package dev.nodera.simulation;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;

/**
 * Read-only view over the region state a {@link dev.nodera.simulation.rules.RuleSet} validates
 * against (Task 3). The MVP implementation is {@link MutableRegionState}, which serves both the
 * read side (this interface) and the write side during {@code execute}.
 *
 * <p><b>Owned vs halo.</b> {@link #inOwnedRegion(NBlockPos)} is true inside the region's owned
 * chunk square (the area it may mutate); {@link #inHalo(NBlockPos)} is true inside the read-only
 * halo ring that surrounds it. A {@link MutableRegionState#setBlock} on a position that is not
 * owned throws {@link IllegalStateException} (fail-hard, Folia-style) — the engine must never mutate
 * the halo.
 *
 * @Thread-context implementations are thread-confined per {@code execute} call; the read view must
 *                 not escape that call.
 */
public interface RegionWorldView {

    /**
     * @return the block state id at {@code pos}, or {@code 0} (AIR) when the position is outside
     *         the snapshot's covered chunks (halo/uncovered) — the documented MVP behaviour until
     *         the real halo arrives with redstone (Task 13).
     * @Thread-context thread-confined per call.
     */
    int getBlock(NBlockPos pos);

    /**
     * @return {@code true} if {@code pos} is inside this region's owned chunk square.
     * @Thread-context thread-confined per call.
     */
    boolean inOwnedRegion(NBlockPos pos);

    /**
     * @return {@code true} if {@code pos} is in the halo ring (in footprint, not owned).
     * @Thread-context thread-confined per call.
     */
    boolean inHalo(NBlockPos pos);

    /**
     * @return the region this view belongs to.
     * @Thread-context thread-confined per call.
     */
    RegionId region();

    /** Return current canonical entity state, or {@code null} when absent. */
    PersistedEntityState entity(NetworkEntityId id);

    /**
     * @return the container table entry at {@code pos}, or {@code null} when none exists (an
     *         empty, never-touched chest has no entry — the table is sparse). Default null for
     *         views predating the container lane (Task 16 / L-10).
     * @Thread-context thread-confined per call.
     */
    /**
     * The committee-agreed set of node ids permitted to run a
     * {@link dev.nodera.core.action.CommandAction} against this region (Task 16 / L-14).
     *
     * <p>Empty by default, and empty means <b>nobody</b>: an unset operator set must never read as
     * "everyone is an operator". Views that carry the execution context override this; the set is
     * root-determining, so every member evaluates the same predicate.
     *
     * @return the operator node ids; never null.
     */
    default java.util.Set<dev.nodera.core.identity.NodeId> operators() {
        return java.util.Set.of();
    }

    default dev.nodera.core.state.ContainerEntry container(NBlockPos pos) {
        return null;
    }
}
