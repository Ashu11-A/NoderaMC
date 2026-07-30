package dev.nodera.shadow;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.rules.FlatWorldRules;

/**
 * The session-fixed deterministic parameters shared by every shadow participant (Task 5): the world
 * seed and the rule-set version + registry fingerprint the committee runs under. These never change
 * within a session, so a {@link RegionExecutionContext} for any batch is fully derived from the
 * batch plus this record — guaranteeing every replica builds the identical context.
 *
 * @param worldSeed           the world seed mixed into every action RNG seed.
 * @param rulesVersion        the rule-set version; the engine refuses a mismatched context.
 * @param registryFingerprint the palette fingerprint; the engine refuses a mismatched context.
 * @Thread-context immutable, any thread.
 */
public record SessionParams(long worldSeed, int rulesVersion, long registryFingerprint) {

    /** The flat-world MVP parameters (Task 3 {@link FlatWorldRules}) at the given seed. */
    public static SessionParams flatWorld(long worldSeed) {
        return new SessionParams(worldSeed, FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
    }

    /**
     * Derive the deterministic execution context for {@code batch}.
     *
     * @param batch the batch to execute; supplies region, epoch, base version, and tick range.
     * @return the fully-resolved {@link RegionExecutionContext}.
     */
    public RegionExecutionContext contextFor(ActionBatch batch) {
        if (batch == null) {
            throw new IllegalArgumentException("batch must not be null");
        }
        return new RegionExecutionContext(
                batch.region(), batch.epoch(), batch.baseVersion(),
                batch.tickFrom(), batch.tickTo(), worldSeed, rulesVersion, registryFingerprint);
    }
}
