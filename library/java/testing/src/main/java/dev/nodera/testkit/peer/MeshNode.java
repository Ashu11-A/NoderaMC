package dev.nodera.testkit.peer;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.transport.PeerAddress;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One peer on a loopback mesh, carrying a single service that speaks the wire.
 *
 * <p>This is the shape behind the gossip lanes — ownership claims, permission grants, tunnels: an
 * identity, a transport, a service constructed over that transport, and a membership list the
 * service reads to decide who to relay to. Each of those suites had written it out as a private
 * inner class of roughly thirty lines, differing only in which service was constructed.
 *
 * <p>The membership list is <b>mutable and per-node</b> rather than a constructor argument, because
 * every one of those suites forms its mesh after the nodes exist — a node that had to know its
 * peers at construction could not model a peer arriving late, which is exactly what one of the
 * tests is about.
 *
 * <p>Thread-context: {@link #peers()} is a copy-on-write list, safe to mutate while the service
 * reads it from its delivery thread.
 *
 * @param <S> the service this node carries.
 */
public final class MeshNode<S> {

    /** The route every loopback peer is addressed on. */
    public static final String ROUTE = "loopback";

    private final NodeIdentity identity;
    private final LoopbackTransport transport;
    private final List<PeerEntry> peers = new CopyOnWriteArrayList<>();
    private S service;

    MeshNode(NodeIdentity identity, LoopbackTransport transport) {
        this.identity = identity;
        this.transport = transport;
    }

    void bind(S built) {
        this.service = built;
    }

    /** @return this node's identity. */
    public NodeIdentity identity() {
        return identity;
    }

    /** @return this node's id. */
    public NodeId nodeId() {
        return identity.nodeId();
    }

    /** @return the transport the service was constructed over. */
    public LoopbackTransport transport() {
        return transport;
    }

    /** @return the service under test. */
    public S service() {
        return service;
    }

    /** @return the mutable membership list the service relays to. */
    public List<PeerEntry> peers() {
        return peers;
    }

    /** @return the entry another node puts in its membership list to reach this one. */
    public PeerEntry entry() {
        return new PeerEntry(identity.nodeId(), ROUTE, NodeCapabilities.initial(), false,
                identity.publicKeyBytes(), "test");
    }

    /** @return this node's loopback address. */
    public PeerAddress address() {
        return PeerAddress.of(identity.nodeId(), ROUTE);
    }

    /**
     * Put a message on the wire by hand, bypassing the service.
     *
     * <p>Every one of these suites needs this: the interesting attacks are the ones a well-behaved
     * {@code publish()} would refuse to send, so the attacker has to speak the wire directly.
     *
     * @param to      the receiving node.
     * @param message the message to encode and send.
     */
    public void send(MeshNode<?> to, NoderaMessage message) {
        transport.send(to.address(), WireCodec.encode(message));
    }

    /** Where a node's decoded inbound messages go. */
    @FunctionalInterface
    public interface MessageSink {

        /**
         * @param from    the sending peer.
         * @param message the decoded message.
         */
        void accept(PeerAddress from, NoderaMessage message);
    }

    /**
     * Builds the service a {@link MeshNode} carries.
     *
     * @param <S> the service type.
     */
    @FunctionalInterface
    public interface ServiceFactory<S> {

        /**
         * @param identity the node's identity.
         * @param transport the node's transport, already registered and not yet started.
         * @param peers    a live view of the node's membership list.
         * @return the service.
         */
        S create(NodeIdentity identity, LoopbackTransport transport,
                 java.util.function.Supplier<List<PeerEntry>> peers);
    }
}
