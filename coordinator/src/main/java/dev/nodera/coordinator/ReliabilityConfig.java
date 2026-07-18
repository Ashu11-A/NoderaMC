package dev.nodera.coordinator;

import dev.nodera.core.NoderaConstants;

/**
 * The weights and decay target for the multi-factor reliability blend (Task 22; L-36).
 *
 * <p>Weights are integers in basis points and MUST sum to {@link ReliabilityFactors#BPS_SCALE}
 * (10000). Storing them as integers (not doubles) keeps the blend pure integer arithmetic, so the
 * resulting score is bit-identical across JVMs — a determinism property the placement and gateway
 * elections depend on.
 *
 * <p>The five weights correspond, in order, to {@link ReliabilityFactors}' signals:
 * correctness, connectivity, uptime, availability, worldsSeeded.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param correctness   weight on the proposal-outcome EMA.
 * @param connectivity  weight on reachability/frame-loss.
 * @param uptime        weight on the online-fraction EMA.
 * @param availability  weight on heartbeat regularity.
 * @param worldsSeeded  weight on the seed-share.
 * @param decayTargetBps the basis-point value offline factors decay toward (Plan §10: 0.5 → 5000).
 */
public record ReliabilityConfig(
        int correctness,
        int connectivity,
        int uptime,
        int availability,
        int worldsSeeded,
        int decayTargetBps
) {

    /** Default weights (Plan §10): correctness dominates, then connectivity/uptime/availability/seed. */
    public static final ReliabilityConfig DEFAULT = new ReliabilityConfig(
            4000, 2500, 2000, 1000, 500, toBps(NoderaConstants.RELIABILITY_OFFLINE_DECAY_TARGET));

    /** @return the default config. */
    public static ReliabilityConfig defaultConfig() {
        return DEFAULT;
    }

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any weight is negative, the weights do not sum to 10000,
     *                                  or the decay target is outside {@code [0, 10000]}.
     */
    public ReliabilityConfig {
        int sum = correctness + connectivity + uptime + availability + worldsSeeded;
        if (correctness < 0 || connectivity < 0 || uptime < 0 || availability < 0 || worldsSeeded < 0) {
            throw new IllegalArgumentException("weights must be non-negative");
        }
        if (sum != ReliabilityFactors.BPS_SCALE) {
            throw new IllegalArgumentException(
                    "weights must sum to " + ReliabilityFactors.BPS_SCALE + ", got " + sum);
        }
        if (decayTargetBps < 0 || decayTargetBps > ReliabilityFactors.BPS_SCALE) {
            throw new IllegalArgumentException(
                    "decayTargetBps must be in [0," + ReliabilityFactors.BPS_SCALE + "]: " + decayTargetBps);
        }
    }

    /** @return the weight at the canonical signal ordinal. */
    public int weight(int ordinal) {
        return switch (ordinal) {
            case 0 -> correctness;
            case 1 -> connectivity;
            case 2 -> uptime;
            case 3 -> availability;
            case 4 -> worldsSeeded;
            default -> throw new IllegalArgumentException("unknown signal ordinal " + ordinal);
        };
    }

    private static int toBps(double fraction) {
        return (int) Math.max(0, Math.min(ReliabilityFactors.BPS_SCALE, Math.round(fraction * ReliabilityFactors.BPS_SCALE)));
    }
}
