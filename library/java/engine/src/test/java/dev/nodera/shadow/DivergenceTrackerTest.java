package dev.nodera.shadow;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DivergenceTrackerTest {

    private ShadowResult result(RegionId region, StateRoot root, NodeId client) {
        return new ShadowResult(region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL,
                SnapshotVersion.INITIAL.next(), root, client, 0L);
    }

    @Test
    void matchTicksMetricsAndDoesNotPoison() {
        RegionId region = Fixtures.region(0, 0);
        RegionSnapshot snap = Fixtures.fullUniformSnapshot(region, 1);
        StateRoot ref = Fixtures.rootOf(snap);

        DivergenceTracker tracker = new DivergenceTracker();
        boolean match = tracker.compare(ref, result(region, ref, Fixtures.node(1L)));

        assertThat(match).isTrue();
        assertThat(tracker.metrics().stats().matches()).isEqualTo(1);
        assertThat(tracker.metrics().stats().mismatches()).isZero();
        assertThat(tracker.divergences()).isEmpty();
        assertThat(tracker.isPoisoned(region)).isFalse();
    }

    @Test
    void mismatchRecordsDivergenceAndPoisons() {
        RegionId region = Fixtures.region(0, 0);
        StateRoot ref = Fixtures.rootOf(Fixtures.fullUniformSnapshot(region, 1));
        StateRoot wrong = StateRoot.zero();
        NodeId liar = Fixtures.node(42L);

        DivergenceTracker tracker = new DivergenceTracker();
        boolean match = tracker.compare(ref, result(region, wrong, liar));

        assertThat(match).isFalse();
        assertThat(tracker.metrics().stats().mismatches()).isEqualTo(1);
        assertThat(tracker.divergences()).hasSize(1);
        DivergenceRecord rec = tracker.divergences().get(0);
        assertThat(rec.region()).isEqualTo(region);
        assertThat(rec.clientNodeId()).isEqualTo(liar);
        assertThat(rec.expectedRoot()).isEqualTo(ref);
        assertThat(rec.gotRoot()).isEqualTo(wrong);
        assertThat(tracker.isPoisoned(region)).isTrue();

        tracker.clearPoison(region);
        assertThat(tracker.isPoisoned(region)).isFalse();
    }
}
