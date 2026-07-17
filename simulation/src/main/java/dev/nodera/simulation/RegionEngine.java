package dev.nodera.simulation;

/**
 * The deterministic region execution engine (Task 3). One method, one contract: turn a
 * {@link RegionExecutionRequest} into a {@link RegionExecutionResult} by replaying the batch's
 * actions in server order against the snapshot, under a fixed rule set, with no access to clocks,
 * RNGs other than {@link DeterministicRandom}, IO, or static mutable state.
 *
 * <p><b>Purity contract.</b> {@code execute} is a pure function of its arguments: any two replicas
 * fed the same {@code (context, snapshot, batch)} on any JVM MUST produce a result whose
 * {@link RegionExecutionResult#resultingRoot()} and canonical {@code RegionDelta} encoding are
 * byte-identical. The {@link dev.nodera.simulation.engine.FlatWorldRegionEngine} implementation and
 * the {@code simulation} ArchUnit forbidden-API test enforce this; the jqwik property test proves it.
 *
 * <p><b>Truth vs transport.</b> {@code resultingRoot} (the SHA-256 of the post-state
 * {@link dev.nodera.core.state.RegionSnapshot}) is consensus truth; the {@code RegionDelta} is
 * transport. Rejections and {@link RegionExecutionResult.ExecutionStats} are deterministic but are
 * NOT part of the hashed outcome.
 *
 * @Thread-context thread-confined per call; implementations must hold no shared mutable state.
 */
public interface RegionEngine {

    /**
     * Execute one region batch deterministically.
     *
     * @param request the fully-resolved execution inputs (context + snapshot + batch).
     * @return the resulting delta, state root, and non-hashed execution stats.
     * @throws IllegalStateException if a cross-region action reaches the engine (coordinator must
     *                               filter first), if a halo write is attempted, or if the request
     *                               carries a {@code rulesVersion} / {@code registryFingerprint}
     *                               this engine does not support.
     * @Thread-context thread-confined per call.
     */
    RegionExecutionResult execute(RegionExecutionRequest request);
}
