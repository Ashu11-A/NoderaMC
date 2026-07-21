package dev.nodera.shadow;

import dev.nodera.simulation.RegionEngine;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The client-side worker executor (Task 5, from the locked design). Runs region batches on a
 * virtual-thread-per-task executor so a recompute never blocks the client tick or render thread;
 * results are returned as a {@link CompletableFuture} and posted back via transport from the worker
 * thread.
 *
 * <p><b>Lifecycle.</b> {@link WorkerState#INACTIVE} → {@link #activate()} → {@link WorkerState#ACTIVE}
 * → {@link #close()} → {@link WorkerState#STOPPED}. {@link #execute} is refused unless the runtime is
 * ACTIVE, so a not-yet-activated or already-stopped worker fails fast rather than silently computing.
 *
 * <p><b>Purity.</b> The runtime adds no nondeterminism: it only schedules
 * {@link RegionEngine#execute(RegionExecutionRequest)}, which is itself a pure function of its
 * arguments. Two workers fed the same request produce byte-identical roots.
 *
 * @Thread-context thread-safe. {@link #execute} may be called from any thread; the engine runs on a
 *                 fresh virtual thread per call and holds no shared mutable state.
 */
public final class WorkerRuntime implements AutoCloseable {

    private final RegionEngine engine;
    private final ExecutorService executor;
    private final AtomicReference<WorkerState> state = new AtomicReference<>(WorkerState.INACTIVE);

    /**
     * @param engine the deterministic engine every request is executed against; must not be null.
     * @throws IllegalArgumentException if {@code engine} is null.
     */
    public WorkerRuntime(RegionEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        this.engine = engine;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /** @return the current lifecycle state (safe from any thread). */
    public WorkerState state() {
        return state.get();
    }

    /**
     * Flip INACTIVE → ACTIVE. Idempotent while ACTIVE; a no-op once STOPPED (a closed worker never
     * reactivates).
     */
    public void activate() {
        state.compareAndSet(WorkerState.INACTIVE, WorkerState.ACTIVE);
    }

    /**
     * Schedule one region batch for deterministic execution.
     *
     * @param request the fully-resolved execution inputs.
     * @return a future completing with the engine result, or failing with {@link IllegalStateException}
     *         if the runtime is not ACTIVE (checked eagerly, before scheduling).
     * @throws IllegalArgumentException if {@code request} is null.
     * @Thread-context any thread.
     */
    public CompletableFuture<RegionExecutionResult> execute(RegionExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        WorkerState now = state.get();
        if (now != WorkerState.ACTIVE) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "worker not ACTIVE (state=" + now + "); activate() before execute()"));
        }
        return CompletableFuture.supplyAsync(() -> engine.execute(request), executor);
    }

    /**
     * Stop the worker: mark STOPPED and shut the executor down, draining in-flight tasks. Idempotent.
     */
    @Override
    public void close() {
        state.set(WorkerState.STOPPED);
        executor.close();
    }
}
