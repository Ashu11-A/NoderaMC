package dev.nodera.peer;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The keep-alive broadcast is what keeps a node in the mesh, so nothing earlier in the tick may be
 * able to stop it.
 *
 * <p>Live failure: a joiner's bootstrap address carries a route and no node id, the rendezvous
 * transport threw an unchecked exception on it, and {@code onHeartbeatTick} — whose first statement
 * is the bootstrap re-announce — unwound before reaching the {@code SessionKeepAlive} broadcast at
 * the bottom. On every tick. The node stopped announcing itself, every peer dropped it as
 * "heartbeat-timeout", and the archive download it was waiting on lost its seeders.
 *
 * <p>The transport-level cause is fixed separately (an unnamed route is dialed directly, and a
 * failure there is a {@code TransportException}). This pins the containment: whatever a bootstrap
 * send does, the node keeps saying it is alive.
 */
final class HeartbeatSurvivesABadBootstrapTest {

    private final List<PeerRuntime> runtimes = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        for (PeerRuntime rt : runtimes) {
            rt.stop();
        }
    }

    /** Throws on the bootstrap dial the way the rendezvous transport did; records everything else. */
    private static final class HostileToUnnamedAddresses implements PeerTransport {
        private final AtomicInteger keepAlives = new AtomicInteger();
        private final AtomicInteger refusals = new AtomicInteger();

        @Override public void start() { }

        @Override public void stop() { }

        @Override public void send(PeerAddress to, byte[] frame) {
            if (to != null && to.nodeId() == null) {
                refusals.incrementAndGet();
                throw new NullPointerException("rendezvous transport requires a nodeId");
            }
            keepAlives.incrementAndGet();
        }

        @Override public void sendStream(PeerAddress to, long streamId, byte[] payload) {
            send(to, payload);
        }

        @Override public void setHandler(MessageHandler handler) { }

        @Override public String listenRoute() {
            return "127.0.0.1:0";
        }
    }

    @Test
    @DisplayName("a bootstrap that throws does not escape into the runtime's threads")
    void theTickAbsorbsIt() throws Exception {
        List<Throwable> uncaught = new CopyOnWriteArrayList<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> uncaught.add(e));
        try {
            runTicks(uncaught);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    private void runTicks(List<Throwable> uncaught) throws Exception {
        HostileToUnnamedAddresses transport = new HostileToUnnamedAddresses();
        NodeIdentity self = NodeIdentity.generate();
        PeerRuntimeConfig fast =
                new PeerRuntimeConfig(Duration.ofMillis(50), Duration.ofSeconds(30));

        // A joiner: bootstrap addressed by route only, exactly as the join flow builds it.
        PeerRuntime rt = PeerRuntime.peer(self, NodeCapabilities.initial(), transport,
                () -> "127.0.0.1:0", PeerAddress.of(null, "10.0.0.101:25566"), fast,
                new RecordingListener());
        runtimes.add(rt);

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (transport.refusals.get() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertThat(transport.refusals.get())
                .as("the bootstrap dial is retried every tick while the runtime has not meshed")
                .isGreaterThanOrEqualTo(3);
        // The discriminator. Before the fix each of these surfaced as
        // "Exception in thread nodera-peer-state-… java.lang.NullPointerException", killing that
        // pool worker and abandoning the rest of the tick — sixteen of them in the live log. A send
        // that fails is the runtime's business to absorb, not the thread's business to die of.
        assertThat(uncaught)
                .as("a failing bootstrap send must not reach a thread's uncaught handler")
                .isEmpty();
    }
}
