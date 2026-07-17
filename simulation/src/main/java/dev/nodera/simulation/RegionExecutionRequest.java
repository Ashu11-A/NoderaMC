package dev.nodera.simulation;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.state.RegionSnapshot;

/**
 * The complete input bundle for one {@link RegionEngine#execute(RegionExecutionRequest)} call
 * (Task 3). A {@code RegionExecutionRequest} is self-contained: the {@link RegionExecutionContext}
 * fixes the deterministic parameters, the {@link RegionSnapshot} fixes the starting state, and the
 * {@link ActionBatch} fixes the ordered action stream. No external reference is consulted during
 * execution.
 *
 * @param context  the deterministic execution parameters.
 * @param snapshot the base snapshot (pre-batch state) at {@code context.baseVersion()}.
 * @param batch    the ordered action stream to replay.
 * @Thread-context immutable, any thread.
 */
public record RegionExecutionRequest(
        RegionExecutionContext context,
        RegionSnapshot snapshot,
        ActionBatch batch
) {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any argument is null.
     * @Thread-context deterministic; safe from any thread.
     */
    public RegionExecutionRequest {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        if (batch == null) {
            throw new IllegalArgumentException("batch must not be null");
        }
    }
}
