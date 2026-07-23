package dev.nodera.simulation;

import dev.nodera.core.crypto.CanonicalEncoder;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionBounds;
import dev.nodera.core.state.BlockEventEntry;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.ScheduledTickEntry;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 13 / L-26 state-model foundation, {@code @Invariant(10)}: the scheduled-tick queue and
 * pending block events are PART of the hashed region root. The forced-failure class this
 * prevents — "peers agree on blocks yet diverge later" because one replica's schedule differs —
 * must be VISIBLE in the root immediately, and pre-redstone snapshots must keep their exact
 * historical bytes.
 */
final class ScheduledStateRootTest {

    private static final HashService HASHES = new HashService();

    private MutableRegionState state() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(TestFixtures.region(0, 0), 0);
        return new MutableRegionState(base, RegionBounds.of(TestFixtures.region(0, 0)));
    }

    @Test
    void identicalBlocksDifferentScheduleDifferentRoot() {
        MutableRegionState a = state();
        MutableRegionState b = state();
        b.scheduleTick(new NBlockPos(5, 70, 5), 1, 12L, 0);

        StateRoot rootA = StateRoot.of(HASHES.hash(a.toSnapshot(new SnapshotVersion(1), 1)));
        StateRoot rootB = StateRoot.of(HASHES.hash(b.toSnapshot(new SnapshotVersion(1), 1)));
        assertThat(rootA)
                .as("a replica with a pending tick MUST diverge in the root immediately")
                .isNotEqualTo(rootB);
    }

    @Test
    void identicalSchedulesIdenticalRoots() {
        MutableRegionState a = state();
        MutableRegionState b = state();
        for (MutableRegionState s : List.of(a, b)) {
            s.scheduleTick(new NBlockPos(5, 70, 5), 1, 12L, 0);
            s.scheduleTick(new NBlockPos(6, 70, 5), 1, 12L, 0);
            s.enqueueBlockEvent(new BlockEventEntry(new NBlockPos(7, 70, 5), 0, 2, 0));
        }
        assertThat(HASHES.hash(a.toSnapshot(new SnapshotVersion(1), 1)))
                .isEqualTo(HASHES.hash(b.toSnapshot(new SnapshotVersion(1), 1)));
    }

    @Test
    void emptyScheduleKeepsThePreRedstoneBytes() {
        // Hash-stability guarantee: a region with no scheduled state still encodes at body
        // version 2 — every pre-redstone root (live store heads included) stays byte-identical.
        RegionSnapshot snapshot = state().toSnapshot(new SnapshotVersion(1), 1);
        assertThat(snapshot.bodyVersion()).isEqualTo(RegionSnapshot.STATE_ENCODING_VERSION);
        RegionSnapshot preRedstone = new RegionSnapshot(
                snapshot.region(), snapshot.version(), snapshot.tick(), snapshot.chunks(),
                snapshot.entities());
        assertThat(CanonicalEncoder.encode(snapshot))
                .isEqualTo(CanonicalEncoder.encode(preRedstone));
    }

    @Test
    void totalOrderIsDueTickThenPriorityThenSeq() {
        MutableRegionState state = state();
        var late = state.scheduleTick(new NBlockPos(1, 70, 1), 1, 20L, 0);
        var earlyLowPriority = state.scheduleTick(new NBlockPos(2, 70, 2), 1, 10L, 3);
        var earlyHighPriority = state.scheduleTick(new NBlockPos(3, 70, 3), 1, 10L, -3);
        var earlyHighPrioritySecond = state.scheduleTick(new NBlockPos(4, 70, 4), 1, 10L, -3);

        assertThat(state.drainDueTicks(10L))
                .as("due ticks fire in (dueTick, priority, seq) order")
                .containsExactly(earlyHighPriority, earlyHighPrioritySecond, earlyLowPriority);
        assertThat(state.scheduledTicks()).containsExactly(late);
        assertThat(state.drainDueTicks(9L)).isEmpty();
    }

    @Test
    void scheduledStateSurvivesSnapshotRoundTripAndReload() {
        MutableRegionState state = state();
        state.scheduleTick(new NBlockPos(5, 70, 5), 1, 12L, 0);
        state.enqueueBlockEvent(new BlockEventEntry(new NBlockPos(7, 70, 5), 0, 2, 1));
        RegionSnapshot snapshot = state.toSnapshot(new SnapshotVersion(1), 1);
        assertThat(snapshot.bodyVersion()).isEqualTo(RegionSnapshot.REDSTONE_ENCODING_VERSION);

        CanonicalWriter w = new CanonicalWriter();
        snapshot.encode(w);
        RegionSnapshot decoded = RegionSnapshot.decode(new CanonicalReader(w.toByteArray()));
        assertThat(decoded).isEqualTo(snapshot);

        // A replica reloading the snapshot resumes the SAME queue — including seq continuity:
        // the next scheduled tick must never reuse a seq already committed to the root.
        MutableRegionState reloaded = new MutableRegionState(
                decoded, RegionBounds.of(decoded.region()));
        assertThat(reloaded.scheduledTicks()).isEqualTo(snapshot.scheduledTicks());
        var next = reloaded.scheduleTick(new NBlockPos(6, 70, 6), 1, 13L, 0);
        assertThat(next.seq())
                .isGreaterThan(snapshot.scheduledTicks().getLast().seq());
    }

    @Test
    void entryCodecsRoundTripIncludingNegativePriority() {
        ScheduledTickEntry tick = new ScheduledTickEntry(new NBlockPos(-5, 70, -7), 3, 99L, -3, 4L);
        CanonicalWriter w = new CanonicalWriter();
        tick.encode(w);
        assertThat(ScheduledTickEntry.decode(new CanonicalReader(w.toByteArray())))
                .isEqualTo(tick);

        BlockEventEntry event = new BlockEventEntry(new NBlockPos(1, 2, 3), 0, 5, 1);
        CanonicalWriter w2 = new CanonicalWriter();
        event.encode(w2);
        assertThat(BlockEventEntry.decode(new CanonicalReader(w2.toByteArray())))
                .isEqualTo(event);
    }
}
