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
 * Worker L-41's first half: the announce heartbeat is the <b>worker's</b>, and it reads what this
 * node holds <i>now</i>.
 *
 * <p>The failure this pins is not a missing timer — the timer has always been scheduled — but a
 * heartbeat that describes the moment a world was hosted rather than the moment it announces. A
 * world hosted before its first region was seeded would then stay advertised as holding nothing,
 * for as long as the session lasted, and the content a joiner needs would be present on disk and
 * invisible on the network.
 *
 * <p>The heartbeat body is driven directly here rather than waited on: what could rot is what the
 * announce reads, not the one line that schedules it, and a test that sleeps through a real
 * interval buys nothing but a slow suite.
 */
final class WorkerHeartbeatHoldingsTest {

    /** A tracker endpoint on a port nothing listens to: the announce is built, then fails to send. */
    private static TrackerClient.Endpoint deadEndpoint() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            return new TrackerClient.Endpoint("127.0.0.1", probe.getLocalPort(),
                    TrackerClient.Transport.TCP);
        }
    }

    private static WorldHostingService hosting(
            java.util.function.Function<String, List<ManifestHolding>> holdings) throws Exception {
        NodeIdentity identity = NodeIdentity.generate();
        return new WorldHostingService(identity, NodeCapabilities.initial(),
                () -> "127.0.0.1:25620", List.of(deadEndpoint()), List.of(), holdings);
    }

    /**
     * Wait for the hosting scheduler to have read the holdings at least {@code target} times.
     * {@code refreshNow} hands the announce to that scheduler rather than running it on the
     * caller's thread — a control connection must not wait on a tracker round trip — so the
     * observation is necessarily asynchronous.
     */
    private static void awaitReads(AtomicInteger reads, int target) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (reads.get() < target && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(reads.get()).isGreaterThanOrEqualTo(target);
    }

    @Test
    @DisplayName("every heartbeat re-reads the holdings, so content seeded later is advertised")
    void theHeartbeatReadsLiveHoldings() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try (WorldHostingService svc = hosting(world -> {
            reads.incrementAndGet();
            return List.of();
        })) {
            svc.host("abcdef01", "A World", "{}");
            int atHost = reads.get();
            assertThat(atHost).isPositive();

            svc.refreshNow("abcdef01");

            // Holdings captured at HOST would leave a region seeded later invisible for the whole
            // session — present on disk, absent from every announce.
            awaitReads(reads, atHost + 1);
        }
    }

    @Test
    @DisplayName("a world this node does not host is not announced")
    void refreshingAnUnknownWorldAnnouncesNothing() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try (WorldHostingService svc = hosting(world -> {
            reads.incrementAndGet();
            return List.of();
        })) {
            svc.refreshNow("deadbeef");
            assertThat(reads.get()).isZero();
        }
    }

    @Test
    @DisplayName("the announce cadence is the tracker's, with a floor this node keeps")
    void theCadenceHasAFloor() throws Exception {
        try (WorldHostingService svc = hosting(world -> List.of())) {
            // The interval belongs to a process that outlives the game: that is what "on its own
            // timer" means in the row, and the floor is what stops a tracker driving it.
            assertThat(svc.refreshIntervalSeconds())
                    .isGreaterThanOrEqualTo(WorldHostingService.MINIMUM_REFRESH_SECONDS);
        }
    }

    @Test
    @DisplayName("an unreachable tracker never stops the heartbeat")
    void anUnreachableTrackerIsSurvivable() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try (WorldHostingService svc = hosting(world -> {
            reads.incrementAndGet();
            return List.of();
        })) {
            svc.host("abcdef01", "A World", "{}");
            // Every endpoint here is dead. A heartbeat that threw would take the scheduler's only
            // thread with it and the world would silently stop being announced from then on.
            for (int i = 0; i < 5; i++) {
                svc.refreshNow("abcdef01");
            }
            awaitReads(reads, 6);
        }
    }
}
