package dev.nodera.simulation;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.simulation.border.RegionHalo;

/**
 * The complete input bundle for one {@link RegionEngine#execute(RegionExecutionRequest)} call
 * (Task 3). A {@code RegionExecutionRequest} is self-contained: the {@link RegionExecutionContext}
 * fixes the deterministic parameters, the {@link RegionSnapshot} fixes the starting state, and the
 * {@link ActionBatch} fixes the ordered action stream. No external reference is consulted during
 * execution.
 *
 * <p>The {@link RegionHalo} is part of that bundle for the same reason everything else is: border
 * mechanics read neighbour state, so a batch that executes differently under a different halo is a
 * batch whose result depends on something outside the request. Carrying it here keeps the
 * self-contained property honest — and makes the halo something every replica must agree on, which
 * is exactly what it is.
 *
 * @param context  the deterministic execution parameters.
 * @param snapshot the base snapshot (pre-batch state) at {@code context.baseVersion()}.
 * @param batch    the ordered action stream to replay.
 * @param halo     neighbour-backed border reads; empty (every read AIR) unless a caller supplies
 *                 slices, which is what every caller did before the halo had any backing.
 * @Thread-context immutable, any thread.
 */
public record RegionExecutionRequest(
        RegionExecutionContext context,
        RegionSnapshot snapshot,
        ActionBatch batch,
        RegionHalo halo
) {

    /**
     * A request with an <b>empty</b> halo: every border read is AIR, which is what the engine did
     * before neighbour slices existed. Kept as the short form because the overwhelming majority of
     * call sites — every test fixture, every single-region path — have no neighbour to read.
     */
    public RegionExecutionRequest(RegionExecutionContext context, RegionSnapshot snapshot,
                                  ActionBatch batch) {
        this(context, snapshot, batch,
                new RegionHalo(snapshot == null ? null : snapshot.region()));
    }

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
        if (halo == null) {
            throw new IllegalArgumentException("halo must not be null");
        }
        if (!halo.region().equals(snapshot.region())) {
            // A halo built for a different region would silently answer border reads with another
            // region's ring — a divergence with no exception anywhere to say so.
            throw new IllegalArgumentException(
                    "halo is for " + halo.region() + " but the snapshot is " + snapshot.region());
        }
    }
}
