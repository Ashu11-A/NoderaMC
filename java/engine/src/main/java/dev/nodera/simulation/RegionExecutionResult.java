package dev.nodera.simulation;

import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.rules.ActionRejection;

import java.util.List;

/**
 * The outcome of one {@link RegionEngine#execute(RegionExecutionRequest)} call (Task 3).
 *
 * <p><b>Consensus shape.</b> Only {@link #delta()} and {@link #resultingRoot()} are agreed upon.
 * {@link #stats()} (counts, timing, and the per-action rejection list) is a deterministic
 * consequence of the inputs but is <b>EXCLUDED from every consensus hash</b>: the root is SHA-256
 * of the post-state {@link dev.nodera.core.state.RegionSnapshot} and the delta carries only block
 * mutations. This keeps agreement cheap and keeps non-state observations (timing, rejection
 * diagnostics) out of the truth set.
 *
 * @param delta         the canonical, sorted block-mutation delta; transport, not truth.
 * @param resultingRoot SHA-256 of the post-state snapshot; truth.
 * @param stats         non-hashed execution metadata, including the ordered rejection list.
 * @Thread-context immutable, any thread.
 */
public record RegionExecutionResult(
        RegionDelta delta,
        StateRoot resultingRoot,
        ExecutionStats stats
) {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any argument is null.
     * @Thread-context deterministic; safe from any thread.
     */
    public RegionExecutionResult {
        if (delta == null) {
            throw new IllegalArgumentException("delta must not be null");
        }
        if (resultingRoot == null) {
            throw new IllegalArgumentException("resultingRoot must not be null");
        }
        if (stats == null) {
            throw new IllegalArgumentException("stats must not be null");
        }
    }

    /**
     * Non-hashed execution metadata. Counts, timing, and the rejection list are deterministic
     * consequences of the inputs but carry no consensus weight: only
     * {@link RegionExecutionResult#delta()} and {@link RegionExecutionResult#resultingRoot()} are
     * agreed upon. The {@link #rejections} list rides along here (rather than as a top-level result
     * field) so {@link RegionExecutionResult} keeps its three-field consensus shape and the
     * rejection-determinism tests can observe it.
     *
     * @param actionsApplied  actions that passed validation and mutated state.
     * @param actionsRejected actions dropped by the {@link dev.nodera.simulation.rules.RuleSet}.
     * @param engineNanos     wall-clock cost; <b>always zero inside the engine</b> because reading
     *                        a clock is forbidden (Task 0 §6). Callers that need timing measure it
     *                        around {@link RegionEngine#execute(RegionExecutionRequest)}.
     * @param rejections      the per-action rejection list, in batch order; deterministic across
     *                        replicas. Excluded from every consensus hash.
     * @Thread-context immutable, any thread.
     */
    public record ExecutionStats(
            int actionsApplied,
            int actionsRejected,
            long engineNanos,
            List<ActionRejection> rejections
    ) {

        /** Sentinel "no rejections, no time" stat block, used by tests and pure helpers. */
        public static final ExecutionStats EMPTY = new ExecutionStats(0, 0, 0L, List.of());

        /**
         * Compact constructor. Nullifies an absent rejection list to the empty list so this record
         * is never null-bearing.
         *
         * @Thread-context deterministic; safe from any thread.
         */
        public ExecutionStats {
            if (rejections == null) {
                rejections = List.of();
            }
        }
    }
}
