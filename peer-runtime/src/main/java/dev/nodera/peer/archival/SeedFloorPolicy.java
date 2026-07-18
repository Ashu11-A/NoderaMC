package dev.nodera.peer.archival;

/**
 * The ≥25%-seed floor and &lt;5%-per-peer cap (Task 21, spec rules 1 and 3), expressed as pure
 * functions of the replication factor {@code R} and the network size {@code N}.
 *
 * <h2>The two thresholds</h2>
 *
 * <ul>
 *   <li><b>Floor</b> = {@code min(25%, R/N)}. At small N this is the spec's flat 25% (it equals
 *       {@code R/N} exactly at N = 20, R = 5); as players join it decays to {@code R/N}, the
 *       average share that sustains R full copies. A peer below it is a free-rider.</li>
 *   <li><b>Cap</b> = {@code max(5%, 2·R/N)}. A flat 5% cap is arithmetically impossible until the
 *       capped shares can fund R copies, so the cap is dynamic: ~50% at N = 20, tightening to the
 *       spec's 5% once N ≥ 2R/5% = 200. The {@code 2·} headroom factor is config.</li>
 * </ul>
 *
 * <p>The floor is always strictly below the cap ({@code R/N < 2·R/N}), so the two can never
 * conflict: a peer cannot be simultaneously below-floor and above-cap.
 *
 * <h2>The {@code FULL_ARCHIVE} host is exempt from the cap</h2>
 *
 * <p>The host holds everything (rule 0: it IS the redundant physical backup), so a 100% share is
 * correct for it and wrong for anyone else. The caller passes an {@code exempt} flag for the host.
 *
 * <h2>Why double is safe here</h2>
 *
 * <p>These are <b>not</b> hashed/wire values — they are policy thresholds evaluated locally. IEEE-754
 * double arithmetic is bit-identical across JVMs, so the classification is deterministic. Anything
 * that crosses the wire as a reliability figure is quantised to basis points elsewhere (Task 20's
 * tracker), never emitted as a float.
 *
 * <p>Thread-context: stateless static helpers; safe for any thread.
 */
public final class SeedFloorPolicy {

    /** The spec's flat seed floor before the R/N decay takes over. */
    public static final double FLAT_FLOOR = 0.25;

    /** The spec's flat per-peer cap before the network is large enough for it to bind. */
    public static final double FLAT_CAP = 0.05;

    /** Headroom over the average share; the cap is {@code 2·R/N} until the flat 5% binds. */
    public static final double CAP_HEADROOM = 2.0;

    private SeedFloorPolicy() {}

    /**
     * @param replicationFactor R.
     * @param networkSize       N.
     * @return the floor fraction {@code min(0.25, R/N)}.
     * @throws IllegalArgumentException if an argument is not positive.
     * @Thread-context any thread.
     */
    public static double floorFraction(int replicationFactor, int networkSize) {
        requirePositive(replicationFactor, networkSize);
        return Math.min(FLAT_FLOOR, (double) replicationFactor / networkSize);
    }

    /**
     * @param replicationFactor R.
     * @param networkSize       N.
     * @return the cap fraction {@code max(0.05, 2·R/N)}.
     * @throws IllegalArgumentException if an argument is not positive.
     * @Thread-context any thread.
     */
    public static double capFraction(int replicationFactor, int networkSize) {
        requirePositive(replicationFactor, networkSize);
        return Math.max(FLAT_CAP, CAP_HEADROOM * replicationFactor / networkSize);
    }

    /**
     * @param replicationFactor R.
     * @param networkSize       N.
     * @return the floor as basis points (0..10000), for wire/reporting without a float.
     * @Thread-context any thread.
     */
    public static int floorBps(int replicationFactor, int networkSize) {
        return toBps(floorFraction(replicationFactor, networkSize));
    }

    /**
     * @param replicationFactor R.
     * @param networkSize       N.
     * @return the cap as basis points (0..10000).
     * @Thread-context any thread.
     */
    public static int capBps(int replicationFactor, int networkSize) {
        return toBps(capFraction(replicationFactor, networkSize));
    }

    /**
     * Classify one peer's share of a world.
     *
     * @param heldPieces    how many of the world's distinct pieces this peer holds (≥0).
     * @param totalPieces   the world's distinct piece count (≥1).
     * @param replicationFactor R.
     * @param networkSize   N.
     * @param exempt        {@code true} for the {@code FULL_ARCHIVE} host (never flagged).
     * @return the classification.
     * @throws IllegalArgumentException if the counts are out of range.
     * @Thread-context any thread.
     */
    public static Classification classify(
            int heldPieces, int totalPieces, int replicationFactor, int networkSize, boolean exempt) {
        if (heldPieces < 0) {
            throw new IllegalArgumentException("heldPieces must be non-negative: " + heldPieces);
        }
        if (totalPieces <= 0) {
            throw new IllegalArgumentException("totalPieces must be positive: " + totalPieces);
        }
        requirePositive(replicationFactor, networkSize);
        if (exempt) {
            return Classification.EXEMPT;
        }
        double share = (double) heldPieces / totalPieces;
        double floor = floorFraction(replicationFactor, networkSize);
        double cap = capFraction(replicationFactor, networkSize);
        if (share < floor) {
            return Classification.BELOW_FLOOR;
        }
        if (share > cap) {
            return Classification.ABOVE_CAP;
        }
        return Classification.WITHIN;
    }

    /** A peer's standing against the floor/cap. */
    public enum Classification {
        /** The {@code FULL_ARCHIVE} host — holds everything, correctly; never flagged. */
        EXEMPT,
        /** Below the seed floor — a free-rider; deprioritised (Task 22 reliability penalty). */
        BELOW_FLOOR,
        /** Within [floor, cap] — healthy. */
        WITHIN,
        /** Above the cap — over-concentrated; redistribute excess via the repair service. */
        ABOVE_CAP
    }

    private static void requirePositive(int replicationFactor, int networkSize) {
        if (replicationFactor <= 0) {
            throw new IllegalArgumentException(
                    "replicationFactor must be positive: " + replicationFactor);
        }
        if (networkSize <= 0) {
            throw new IllegalArgumentException("networkSize must be positive: " + networkSize);
        }
    }

    private static int toBps(double fraction) {
        return (int) Math.max(0, Math.min(10_000, Math.round(fraction * 10_000)));
    }
}
