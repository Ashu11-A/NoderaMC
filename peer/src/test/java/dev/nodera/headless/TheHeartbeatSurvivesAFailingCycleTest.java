package dev.nodera.headless;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.peer.discovery.TrackerClient;
import dev.nodera.protocol.content.ManifestHolding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker that stops announcing has silently un-hosted every world it holds.
 *
 * <p>{@code scheduleWithFixedDelay} cancels a task <b>permanently</b> the first time it throws, and
 * reports that only into a {@code Future} nobody reads. The announce heartbeat is the whole reason
 * a Nodera world outlives the game that shared it, so one exception on one tick does not degrade
 * this node — it removes every world it holds from every tracker, forever, while the process stays
 * up and looks perfectly healthy.
 *
 * <p>Observed live, and it is the worst failure this system has produced: a player left, and the
 * other player could never get back in. Both workers were running. One of them held 394 of 394
 * pieces of the world. Every tracker query — including the one made by the node holding the full
 * copy — answered {@code 0 seeder(s), 0 routable}, because the peer records had aged out and
 * nothing was refreshing them. The joiner sat on "Migrating world…" against a swarm that had a
 * complete copy of the world in it.
 */
final class TheHeartbeatSurvivesAFailingCycleTest {

    /** A tracker endpoint on a port nothing listens to: the announce is built, then fails to send. */
    private static TrackerClient.Endpoint deadEndpoint() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            return new TrackerClient.Endpoint("127.0.0.1", probe.getLocalPort(),
                    TrackerClient.Transport.TCP);
        }
    }

    private static WorldHostingService hosting(
            java.util.function.Function<String, List<ManifestHolding>> holdings) throws Exception {
        return new WorldHostingService(NodeIdentity.generate(), NodeCapabilities.initial(),
                () -> "127.0.0.1:25620", List.of(deadEndpoint()), List.of(), holdings);
    }

    @Test
    @DisplayName("a cycle that throws does not end the heartbeat")
    void oneBadCycleDoesNotStopTheAnnounce() throws Exception {
        AtomicInteger cycles = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean failNow =
                new java.util.concurrent.atomic.AtomicBoolean();
        try (WorldHostingService svc = hosting(world -> {
            cycles.incrementAndGet();
            // Fail the way a signing or content-plane fault would: with an exception, on the
            // heartbeat's own thread, from inside the announce path.
            if (failNow.get()) {
                throw new IllegalStateException("holdings unavailable this cycle");
            }
            return List.of();
        })) {
            svc.host("abcdef01", "A World", "{}");

            int before = cycles.get();
            failNow.set(true);
            svc.reconcile(); // must not propagate: this body runs on the shared scheduler thread
            int afterFailing = cycles.get();
            assertThat(afterFailing).as("the failing cycle ran").isGreaterThan(before);

            failNow.set(false);
            svc.reconcile();
            assertThat(cycles.get())
                    .as("and the next one still happened — a failed cycle is not a dead lane")
                    .isGreaterThan(afterFailing);
        }
    }

    @Test
    @DisplayName("one world that cannot be announced does not silence the others")
    void aFailingWorldDoesNotTakeTheRestDown() throws Exception {
        AtomicInteger good = new AtomicInteger();
        try (WorldHostingService svc = hosting(world -> {
            if (world.equals("aaaaaaaa")) {
                throw new IllegalStateException("this world's holdings cannot be read");
            }
            good.incrementAndGet();
            return List.of();
        })) {
            svc.host("aaaaaaaa", "Broken", "{}");
            svc.host("bbbbbbbb", "Fine", "{}");
            svc.host("cccccccc", "Also fine", "{}");
            good.set(0);

            svc.reconcile();

            assertThat(good.get())
                    .as("a worker holding several worlds must not go dark on all of them "
                            + "because of the first one")
                    .isEqualTo(2);
        }
    }
}
