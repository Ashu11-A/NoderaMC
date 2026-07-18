package dev.nodera.fallback;

import dev.nodera.core.state.StateRoot;
import dev.nodera.coordinator.WorldMutationApplier;
import dev.nodera.simulation.RegionEngine;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;

/**
 * The server's fallback executor (Task 8, Phase 4): runs a single-region batch server-side and
 * commits it through the coordinator's {@link WorldMutationApplier}. Used for regions a committee
 * cannot own — unassigned, disputed, or committee-collapsed. Cross-region actions are routed here by
 * the {@link CrossRegionRouter} but executed by the server's native vanilla path (the region engine
 * refuses cross-region actions by contract); this executor covers the single-region server lane.
 *
 * @Thread-context server main thread (single writer); not thread-safe.
 */
public final class FallbackExecutor {

    private final RegionEngine engine;
    private final WorldMutationApplier applier;

    public FallbackExecutor(RegionEngine engine, WorldMutationApplier applier) {
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        if (applier == null) {
            throw new IllegalArgumentException("applier must not be null");
        }
        this.engine = engine;
        this.applier = applier;
    }

    /**
     * Execute a single-region batch server-side and commit it.
     *
     * @param request the execution inputs (base snapshot + batch).
     * @return the server root and the applier outcome.
     */
    public FallbackResult execute(RegionExecutionRequest request) {
        RegionExecutionResult result = engine.execute(request);
        WorldMutationApplier.ApplyResult applied = applier.apply(result.delta());
        return new FallbackResult(result.resultingRoot(), applied);
    }

    /**
     * @param root        the server-computed root.
     * @param applyResult the world-write outcome.
     */
    public record FallbackResult(StateRoot root, WorldMutationApplier.ApplyResult applyResult) {
        /** @return {@code true} if the fallback commit applied cleanly. */
        public boolean committed() {
            return applyResult.committed();
        }
    }
}
