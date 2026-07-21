package dev.nodera.testkit;

import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.codec.ChunkedStreams;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.simulationmsg.StreamChunk;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import dev.nodera.transport.TransportException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * In-JVM {@link PeerTransport} that lets Nodera peers be wired together without NeoForge or any
 * real network stack — Task 7 option (a) "Test transport". A {@link LoopbackNetwork} is a closed
 * set of peers that address one another by {@link NodeId} over the well-known route
 * {@code "loopback"}; frames handed to {@link #send(PeerAddress, byte[])} on one transport are
 * delivered to the target transport's {@link MessageHandler} on the target's own single-thread
 * executor, mirroring the off-{@code send}-thread delivery semantics of a real transport.
 *
 * <p>The {@code ClusterHarness} (Task 7) boots the Minecraft-free consensus + simulation stack and
 * connects replicas through this transport so integration tests can drive the full
 * proposal→vote→commit path with zero external dependencies. {@link #sendStream} chunkifies a
 * large payload through {@link ChunkedStreams} exactly as the NeoForge transport would, so tests
 * exercise the real reassembly path.
 *
 * <h2>Threading model</h2>
 *
 * <ul>
 *   <li>{@link #start()}, {@link #stop()}, {@link #send}, {@link #sendStream}, and
 *       {@link #setHandler} are all safe to call from any thread.</li>
 *   <li>Every {@link LoopbackTransport} owns a dedicated single-thread
 *       {@link ExecutorService} created in {@link #start()} and torn down in {@link #stop()}.
 *       Inbound frames are delivered by submitting a task to the <i>receiver's</i> executor, so a
 *       handler always runs <b>off the sender's thread</b> and is serialised per transport
 *       (re-entrancy is bounded: a handler that calls {@code send} cannot re-enter itself
 *       synchronously). This deliberately mirrors the off-network-thread delivery contract
 *       documented on {@link MessageHandler}.</li>
 *   <li>The {@link MessageHandler} field is {@code volatile}; the executor field is guarded by the
 *       transport's lifecycle lock. Delivery snapshots both under/around the lock so a concurrent
 *       {@link #stop()} cannot observe a half-torn-down state.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>A transport is <i>routable</i> (discoverable by other peers) only between {@link #start()}
 * and {@link #stop()}. {@link #stop()} deregisters the transport from its network and notifies
 * every still-routable peer by invoking
 * {@link MessageHandler#onPeerDown(PeerAddress)} with {@code PeerAddress.of(self, "loopback")}
 * on that peer's executor. After {@link #stop()}, a {@link #send} addressed to this transport
 * throws {@link TransportException} ("unknown peer …"), matching the real-transport contract for a
 * disconnected peer.
 *
 * <p>Thread-context: see per-method Javadoc.
 *
 * @see LoopbackNetwork
 */
public final class LoopbackTransport implements PeerTransport {

    /**
     * Closed set of {@link LoopbackTransport} peers that route to one another by {@link NodeId}.
     *
     * <p>A network owns a {@link ConcurrentMap} of <i>active</i> (started, routable) transports
     * keyed by {@link NodeId}. {@link #register(NodeId)} creates a transport bound to this network
     * (not yet routable); {@link LoopbackTransport#start()} makes it routable;
     * {@link LoopbackTransport#stop()} removes it.
     *
     * <p>Thread-context: all methods safe from any thread.
     */
    public static final class LoopbackNetwork {

        private final ConcurrentMap<NodeId, LoopbackTransport> active = new ConcurrentHashMap<>();

        private LoopbackNetwork() {}

        /**
         * Create a fresh, empty loopback network.
         *
         * @return a new {@link LoopbackNetwork}.
         * @Thread-context any thread.
         */
        public static LoopbackNetwork newNetwork() {
            return new LoopbackNetwork();
        }

        /**
         * Create and return a new {@link LoopbackTransport} bound to this network for the given
         * {@code self} id. The returned transport is NOT yet routable; call
         * {@link LoopbackTransport#start()} to make it discoverable to its peers.
         *
         * @param self the local peer's stable id.
         * @return a new, not-yet-started transport.
         * @throws IllegalArgumentException if {@code self} is null.
         * @Thread-context any thread.
         */
        public LoopbackTransport register(NodeId self) {
            Objects.requireNonNull(self, "self");
            return new LoopbackTransport(this, self);
        }

        /**
         * Look up the <i>active</i> (started, routable) transport for {@code nodeId}, or
         * {@code null} if no such transport is currently routable in this network.
         *
         * @param nodeId the peer id to resolve.
         * @return the active transport, or {@code null}.
         * @throws IllegalArgumentException if {@code nodeId} is null.
         * @Thread-context any thread.
         */
        public LoopbackTransport transportOf(NodeId nodeId) {
            Objects.requireNonNull(nodeId, "nodeId");
            return active.get(nodeId);
        }
    }

    private static final String ROUTE = "loopback";
    private static final long SHUTDOWN_AWAIT_SECONDS = 5L;

    private final LoopbackNetwork network;
    private final NodeId self;
    private final Object lifecycleLock = new Object();

    private volatile MessageHandler handler;

    private ExecutorService executor;

    private LoopbackTransport(LoopbackNetwork network, NodeId self) {
        this.network = network;
        this.self = self;
    }

    /**
     * @return this transport's stable {@link NodeId}.
     * @Thread-context any thread.
     */
    public NodeId nodeId() {
        return self;
    }

    @Override
    public void start() {
        synchronized (lifecycleLock) {
            if (executor != null) {
                return;
            }
            LoopbackTransport existing = network.active.putIfAbsent(self, this);
            if (existing != null && existing != this) {
                throw new TransportException("loopback peer already started: " + self);
            }
            executor = Executors.newSingleThreadExecutor(this::newWorkerThread);
        }
    }

    @Override
    public void stop() {
        final ExecutorService toShutdown;
        final List<LoopbackTransport> peers;
        synchronized (lifecycleLock) {
            if (executor == null) {
                return;
            }
            network.active.remove(self, this);
            peers = new ArrayList<>(network.active.values());
            toShutdown = executor;
            executor = null;
        }
        PeerAddress down = PeerAddress.of(self, ROUTE);
        for (LoopbackTransport peer : peers) {
            peer.notifyPeerDown(down);
        }
        toShutdown.shutdown();
        try {
            if (!toShutdown.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                toShutdown.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            toShutdown.shutdownNow();
        }
    }

    @Override
    public void send(PeerAddress to, byte[] frame) {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(frame, "frame");
        // Guard the SENDER's own lifecycle, not just the destination's: a stopped transport must
        // not be able to originate frames (it has deregistered and may be mid-teardown). This
        // mirrors the deliver()-side check and the real-transport contract for a disconnected peer.
        synchronized (lifecycleLock) {
            if (executor == null) {
                throw new TransportException("transport not started: " + self);
            }
        }
        NodeId target = to.nodeId();
        if (target == null) {
            throw new TransportException(
                    "unknown peer: null nodeId (route=" + to.route() + ")");
        }
        LoopbackTransport destination = network.active.get(target);
        if (destination == null) {
            throw new TransportException("unknown peer " + target);
        }
        byte[] owned = frame.clone();
        PeerAddress from = PeerAddress.of(self, ROUTE);
        destination.deliver(from, owned);
    }

    @Override
    public void sendStream(PeerAddress to, long streamId, byte[] payload) {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(payload, "payload");
        List<StreamChunk> chunks = ChunkedStreams.split(streamId, payload);
        for (StreamChunk chunk : chunks) {
            send(to, MessageCodec.encode(chunk));
        }
    }

    @Override
    public void setHandler(MessageHandler handler) {
        this.handler = handler;
    }

    /**
     * Deliver an inbound frame to this transport's handler on this transport's single-thread
     * executor. Throws if this transport is not currently routable (started).
     */
    private void deliver(PeerAddress from, byte[] frame) {
        final ExecutorService exec;
        synchronized (lifecycleLock) {
            exec = executor;
        }
        if (exec == null) {
            throw new TransportException("loopback peer not started: " + self);
        }
        final MessageHandler h = handler;
        if (h == null) {
            return;
        }
        exec.execute(() -> h.onMessage(from, frame));
    }

    /**
     * Deliver a peer-down notification for {@code down} to this transport's handler on its
     * single-thread executor. No-op if this transport is stopped or has no handler installed.
     */
    private void notifyPeerDown(PeerAddress down) {
        final ExecutorService exec;
        synchronized (lifecycleLock) {
            exec = executor;
        }
        if (exec == null) {
            return;
        }
        final MessageHandler h = handler;
        if (h == null) {
            return;
        }
        exec.execute(() -> h.onPeerDown(down));
    }

    private Thread newWorkerThread(Runnable r) {
        Thread t = new Thread(r, "nodera-loopback-" + self);
        t.setDaemon(true);
        return t;
    }
}
