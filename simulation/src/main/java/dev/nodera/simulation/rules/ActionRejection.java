package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionEnvelope;

/**
 * A deterministic rejection of one {@link ActionEnvelope} by a {@link RuleSet} (Task 3). Rejections
 * are observed by the engine, counted in
 * {@link dev.nodera.simulation.RegionExecutionResult.ExecutionStats#actionsRejected()}, and listed
 * in {@link dev.nodera.simulation.RegionExecutionResult.ExecutionStats#rejections()} in batch
 * order. They are <b>excluded from every consensus hash</b> — they are a deterministic consequence
 * of the inputs, so all replicas compute the same list, but only the resulting state is truth.
 *
 * @param envelope the rejected action (identity + signature retained for diagnostics).
 * @param reason   the deterministic rejection reason.
 * @Thread-context immutable, any thread.
 */
public record ActionRejection(ActionEnvelope envelope, Reason reason) {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code envelope} or {@code reason} is null.
     * @Thread-context deterministic; safe from any thread.
     */
    public ActionRejection {
        if (envelope == null) {
            throw new IllegalArgumentException("envelope must not be null");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
    }

    /**
     * Deterministic rejection taxonomy. The set is append-only: new reasons are added at the end
     * (never renumbered) so cross-version peers agree on the meaning of each constant.
     *
     * @Thread-context immutable, any thread.
     */
    public enum Reason {
        /** The action targets a block state id outside the rule set's whitelist. */
        ILLEGAL_BLOCK,
        /** The action targets a position outside this region's owned bounds. */
        OUT_OF_REGION,
        /**
         * The action's {@code expectedPreviousStateId} does not match the pre-batch state. Not
         * raised by the MVP pre-apply validator (there is no client-supplied expected id); reserved
         * for the commit applier (Task 6).
         */
        BAD_PREVIOUS_STATE,
        /** The action's target position is outside the reachable height/extent envelope. */
        OUT_OF_REACH,
        /** The action is structurally invalid (bad face, missing fields, etc.). */
        MALFORMED,
        /** The rule set does not handle this action kind (e.g. entity actions under the block-only
         *  MVP rules — the entity lane ships in its own rule set). */
        UNSUPPORTED_ACTION
    }
}
