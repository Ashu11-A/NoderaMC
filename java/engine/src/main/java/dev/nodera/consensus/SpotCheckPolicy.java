package dev.nodera.consensus;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.StableHash;
import dev.nodera.core.region.RegionId;

/**
 * Adaptive spot-check sampling (ledger L-22; Plan §6 Phase 3). The server need not re-execute
 * every committed batch itself once a committee has proven itself — instead it re-executes one in
 * every {@code N} batches, where {@code N} widens as the committee's minimum reliability rises.
 *
 * <p>The batch to re-check is selected deterministically:
 * <pre>
 *     StableHash.of(regionHashLong, version, serverSecretLong) mod N == 0
 * </pre>
 * so every honest replica agrees on which batches the server audited, and a byzantine committee
 * cannot predict at submission time which batches will be checked (the {@code serverSecret} is
 * held by the server and rotated). No {@link Math#random} is used — the choice is a pure function
 * of the inputs.
 *
 * <p>Divisor thresholds:
 * <ul>
 *   <li>{@code committeeReliability >= 0.99} → {@code N = 64} (~1.6%, steady-state target for
 *       proven committees).</li>
 *   <li>{@code committeeReliability <}{@code #ASSIGNMENT_FLOOR 0.95}
 *       ({@link NoderaConstants#RELIABILITY_ASSIGNMENT_FLOOR}) → {@code N = 4} (25%): fresh or
 *       suspect committees are audited heavily.</li>
 *   <li>otherwise → {@code N = 8} (12.5%) default.</li>
 * </ul>
 *
 * <p>Thread-context: pure functions with no mutable state; safe from any thread.
 */
public final class SpotCheckPolicy {

    /** Reliability at and above which sampling widens to N=64. */
    public static final double HIGH_RELIABILITY_THRESHOLD = 0.99;

    private SpotCheckPolicy() {}

    /**
     * @param committeeReliability the committee's minimum reliability score in {@code [0, 1]}.
     * @return the sampling divisor {@code N}: 64 for highly-trusted committees
     *         ({@code >= }{@value #HIGH_RELIABILITY_THRESHOLD}), 4 for fresh/suspect committees
     *         (below {@link NoderaConstants#RELIABILITY_ASSIGNMENT_FLOOR}), 8 otherwise.
     */
    public static int divisorFor(double committeeReliability) {
        if (committeeReliability >= HIGH_RELIABILITY_THRESHOLD) {
            return 64;
        }
        if (committeeReliability < NoderaConstants.RELIABILITY_ASSIGNMENT_FLOOR) {
            return 4;
        }
        return 8;
    }

    /**
     * Whether the server should re-execute (spot-check) the given batch.
     *
     * <p>{@code regionHashLong} is computed canonically as
     * {@code StableHash.of(StableHash.of(dimension.toString()), regionX, regionZ)}; combined with
     * the batch {@code version} and the server's {@code serverSecret} via
     * {@link StableHash#of(long...)}. The modulo is taken with {@link Math#floorMod(long, long)}
     * so the result is non-negative and uniformly distributed regardless of the hash's sign.
     *
     * @param region               the region whose batch is in question; not null.
     * @param version              the batch's base snapshot version.
     * @param serverSecret         the server's audit secret (rotated, never published to the
     *                             committee).
     * @param committeeReliability the committee's minimum reliability; selects {@code N}.
     * @return true iff the canonical hash of {@code (region, version, serverSecret)} is divisible
     *         by {@code N = divisorFor(committeeReliability)}.
     */
    public static boolean shouldCheck(
            RegionId region, long version, long serverSecret, double committeeReliability) {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        long regionHashLong = StableHash.of(
                StableHash.of(region.dimension().toString()),
                region.regionX(),
                region.regionZ());
        long h = StableHash.of(regionHashLong, version, serverSecret);
        int n = divisorFor(committeeReliability);
        return Math.floorMod(h, (long) n) == 0L;
    }
}
