package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.WorldHealth;
import dev.nodera.protocol.discovery.TrackerQuery;
import dev.nodera.protocol.discovery.TrackerResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tracker joins liveness with holdings and classifies health. The health rules are the
 * safety-critical part: zero seeders inside the retention window is DEGRADED-with-countdown, not
 * DEAD, so a host rebooting does not watch its world go gray.
 *
 * <p>Thread-context: single test thread.
 */
final class TrackerServiceTest {

    @Test
    void answersPeersSeedersAndCountsFromLiveState() {
        PeerDirectory dir = new PeerDirectory();
        ArchiveInventory inv = new ArchiveInventory();
        TrackerService tracker = new TrackerService(dir, inv);
        Bytes a = DiscoveryFixtures.worldHash("a");

        dir.seen(a, DiscoveryFixtures.entry(DiscoveryFixtures.node(1), 0.8), 0L);
        dir.seen(a, DiscoveryFixtures.entry(DiscoveryFixtures.node(2), 1.0), 0L);
        inv.record(a, DiscoveryFixtures.manifestHash(1), DiscoveryFixtures.node(1),
                DiscoveryFixtures.bitmapOf(java.util.Set.of(0, 1, 2)));

        TrackerResponse r = tracker.answer(new TrackerQuery(a), 0L);

        assertThat(r.genesisHash()).isEqualTo(a);
        assertThat(r.worldPlayerCount()).isEqualTo(2);
        assertThat(r.peers()).hasSize(2);
        assertThat(r.seeders()).hasSize(1);
        assertThat(r.storedChunks()).isEqualTo(3L);
        // mean reliability (0.8 + 1.0)/2 = 0.9 → 9000 bps
        assertThat(r.reliabilityBps()).isEqualTo(9000);
    }

    @Test
    void anUnregisteredWorldAnswersEmptyRatherThanThrowing() {
        TrackerService tracker = new TrackerService(new PeerDirectory(), new ArchiveInventory());
        Bytes unknown = DiscoveryFixtures.worldHash("ghost");

        TrackerResponse r = tracker.answer(new TrackerQuery(unknown), 0L);

        assertThat(r.worldPlayerCount()).isZero();
        assertThat(r.storedChunks()).isZero();
        assertThat(r.reliabilityBps()).isZero();
        assertThat(r.health()).isEqualTo(WorldHealth.DEGRADED);
        assertThat(r.worldName()).isEmpty();
    }

    @Test
    void namesAreDirectoryMetadataLookedUpByGenesisHash() {
        TrackerService tracker = new TrackerService(new PeerDirectory(), new ArchiveInventory());
        Bytes a = DiscoveryFixtures.worldHash("a");
        tracker.registerWorld(a, TrackerService.WorldInfo.named("Survival"));

        assertThat(tracker.answer(new TrackerQuery(a), 0L).worldName()).isEqualTo("Survival");
    }

    @Test
    void healthyRequiresEnoughSeeders() {
        TrackerService tracker = new TrackerService(new PeerDirectory(), new ArchiveInventory(), 5);
        Bytes a = DiscoveryFixtures.worldHash("a");

        tracker.directory().seen(a, DiscoveryFixtures.entry(DiscoveryFixtures.node(1), false), 0L);
        // 4 seeders < floor 5 → DEGRADED even though the world is alive
        for (int i = 1; i <= 4; i++) {
            tracker.inventory().record(a, DiscoveryFixtures.manifestHash(1),
                    DiscoveryFixtures.node(i), DiscoveryFixtures.bitmapOf(java.util.Set.of(0)));
        }
        assertThat(tracker.answer(new TrackerQuery(a), 0L).health()).isEqualTo(WorldHealth.DEGRADED);

        tracker.inventory().record(a, DiscoveryFixtures.manifestHash(1),
                DiscoveryFixtures.node(5), DiscoveryFixtures.bitmapOf(java.util.Set.of(0)));
        assertThat(tracker.answer(new TrackerQuery(a), 0L).health()).isEqualTo(WorldHealth.HEALTHY);
    }

    @Test
    void zeroSeedersInsideTheRetentionWindowIsDegradedNotDead() {
        TrackerService tracker = new TrackerService(new PeerDirectory(), new ArchiveInventory());
        Bytes a = DiscoveryFixtures.worldHash("a");
        long deadline = 10_000L;
        tracker.registerWorld(a, new TrackerService.WorldInfo("a", deadline));

        // No seeders, but the countdown has not expired.
        WorldHealth before = tracker.healthOf(0, deadline, deadline - 1);
        assertThat(before).isEqualTo(WorldHealth.DEGRADED);

        // Only an EXPIRED countdown flips a zero-seeder world to DEAD.
        assertThat(tracker.healthOf(0, deadline, deadline)).isEqualTo(WorldHealth.DEAD);
        assertThat(tracker.healthOf(0, deadline, deadline + 1)).isEqualTo(WorldHealth.DEAD);
        // No countdown at all → stays DEGRADED forever (the host simply has not enabled retention).
        assertThat(tracker.healthOf(0, 0, 1_000_000)).isEqualTo(WorldHealth.DEGRADED);
    }

    @Test
    void responseCarriesTheRetentionDeadlineForTheUiCountdown() {
        TrackerService tracker = new TrackerService(new PeerDirectory(), new ArchiveInventory());
        Bytes a = DiscoveryFixtures.worldHash("a");
        long deadline = 50_000L;
        tracker.registerWorld(a, new TrackerService.WorldInfo("a", deadline));

        TrackerResponse r = tracker.answer(new TrackerQuery(a), 0L);
        assertThat(r.hasRetentionCountdown()).isTrue();
        assertThat(r.retentionDeadlineEpochMillis()).isEqualTo(deadline);
    }

    @Test
    void outOfRangeReliabilityIsClampedToBasisPoints() {
        assertThat(TrackerResponse.toBasisPoints(1.5)).isEqualTo(10_000);
        assertThat(TrackerResponse.toBasisPoints(-0.2)).isZero();
        assertThat(TrackerResponse.toBasisPoints(Double.NaN)).isZero();
        assertThatThrownBy(() -> new TrackerResponse(DiscoveryFixtures.worldHash("a"), "x",
                java.util.List.of(), java.util.List.of(), 0, 0, 10_001, WorldHealth.HEALTHY, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
