/**
 * Phase 1 shadow validation (Task 5), Minecraft-free.
 *
 * <p>The server plays vanilla and stays fully authoritative; captured player actions are streamed to
 * clients whose {@link dev.nodera.shadow.WorkerRuntime}s recompute the region with the same
 * deterministic {@link dev.nodera.simulation.RegionEngine} and report only a
 * {@link dev.nodera.core.state.StateRoot}. The server's {@link dev.nodera.shadow.ServerRecompute}
 * produces the reference root and {@link dev.nodera.shadow.DivergenceTracker} compares. <b>Nothing a
 * client computes is ever committed</b> — this phase exists solely to prove (or break) determinism
 * against real play with zero gameplay risk.
 *
 * <p>Everything here is pure Java over {@code core} + {@code simulation}: no clocks in the hashed
 * path, no NeoForge, no IO. The NeoForge capture/stream shim (Task 5 mod side) is a thin adapter
 * that feeds {@link dev.nodera.shadow.ShadowCoordinator}; the determinism pipeline itself is proven
 * headlessly by {@code ShadowValidationIT}.
 *
 * @Thread-context see each type; the coordinator is single-thread-confined, the worker runtime is
 *                 thread-safe.
 */
package dev.nodera.shadow;
