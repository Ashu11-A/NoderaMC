package dev.nodera.diagnostics.metric;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Injected-time, fixed-point commit-throughput behavior for {@link TpsMeter}. */
final class TpsMeterTest {

    private static final long MILLISECOND = 1_000_000L;
    private static final NodeId NODE = new NodeId(new UUID(3L, 4L));
    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), -2, 7);

    @Test
    void rejectsInvalidEmaWeightsAndAppliedTicks() {
        assertThatThrownBy(() -> new TpsMeter(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TpsMeter(10_001))
                .isInstanceOf(IllegalArgumentException.class);

        TpsMeter meter = new TpsMeter(5_000);
        assertThatThrownBy(() -> meter.recordCommit(NODE, REGION, -1L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countsCommitsRatherThanNumericTickDeltaAndAppliesEma() {
        TpsMeter meter = new TpsMeter(5_000);
        meter.recordCommit(NODE, REGION, 10L, 0L); // baseline only
        meter.recordCommit(NODE, REGION, 100L, 50L * MILLISECOND); // one commit / 50 ms

        assertThat(meter.commitsPerSecondBasisPoints(NODE, REGION)).isEqualTo(200_000L);

        meter.recordCommit(NODE, REGION, 101L, 150L * MILLISECOND); // raw 10 TPS
        assertThat(meter.tpsBasisPoints(NODE, REGION)).isEqualTo(150_000L);
    }

    @Test
    void staleTickAndTimeSamplesCannotInflateTps() {
        TpsMeter meter = new TpsMeter(10_000);
        meter.recordCommit(NODE, REGION, 10L, 1_000L * MILLISECOND);
        meter.recordCommit(NODE, REGION, 11L, 1_050L * MILLISECOND); // 20 TPS

        meter.recordCommit(NODE, REGION, 11L, 1_051L * MILLISECOND); // stale tick
        meter.recordCommit(NODE, REGION, 12L, 1_040L * MILLISECOND); // stale time
        meter.recordCommit(NODE, REGION, 12L, 1_060L * MILLISECOND); // already observed tick
        assertThat(meter.tpsBasisPoints(NODE, REGION)).isEqualTo(200_000L);

        meter.recordCommit(NODE, REGION, 13L, 1_150L * MILLISECOND);
        TpsMeter.Snapshot snapshot = meter.snapshot(NODE, REGION).orElseThrow();
        assertThat(snapshot.tpsBasisPoints()).isEqualTo(100_000L);
        assertThat(snapshot.highestAppliedTick()).isEqualTo(13L);
        assertThat(snapshot.lastRateSampleNanos()).isEqualTo(1_150L * MILLISECOND);
        assertThat(snapshot.acceptedCommitCount()).isEqualTo(3L);
        assertThat(snapshot.rateSampleCount()).isEqualTo(2L);
    }

    @Test
    void timestampSubtractionOverflowIsConservative() {
        TpsMeter meter = new TpsMeter(10_000);
        meter.recordCommit(NODE, REGION, 1L, Long.MIN_VALUE);
        meter.recordCommit(NODE, REGION, 2L, Long.MAX_VALUE);

        assertThat(meter.tpsBasisPoints(NODE, REGION)).isZero();
        assertThat(meter.snapshot(NODE, REGION).orElseThrow().rateSampleCount()).isEqualTo(1L);
    }

    @Test
    void snapshotsAreImmutableAndKeysRemainIndependent() {
        TpsMeter meter = new TpsMeter(10_000);
        NodeId otherNode = new NodeId(new UUID(5L, 6L));
        meter.recordCommit(NODE, REGION, 1L, 0L);
        meter.recordCommit(NODE, REGION, 2L, 50L * MILLISECOND);
        meter.recordCommit(otherNode, REGION, 1L, 0L);

        Map<TpsMeter.Key, TpsMeter.Snapshot> snapshot = meter.snapshot();
        assertThat(snapshot).hasSize(2);
        assertThat(meter.tpsBasisPoints(otherNode, REGION)).isZero();
        assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
    }
}
