package dev.nodera.simulation;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;

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
}
