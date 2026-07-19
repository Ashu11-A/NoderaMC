package dev.nodera.shadow;

import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.RegionEngine;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;

/**
 * The server-side reference recompute (Task 5). Runs the same deterministic {@link RegionEngine} on
 * the same inputs the clients received to produce the <b>reference root</b> a
 * {@link DivergenceTracker} compares client {@link ShadowResult}s against. Runs the request twice
 * and asserts the two roots match — a cheap self-check that catches nondeterminism inside one JVM
 * (throws {@link NondeterminismException}) so it is never mistaken for a cross-client divergence.
 *
 * <p>In the real deployment this runs on a dedicated async executor, never the server main thread;
 * here it is a plain call so tests stay deterministic. Nothing it produces is committed — Phase 1 is
 * observation only.
 *
 * @Thread-context stateless; safe from any thread (the engine is a pure function).
 */
public final class ServerRecompute {

    private final RegionEngine engine;

    /**
     * @param engine the reference engine; must not be null.
     * @throws IllegalArgumentException if {@code engine} is null.
     */
    public ServerRecompute(RegionEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        this.engine = engine;
    }

    /**
     * Compute the reference outcome for {@code request}, with an intra-JVM determinism self-check.
     *
     * @param request the execution inputs (context + reference snapshot + batch).
     * @return the reference root and delta.
     * @throws NondeterminismException if two recomputes of the same request disagree.
     * @throws IllegalArgumentException if {@code request} is null.
     */
    public Reference reference(RegionExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        RegionExecutionResult first = engine.execute(request);
        RegionExecutionResult second = engine.execute(request);
        if (!first.resultingRoot().equals(second.resultingRoot())) {
            throw new NondeterminismException(
                    request.context().region(), first.resultingRoot(), second.resultingRoot());
        }
        return new Reference(first.resultingRoot(), first.delta());
    }

    /**
     * The reference outcome: the truth root and the transport delta.
     *
     * @param root  the reference state root (consensus truth).
     * @param delta the reference delta (used to advance the server's reference chain).
     */
    public record Reference(StateRoot root, RegionDelta delta) {
        public Reference {
            if (root == null) {
                throw new IllegalArgumentException("root must not be null");
            }
            if (delta == null) {
                throw new IllegalArgumentException("delta must not be null");
            }
        }
    }
}
