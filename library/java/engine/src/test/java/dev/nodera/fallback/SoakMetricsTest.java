package dev.nodera.fallback;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SoakMetricsTest {

    @Test
    void ratioAndReasonCounts() {
        SoakMetrics m = new SoakMetrics();
        for (int i = 0; i < 92; i++) {
            m.record(RoutingDecision.of(RoutingReason.DELEGATED_COMMITTEE));
        }
        m.record(RoutingDecision.of(RoutingReason.UNASSIGNED));
        m.record(RoutingDecision.of(RoutingReason.CROSS_REGION));
        m.record(RoutingDecision.of(RoutingReason.CROSS_REGION));
        m.record(RoutingDecision.of(RoutingReason.DISPUTED));

        assertThat(m.total()).isEqualTo(96);
        assertThat(m.committeeBatches()).isEqualTo(92);
        assertThat(m.fallbackBatches()).isEqualTo(4);
        assertThat(m.countReason(RoutingReason.CROSS_REGION)).isEqualTo(2);
        assertThat(m.committeeCommitRatio()).isCloseTo(92.0 / 96.0, within(1e-9));
        assertThat(m.meetsPhase4ExitCriterion()).isTrue(); // 95.8% > 90%
    }

    @Test
    void exactlyNinetyPercentDoesNotMeetStrictThreshold() {
        SoakMetrics m = new SoakMetrics();
        for (int i = 0; i < 9; i++) {
            m.record(RoutingDecision.of(RoutingReason.DELEGATED_COMMITTEE));
        }
        m.record(RoutingDecision.of(RoutingReason.UNASSIGNED)); // 9/10 = 90% exactly
        assertThat(m.committeeCommitRatio()).isEqualTo(0.90);
        assertThat(m.meetsPhase4ExitCriterion()).isFalse(); // strict >
    }

    @Test
    void emptyMetricsAreZero() {
        SoakMetrics m = new SoakMetrics();
        assertThat(m.committeeCommitRatio()).isZero();
        assertThat(m.meetsPhase4ExitCriterion()).isFalse();
    }
}
