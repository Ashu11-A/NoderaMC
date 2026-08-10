package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.protocol.content.WorldManifestAnswer;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.storage.ContentStore;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import dev.nodera.transport.socket.SocketPeerTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A little swarm of {@link WorldArchiveService} nodes, over either a loopback network or real TCP.
 *
 * <p>Eight tests in this package spelled the same thing out by hand — an identity, a transport, an
 * {@link InMemoryContentStore}, a service, a handler bridging the transport to the service, a start,
 * and a four-branch {@code tearDown} — once per <i>test method</i> in the larger ones. The
 * differences between the copies were never deliberate: some bridged before seeding and some after,
 * some stopped the transport before closing the service and some the other way round.
 *
 * <p><b>The bridge no longer swallows.</b> Every copy of it read
 * {@code catch (RuntimeException ignored)} with a comment about the production mux fanning out the
 * same way — but in these tests the bridge is the <i>only</i> path by which a frame reaches the
 * service under test, so a frame that fails to decode or to dispatch is not a frame for another
 * lane, it is the test's subject going missing. A renumbered wire tag would present as a 30–60 s
 * fetch timeout and never as "decode failed". Failures are recorded per node and {@link #close()}
 * fails the test with them.
 *
 * <p>Thread-context: built and closed on the test thread; handlers run on transport threads, so the
 * failure list is a {@link CopyOnWriteArrayList}.
 */
final class ArchiveMesh implements AutoCloseable {

    private final HashService hashes = new HashService();
    private final List<Node> nodes = new ArrayList<>();
    private final List<String> undeliverable = new CopyOnWriteArrayList<>();

    /**
     * Set before the close loop, so the recorded list holds only frames that failed while the mesh
     * was live.
     *
     * <p>{@link #close()} walks the nodes one at a time, closing each node's service before its
     * transport — so between the first and the last there is a window in which one node is shut down
     * and its neighbours are still running and still able to send to it. A frame that lands in that
     * window is not the mesh failing to deliver the test's subject; it is the test being over. Every
     * entry recorded here has to be the former, because that is what the assertion at the end of
     * {@code close()} claims it is, and a teardown artefact reported as a delivery failure is a
     * failure whose cause is in this file.
     */
    private volatile boolean tearingDown;

    private ArchiveMesh() {
    }

    /**
     * {@code count} nodes on one in-JVM {@link LoopbackTransport} network, every one bridged.
     *
     * @param count how many nodes.
     * @return the started mesh.
     */
    static ArchiveMesh loopback(int count) {
        ArchiveMesh mesh = new ArchiveMesh();
        LoopbackTransport.LoopbackNetwork network = LoopbackTransport.LoopbackNetwork.newNetwork();
        for (int i = 0; i < count; i++) {
            NodeIdentity identity = NodeIdentity.generate();
            mesh.add(identity, network.register(identity.nodeId()), null);
        }
        return mesh;
    }

    /**
     * One loopback node over a store the caller built — an {@code FsContentStore} on a
     * {@code @TempDir}, when the claim is about eviction, pinning or a disk budget.
     *
     * @param store the content store the service will use.
     * @return the started one-node mesh.
     */
    static ArchiveMesh loopbackOver(ContentStore store) {
        ArchiveMesh mesh = new ArchiveMesh();
        NodeIdentity identity = NodeIdentity.generate();
        mesh.add(identity,
                LoopbackTransport.LoopbackNetwork.newNetwork().register(identity.nodeId()), store);
        return mesh;
    }

    /**
     * {@code count} nodes on real loopback TCP, every one bridged and listening on an ephemeral
     * port.
     *
     * <p>Sockets rather than the loopback network wherever the claim is about addressing, framing or
     * handler wiring: a loopback transport delivers by node id and exercises none of those, which is
     * how an in-process measurement of 9.8 MB/s coexisted with a live run reporting
     * {@code 0/73 piece(s) after 120s}.
     *
     * @param count how many nodes.
     * @return the started mesh.
     */
    static ArchiveMesh sockets(int count) {
        ArchiveMesh mesh = new ArchiveMesh();
        for (int i = 0; i < count; i++) {
            NodeIdentity identity = NodeIdentity.generate();
            mesh.add(identity,
                    new SocketPeerTransport(identity, "127.0.0.1", 0, "127.0.0.1"), null);
        }
        return mesh;
    }

    private void add(NodeIdentity identity, PeerTransport transport, ContentStore over) {
        ContentStore store = over != null ? over : new InMemoryContentStore(hashes);
        WorldArchiveService service = new WorldArchiveService(identity, transport, store, List.of());
        Node node = new Node(identity, transport, store, service);
        bridge(node);
        transport.start();
        nodes.add(node);
    }

    private void bridge(Node node) {
        node.transport.setHandler(new MessageHandler() {
            @Override
            public void onMessage(PeerAddress from, byte[] frame) {
                if (node.muted) {
                    return;
                }
                try {
                    node.service.onMessage(from, WireCodec.decode(frame));
                } catch (RuntimeException undelivered) {
                    if (tearingDown || node.closed) {
                        // The test is over, or this node was closed on purpose while its neighbours
                        // were still live -- ArchiveLaneTest does exactly that in
                        // theHeldCopySurvivesAnUnreachableNewerVersion. Either way the throw is the
                        // close, not a delivery failure; see the field's javadoc.
                        return;
                    }
                    undeliverable.add(node.identity.nodeId() + " could not take a "
                            + frame.length + "-byte frame from " + from + ": " + undelivered);
                }
            }

            @Override
            public void onPeerDown(PeerAddress peer) {
            }
        });
    }

    /**
     * The node at {@code index}, in creation order.
     *
     * @param index 0-based.
     * @return that node.
     */
    Node node(int index) {
        return nodes.get(index);
    }

    /**
     * The hasher every node shares, for deriving world ids the way production does.
     *
     * @return the hash service.
     */
    HashService hashes() {
        return hashes;
    }

    /**
     * A world id from a name, the way {@code NODERA-HOST} derives one.
     *
     * @param worldName any string.
     * @return its SHA-256.
     */
    Bytes worldId(String worldName) {
        return hashes.sha256(worldName.getBytes());
    }

    /**
     * Teach {@code from} how to dial {@code to} — the state {@code resolveSeeders} leaves behind
     * after a tracker lookup that reported a routable peer.
     *
     * @param from the node that will dial.
     * @param to the node it will dial.
     */
    void route(int from, int to) {
        Node target = nodes.get(to);
        if (!(target.transport instanceof SocketPeerTransport socket)) {
            throw new IllegalStateException("only a socket node has a dialable route");
        }
        nodes.get(from).service.learnRoute(target.identity.nodeId(), socket.listenRoute());
    }

    /**
     * A world of {@code size} bytes, deterministic per seed so equality assertions mean something.
     *
     * @param seed the PRNG seed.
     * @param size how many bytes.
     * @return the blob.
     */
    static byte[] blob(long seed, int size) {
        byte[] raw = new byte[size];
        new Random(seed).nextBytes(raw);
        return raw;
    }

    /**
     * The manifest-exchange answer (tag 52) carrying one manifest, as a seeder would send it.
     *
     * @param worldId the world the manifest belongs to.
     * @param manifest the manifest.
     * @return the answer message.
     */
    static WorldManifestAnswer answerCarrying(Bytes worldId, PieceManifest manifest) {
        CanonicalWriter writer = new CanonicalWriter();
        manifest.encode(writer);
        return new WorldManifestAnswer(worldId, List.of(writer.toBytes()));
    }

    /**
     * Stop every node, then fail if any frame never reached the service it was addressed to.
     *
     * <p>Order matters and was inconsistent across the copies: services close first, so a service
     * shutting down cannot be handed a frame by a transport that is still running.
     *
     * <p>Recording stops before the first node is touched ({@link #tearingDown}) and for any node a
     * test closed itself, so what this
     * asserts on is exactly the set of frames that went missing while the test was running. The
     * assertion itself stays: it is the only thing standing between a renumbered wire tag and a
     * 30–60 s fetch timeout with no stated cause.
     */
    @Override
    public void close() {
        tearingDown = true;
        for (Node node : nodes) {
            node.closeQuietly();
        }
        assertThat(undeliverable)
                .as("every frame the mesh delivered reached the service it was addressed to; a "
                        + "renumbered tag or a changed encoding shows up here rather than as a "
                        + "fetch that times out with no explanation")
                .isEmpty();
    }

    /** One peer: its identity, transport, content store and archive service. */
    static final class Node implements AutoCloseable {

        private final NodeIdentity identity;
        private final PeerTransport transport;
        private final ContentStore store;
        private final WorldArchiveService service;
        private volatile boolean muted;
        private volatile boolean closed;

        private Node(NodeIdentity identity, PeerTransport transport,
                ContentStore store, WorldArchiveService service) {
            this.identity = identity;
            this.transport = transport;
            this.store = store;
            this.service = service;
        }

        /** @return this node's identity. */
        NodeIdentity identity() {
            return identity;
        }

        /** @return this node's id, for a seeder set or a {@link PeerAddress}. */
        NodeId nodeId() {
            return identity.nodeId();
        }

        /** @return the archive service under test. */
        WorldArchiveService service() {
            return service;
        }

        /** @return this node's content store, for asserting what is and is not on its disk. */
        ContentStore store() {
            return store;
        }

        /** @return the transport, for a test that pulls it out from under a transfer. */
        PeerTransport transport() {
            return transport;
        }

        /** @return an address for this node, as an inbound frame would carry. */
        PeerAddress address() {
            return PeerAddress.of(identity.nodeId(), "loopback");
        }

        /**
         * Stop answering, without stopping listening — a peer whose pieces are "still arriving".
         *
         * <p>The test drives its answers by hand in that case, so a real service answering the
         * manifest query would settle the very race the test exists to hold open.
         */
        void mute() {
            muted = true;
        }

        /** Stop this node's transport, mid-test, the way a departing peer does. */
        void stopTransport() {
            transport.stop();
        }

        /** Close the service and stop the transport; safe to call twice. */
        @Override
        public void close() {
            closeQuietly();
        }

        private void closeQuietly() {
            if (closed) {
                return;
            }
            closed = true;
            service.close();
            transport.stop();
        }
    }
}
