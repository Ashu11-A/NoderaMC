package dev.nodera.coordinator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The multi-factor blend (Task 22; L-36). The properties that matter: determinism (same inputs ⇒
 * same score on any JVM), slash-to-0 on a correctness of 0, the floor gate, and that no single
 * factor dominates — a high-correctness/poor-connectivity node must score below its inverse under a
 * config that weights connectivity.
 *
 * <p>Thread-context: single test thread.
 */
final class ReliabilityScorerTest {

    @Test
    void sameInputsProduceTheSameScore() {
        ReliabilityScorer scorer = ReliabilityScorer.defaultScorer();
        ReliabilityFactors f = ReliabilityFactors.ofFractions(0.9, 0.8, 0.7, 0.6, 0.5);

        // Repeated scoring is pure: no hidden state, no drift.
        int a = scorer.scoreBps(f);
        int b = scorer.scoreBps(f);
        int c = scorer.scoreBps(f);
        assertThat(a).isEqualTo(b).isEqualTo(c);

        // The blend of (0.9,0.8,0.7,0.6,0.5) under default weights (4000,2500,2000,1000,500):
        // (4000·9000 + 2500·8000 + 2000·7000 + 1000·6000 + 500·5000)/10000 = 7850 bps.
        assertThat(a).isEqualTo(7850);
        assertThat(scorer.score(f)).isCloseTo(0.785, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void slashToZeroWhenCorrectnessIsZero() {
        ReliabilityScorer scorer = ReliabilityScorer.defaultScorer();
        // A peer that otherwise looks great but equivocated (correctness 0) scores 0.
        ReliabilityFactors slashed = ReliabilityFactors.ofFractions(0.0, 1.0, 1.0, 1.0, 1.0);
        assertThat(scorer.scoreBps(slashed)).isZero();
        assertThat(scorer.eligibleForAssignment(slashed)).isFalse();
    }

    @Test
    void theAssignmentFloorGatesEligibility() {
        ReliabilityScorer scorer = ReliabilityScorer.defaultScorer();   // floor 9500 bps
        ReliabilityFactors borderline = ReliabilityFactors.ofFractions(0.95, 0.95, 0.95, 0.95, 0.95);
        // 0.95 across the board → 9500 bps, exactly at the floor.
        assertThat(scorer.scoreBps(borderline)).isEqualTo(9500);
        assertThat(scorer.eligibleForAssignment(borderline)).isTrue();

        ReliabilityFactors below = ReliabilityFactors.ofFractions(0.94, 0.95, 0.95, 0.95, 0.95);
        assertThat(scorer.eligibleForAssignment(below)).isFalse();
    }

    @Test
    void noSingleFactorDominatesAcrossWeightConfigs() {
        // A node with great proposal-match but poor connectivity/uptime…
        ReliabilityFactors highCorrectLowConn = ReliabilityFactors.ofFractions(1.0, 0.2, 0.2, 0.5, 0.5);
        // …and the inverse: poor correctness, great connectivity/uptime.
        ReliabilityFactors lowCorrectHighConn = ReliabilityFactors.ofFractions(0.5, 1.0, 1.0, 0.5, 0.5);

        // Under a connectivity-heavy config, the inverse must score higher — the blend is not a
        // correctness-only EMA any more (the whole point of L-36).
        ReliabilityConfig connectivityHeavy = new ReliabilityConfig(2000, 4000, 2000, 1000, 1000, 5000);
        ReliabilityScorer scorer = new ReliabilityScorer(connectivityHeavy, 0);
        assertThat(scorer.scoreBps(lowCorrectHighConn))
                .isGreaterThan(scorer.scoreBps(highCorrectLowConn));

        // Under a correctness-heavy config, the order flips — proving the weights actually bind.
        ReliabilityConfig correctnessHeavy = new ReliabilityConfig(8000, 500, 500, 500, 500, 5000);
        ReliabilityScorer correctnessScorer = new ReliabilityScorer(correctnessHeavy, 0);
        assertThat(correctnessScorer.scoreBps(highCorrectLowConn))
                .isGreaterThan(correctnessScorer.scoreBps(lowCorrectHighConn));
    }

    @Test
    void offlineDecayMovesSignalsTowardTheTarget() {
        ReliabilityScorer scorer = ReliabilityScorer.defaultScorer();   // decay target 5000 bps
        ReliabilityFactors strong = ReliabilityFactors.perfect();       // all 10000
        ReliabilityFactors decayed = scorer.decayed(strong, 1000);
        for (int i = 0; i < ReliabilityFactors.SIGNAL_COUNT; i++) {
            assertThat(decayed.signal(i)).isEqualTo(9000);
        }
        // Repeated decay converges to the target (5000), never below it.
        ReliabilityFactors current = strong;
        for (int i = 0; i < 100; i++) {
            current = scorer.decayed(current, 1000);
        }
        for (int i = 0; i < ReliabilityFactors.SIGNAL_COUNT; i++) {
            assertThat(current.signal(i)).isEqualTo(5000);
        }
    }

    @Test
    void factorsRoundTripCanonically() {
        ReliabilityFactors original = ReliabilityFactors.ofFractions(0.91, 0.82, 0.73, 0.64, 0.55);
        dev.nodera.core.crypto.CanonicalWriter w = new dev.nodera.core.crypto.CanonicalWriter();
        original.encode(w);
        ReliabilityFactors decoded = ReliabilityFactors.decode(
                new dev.nodera.core.crypto.CanonicalReader(w.toByteArray()));
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void configRejectsWeightsThatDoNotSumToTheScale() {
        assertThatThrownBy(() -> new ReliabilityConfig(4000, 2500, 2000, 1000, 999, 5000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sum to");
    }

    @Test
    void rejectsOutOfRangeSignals() {
        assertThatThrownBy(() -> new ReliabilityFactors(-1, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReliabilityFactors(0, 0, 0, 0, 10_001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
