package dev.nodera.coordinator;

import java.util.Objects;

/**
 * Blends a node's {@link ReliabilityFactors} into the single scalar that drives placement, gateway
 * election, and lag handoff (Task 22; retires L-36).
 *
 * <h2>Pure integer arithmetic</h2>
 *
 * <p>The score is a weighted average computed entirely in basis points:
 * {@code score = Σ(weight_i · factor_i) / Σ(weight_i)}. Because every input is an integer and the
 * weights are normalised to sum to {@link ReliabilityFactors#BPS_SCALE}, the division is exact and
 * the result is bit-identical on every JVM — the determinism property
 * {@code ReliabilityPropertyTest} pins. A {@code double} reappears only as a display convenience.
 *
 * <h2>Slash and floor</h2>
 *
 * <p>Equivocation slashes the score to 0 outright (Plan §10), regardless of the other factors — a
 * peer that lies about state cannot buy reliability with good connectivity. Assignment requires the
 * score at or above the configured floor.
 *
 * <h2>Offline decay</h2>
 *
 * <p>{@link #decayed(ReliabilityFactors, int)} moves every signal toward the config's decay target
 * (Plan §10: 0.5). A peer that goes offline drifts toward neutrality, not toward 0 — being offline
 * is not the same as being dishonest.
 *
 * <p>Thread-context: stateless; safe from any thread.
 */
public final class ReliabilityScorer {

    private final ReliabilityConfig config;
    private final int assignmentFloorBps;

    /**
     * @param config              the weights + decay target.
     * @param assignmentFloorBps  minimum score (basis points) for assignment eligibility.
     * @throws IllegalArgumentException if an argument is null or the floor is out of range.
     * @Thread-context any thread (construction only).
     */
    public ReliabilityScorer(ReliabilityConfig config, int assignmentFloorBps) {
        this.config = Objects.requireNonNull(config, "config");
        if (assignmentFloorBps < 0 || assignmentFloorBps > ReliabilityFactors.BPS_SCALE) {
            throw new IllegalArgumentException(
                    "assignmentFloorBps must be in [0," + ReliabilityFactors.BPS_SCALE + "]: "
                            + assignmentFloorBps);
        }
        this.assignmentFloorBps = assignmentFloorBps;
    }

    /** Scorer with the default config and the Plan §10 assignment floor (0.95 → 9500 bps). */
    public static ReliabilityScorer defaultScorer() {
        return new ReliabilityScorer(ReliabilityConfig.defaultConfig(), 9500);
    }

    /**
     * The blended score, in basis points (0..10000). Pure integer arithmetic.
     *
     * @param factors the node's signals.
     * @return the weighted score; 0 if the node was slashed (correctness 0 ⇒ score 0).
     * @throws IllegalArgumentException if {@code factors} is null.
     * @Thread-context any thread.
     */
    public int scoreBps(ReliabilityFactors factors) {
        Objects.requireNonNull(factors, "factors");
        // Slash: a correctness of 0 means the node equivocated; the whole score is 0.
        if (factors.correctness() == 0) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < ReliabilityFactors.SIGNAL_COUNT; i++) {
            sum += (long) config.weight(i) * factors.signal(i);
        }
        // Weights sum to BPS_SCALE, so this is the exact weighted average in basis points.
        return (int) (sum / ReliabilityFactors.BPS_SCALE);
    }

    /**
     * The score as a {@code [0,1]} double — display convenience only; the load-bearing value is
     * {@link #scoreBps(ReliabilityFactors)}.
     */
    public double score(ReliabilityFactors factors) {
        return (double) scoreBps(factors) / ReliabilityFactors.BPS_SCALE;
    }

    /**
     * @param factors the node's signals.
     * @return {@code true} if the blended score meets the assignment floor.
     * @Thread-context any thread.
     */
    public boolean eligibleForAssignment(ReliabilityFactors factors) {
        return scoreBps(factors) >= assignmentFloorBps;
    }

    /** @return the configured assignment floor (basis points). */
    public int assignmentFloorBps() {
        return assignmentFloorBps;
    }

    /** @return the config in use. */
    public ReliabilityConfig config() {
        return config;
    }

    /**
     * Move every signal one step toward the decay target. Used when a node is observed offline.
     *
     * @param factors  the current signals.
     * @param stepBps  how far to move (basis points); must be in {@code [0, 10000]}.
     * @return the decayed signals.
     * @throws IllegalArgumentException if an argument is null or {@code stepBps} is out of range.
     * @Thread-context any thread.
     */
    public ReliabilityFactors decayed(ReliabilityFactors factors, int stepBps) {
        Objects.requireNonNull(factors, "factors");
        if (stepBps < 0 || stepBps > ReliabilityFactors.BPS_SCALE) {
            throw new IllegalArgumentException("stepBps out of range: " + stepBps);
        }
        int target = config.decayTargetBps();
        ReliabilityFactors current = factors;
        for (int i = 0; i < ReliabilityFactors.SIGNAL_COUNT; i++) {
            current = current.withSignal(i, step(current.signal(i), target, stepBps));
        }
        return current;
    }

    private static int step(int value, int target, int stepBps) {
        if (value == target) {
            return value;
        }
        if (value < target) {
            return Math.min(target, value + stepBps);
        }
        return Math.max(target, value - stepBps);
    }
}
