/**
 * Phase 4 server-fallback-only execution + cross-region router (Task 8), Minecraft-free.
 *
 * <p>By Phase 4 the server executes ONLY what a committee cannot: unassigned regions, cross-region
 * actions, disputed proposals, and regions whose committee collapsed. Everything else is
 * committee-committed (Task 7). {@link dev.nodera.fallback.CrossRegionRouter} classifies each action
 * into the committee lane or the {@link dev.nodera.fallback.FallbackExecutor} server lane;
 * {@link dev.nodera.fallback.SoakMetrics} measures the committee-commit ratio — the Phase 4 exit
 * criterion is a sustained &gt;90% of validated-action batches committing without server
 * re-execution.
 *
 * <p>Runs entirely over the {@link dev.nodera.coordinator.MutableWorldView} seam and the
 * {@link dev.nodera.simulation.engine.FlatWorldRegionEngine} — no NeoForge (the actual vanilla
 * cross-region execution on the real {@code ServerLevel} is the mod side's job; here cross-region is
 * classified, routed, and counted).
 *
 * @Thread-context the router is single-thread-confined (server main thread); see each type.
 */
package dev.nodera.fallback;
