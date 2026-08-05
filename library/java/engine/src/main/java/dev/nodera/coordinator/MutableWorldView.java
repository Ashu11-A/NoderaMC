package dev.nodera.coordinator;

import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkStamp;
import dev.nodera.core.state.ChunkStampBook;
import dev.nodera.core.state.Hlc;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.InventoryCredit;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionChunkIndex;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;

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

    /**
     * The canonical {@link StateRoot} of a region's current state.
     *
     * <h2>Why this is on the seam rather than at each call site</h2>
     *
     * <p>Committing a region re-extracts it, encodes all 64 columns and SHA-256s the result — and a
     * region is pending whenever any ghost moved, so this ran up to once per tick per region on the
     * server thread. Worse, the same value was then computed a <b>second</b> time for the same state
     * when the certified delta was applied and verified. A live two-player session measured that as
     * 15.5 TPS.
     *
     * <p>The default is the honest, uncached computation, so an implementation that does not track
     * its own writes stays correct. {@link InMemoryWorldView} — the single writer behind every
     * production view — overrides it with a cache keyed on how many times the region has actually
     * changed, which makes the second call free and the ghost-only case free entirely.
     *
     * @param region  the region.
     * @param version the version to stamp the extracted snapshot with.
     * @param tick    the tick to stamp it with.
     * @return the root over the canonical encoding — byte-for-byte what hashing
     *         {@link #reExtract} produces.
     * @Thread-context server main thread.
     */
    default StateRoot regionRoot(RegionId region, SnapshotVersion version, long tick) {
        return StateRoot.of(new HashService().hash(reExtract(region, version, tick)));
    }

    /**
     * The region's per-column content stamps under a merkle root.
     *
     * <p>What a peer compares instead of transferring. The default encodes and hashes every column;
     * {@link InMemoryWorldView} overrides it to re-hash only the columns that have been written
     * since it was last asked, which is what makes asking cheap enough to do on every commit.
     *
     * @param region the region.
     * @param book   where this node records when each column was last written; supplies the
     *               provenance clock each stamp carries. {@code null} stamps every column with the
     *               floor reading.
     * @return the index.
     * @Thread-context server main thread.
     */
    default RegionChunkIndex chunkIndex(RegionId region, ChunkStampBook book) {
        java.util.List<ChunkStamp> stamps = new java.util.ArrayList<>();
        for (dev.nodera.core.state.ChunkColumnState column
                : reExtract(region, SnapshotVersion.INITIAL, 0L).chunks()) {
            Hlc stamp = book == null ? Hlc.ZERO
                    : book.stampFor(column.chunkX(), column.chunkZ(), Hlc.ZERO);
            stamps.add(ChunkStamp.of(column, stamp));
        }
        return RegionChunkIndex.of(region, stamps);
    }

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
