package dev.nodera.shadow;

/**
 * Lifecycle of a client {@link WorkerRuntime} (Task 5). A worker is created {@link #INACTIVE},
 * flipped to {@link #ACTIVE} by the {@code WorkerActivation} handshake step, and {@link #STOPPED}
 * exactly once on {@link WorkerRuntime#close()}. Transitions are monotonic: STOPPED never returns
 * to ACTIVE.
 */
public enum WorkerState {
    /** Constructed but not yet activated by the handshake; {@code execute} is refused. */
    INACTIVE,
    /** Activated; accepts execution requests on the virtual-thread executor. */
    ACTIVE,
    /** Closed; the executor is shut down and {@code execute} is refused permanently. */
    STOPPED
}
