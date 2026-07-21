package dev.nodera.diagnostics.metric;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fixed-point validator and region skew behavior for {@link TickSkewMeter}. */
final class TickSkewMeterTest {

    private static final NodeId NODE = new NodeId(new UUID(1L, 2L));
    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 3, 4);

    @Test
    void rejectsInvalidEmaWeightsAndTicks() {
        assertThatThrownBy(() -> new TickSkewMeter(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TickSkewMeter(10_001))
                .isInstanceOf(IllegalArgumentException.class);

        TickSkewMeter meter = new TickSkewMeter(5_000);
        assertThatThrownBy(() -> meter.recordValidator(NODE, REGION, -1L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> meter.recordRegion(NODE, REGION, 0L, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void computesValidatorSkewEmaWithoutPromotingRemoteTicksToReference() {
        TickSkewMeter meter = new TickSkewMeter(5_000);

        meter.recordValidator(NODE, REGION, 90L, 100L); // 10.0000 ticks
        assertThat(meter.validatorSkewBasisPoints(NODE, REGION)).isEqualTo(100_000L);

        meter.recordValidator(NODE, REGION, 90L, 110L); // raw 20; EMA = 15 ticks
        assertThat(meter.validatorSkewBasisPoints(NODE, REGION)).isEqualTo(150_000L);

        meter.recordValidator(NODE, REGION, 1_000L, 105L); // stale ref, remote is not ref
        TickSkewMeter.Snapshot snapshot = meter.snapshot(NODE, REGION).orElseThrow();
        assertThat(snapshot.validatorSkewBasisPoints()).isEqualTo(75_000L);
        assertThat(snapshot.validatorReferenceTick()).isEqualTo(110L);
        assertThat(snapshot.validatorAppliedTick()).isEqualTo(1_000L);
    }

    @Test
    void computesRegionSkewAgainstMonotonicCertifiedNetworkMaximum() {
        TickSkewMeter meter = new TickSkewMeter(5_000);

        meter.recordRegion(NODE, REGION, 95L, 100L); // 5 ticks
        meter.recordRegion(NODE, REGION, 100L, 110L); // raw 10; EMA = 7.5 ticks
        meter.recordRegion(NODE, REGION, 90L, 105L); // both stale; raw remains 10

        TickSkewMeter.Snapshot snapshot = meter.snapshot(NODE, REGION).orElseThrow();
        assertThat(snapshot.regionSkewBasisPoints()).isEqualTo(87_500L);
        assertThat(snapshot.regionCommittedTick()).isEqualTo(100L);
        assertThat(snapshot.regionReferenceTick()).isEqualTo(110L);
        assertThat(snapshot.regionSampleCount()).isEqualTo(3L);
    }

    @Test
    void staleValidatorObservationCannotInflateSkew() {
        TickSkewMeter meter = new TickSkewMeter(10_000);
        meter.recordValidator(NODE, REGION, 95L, 100L);
        meter.recordValidator(NODE, REGION, 90L, 100L);

        assertThat(meter.validatorSkewBasisPoints(NODE, REGION)).isEqualTo(50_000L);
        assertThat(meter.snapshot(NODE, REGION).orElseThrow().validatorAppliedTick())
                .isEqualTo(95L);
    }

    @Test
    void snapshotsAreImmutableAndScalingSaturatesSafely() {
        TickSkewMeter meter = new TickSkewMeter(10_000);
        meter.recordValidator(NODE, REGION, 0L, Long.MAX_VALUE);

        Map<TickSkewMeter.Key, TickSkewMeter.Snapshot> snapshot = meter.snapshot();
        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.values().iterator().next().validatorSkewBasisPoints())
                .isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
    }
}
