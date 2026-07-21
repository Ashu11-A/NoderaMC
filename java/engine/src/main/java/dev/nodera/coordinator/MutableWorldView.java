package dev.nodera.coordinator;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;

/**
 * The single seam through which the coordinator reads and writes committed world state (Task 6,
 * Plan §3.9 / Task 0 §4.5). Keeping the real {@code ServerLevel} behind this interface is what makes
 * the whole commit pipeline unit-testable without a Minecraft server: the NeoForge mod supplies a
 * {@code ServerLevel}-backed impl on the server main thread, and tests use
 * {@link InMemoryWorldView}. Per Task 0 §4.5, all world mutation flows through
 * {@link WorldMutationApplier}, which is the only caller of {@link #setBlock}.
 *
 * @Thread-context implementations are called on the server main thread only (single writer).
 */
public interface MutableWorldView {

    /**
     * @param region the region owning {@code pos}.
     * @param pos    the absolute block position.
     * @return the current block state id at {@code pos}.
     */
    int getBlock(RegionId region, NBlockPos pos);

    /**
     * Set the block state id at {@code pos}. Called only by {@link WorldMutationApplier}, only after
     * its validate pass has confirmed every mutation's compare-and-set guard.
     *
     * @param region  the region owning {@code pos}.
     * @param pos     the absolute block position.
     * @param stateId the new block state id.
     */
    void setBlock(RegionId region, NBlockPos pos, int stateId);
}
