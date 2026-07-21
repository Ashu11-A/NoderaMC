package dev.nodera.simulation;

import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;

/**
 * Immutable, fully-determining inputs for one region execution (Task 3). Together with the
 * {@link dev.nodera.core.state.RegionSnapshot} and {@link dev.nodera.core.action.ActionBatch} in
 * the {@link RegionExecutionRequest}, this record fixes the outcome bit-for-bit: same context +
 * snapshot + batch on any JVM ⇒ same {@link dev.nodera.core.state.StateRoot} and same canonical
 * {@link dev.nodera.core.state.RegionDelta} bytes.
 *
 * <p><b>No explicit deterministic seed field.</b> The design draft carried a single
 * {@code deterministicSeed}; this record replaces it with the raw determinism sources
 * ({@code worldSeed}, {@code region}, {@code tickFrom}) and lets
 * {@link DeterministicRandom#seedFor(RegionExecutionContext, long, long)} derive a per-action seed
 * from them via {@link dev.nodera.core.crypto.StableHash}. Keeping the raw sources (not a pre-mixed
 * seed) means every replica derives the identical seed independently — there is no place for a
 * mis-mixed seed to hide.
 *
 * @param region              the region being executed.
 * @param epoch               the region epoch the committee is running under.
 * @param baseVersion         the snapshot version {@code execute} starts from.
 * @param tickFrom            inclusive first tick of the batch range.
 * @param tickTo              inclusive last tick of the batch range (post-state tick).
 * @param worldSeed           the world seed; mixed into every action RNG seed.
 * @param rulesVersion        the rule-set version the caller expects; the engine refuses mismatches.
 * @param registryFingerprint fingerprint of the block registry the caller expects; the engine
 *                            refuses mismatches so two builds hashing different palettes never
 *                            validate each other.
 * @Thread-context immutable, any thread.
 */
public record RegionExecutionContext(
        RegionId region,
        RegionEpoch epoch,
        SnapshotVersion baseVersion,
        long tickFrom,
        long tickTo,
        long worldSeed,
        int rulesVersion,
        long registryFingerprint
) {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code region}, {@code epoch}, or {@code baseVersion} is null.
     * @Thread-context deterministic; safe from any thread.
     */
    public RegionExecutionContext {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        if (epoch == null) {
            throw new IllegalArgumentException("epoch must not be null");
        }
        if (baseVersion == null) {
            throw new IllegalArgumentException("baseVersion must not be null");
        }
    }
}
