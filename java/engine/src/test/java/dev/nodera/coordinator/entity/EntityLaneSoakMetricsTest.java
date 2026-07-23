package dev.nodera.coordinator.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class EntityLaneSoakMetricsTest {

    @Test
    void emptyObservationCannotClaimTask12Exit() {
        assertThat(new EntityLaneSoakMetrics().meetsTask12ExitCriterion()).isFalse();
    }

    @Test
    void oneMinuteThrottledGhostFixtureRecordsBandwidthAndPassesThresholds() {
        EntityLaneSoakMetrics metrics = new EntityLaneSoakMetrics();
        for (int tick = 1; tick <= 1_200; tick++) {
            metrics.recordGhostMobTicks(1);
            if (tick % 5 == 0) {
                metrics.recordGhostUpdate(100);
                metrics.recordCommit();
            }
        }

        EntityLaneSoakMetrics.Snapshot result = metrics.snapshot();
        assertThat(result.ghostUpdates()).isEqualTo(240);
        assertThat(result.ghostBytesPerMobMinute()).isEqualTo(24_000);
        assertThat(result.resyncRateBps()).isZero();
        assertThat(result.passes()).isTrue();
    }

    @Test
    void resyncRateAboveOnePercentFailsExitCriterion() {
        EntityLaneSoakMetrics metrics = new EntityLaneSoakMetrics();
        metrics.recordGhostMobTicks(1_200);
        metrics.recordGhostUpdate(100);
        for (int i = 0; i < 100; i++) {
            metrics.recordCommit();
        }
        metrics.recordResync();
        metrics.recordResync();

        assertThat(metrics.snapshot().resyncRateBps()).isGreaterThan(100);
        assertThat(metrics.meetsTask12ExitCriterion()).isFalse();
    }
}
