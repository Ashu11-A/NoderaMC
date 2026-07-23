package dev.nodera.coordinator;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.InventoryCredit;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;

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

    /** True when canonical state for {@code region} is loaded and writable. */
    boolean isRegionLoaded(RegionId region);

    /** Begin an all-or-nothing mutation scope. Closing without commit rolls every write back. */
    MutationScope beginMutation();

    /** Re-extract canonical live state for root verification. */
    RegionSnapshot reExtract(RegionId region, SnapshotVersion version, long tick);

    /** Preserve or advance canonical snapshot body encoding during legacy replay/migration. */
    default void setSnapshotBodyVersion(RegionId region, int bodyVersion) {
        // Live worlds emit current snapshots; compatibility stores override when version matters.
    }

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

    /** Return canonical entity state, or {@code null} when absent. */
    PersistedEntityState getEntity(RegionId region, NetworkEntityId id);

    /** Insert or replace one canonical entity row. */
    void setEntity(RegionId region, PersistedEntityState entity);

    /** Remove one canonical entity row. */
    void removeEntity(RegionId region, NetworkEntityId id);

    /** Return a prior credit with the same replay key, or {@code null}. */
    InventoryCredit getInventoryCredit(InventoryCredit credit);

    /** Apply one validated, replay-safe inventory credit. */
    void creditInventory(InventoryCredit credit);

    /** Transaction handle used by {@link WorldMutationApplier}. */
    interface MutationScope extends AutoCloseable {
        void commit();

        @Override
        void close();
    }
}
