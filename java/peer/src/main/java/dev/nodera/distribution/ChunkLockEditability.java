package dev.nodera.distribution;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.coordinator.WorldMutationApplier;

/**
 * The L-33 wiring: adapts {@link ChunkLockMap#isChunkEditable(RegionId, int)} to the applier's
 * {@link WorldMutationApplier.ChunkEditability} seam. The block position resolves to its chunk's
 * ordinal in the snapshot's canonical chunk order (chunk-x major, chunk-z minor over the region's
 * 8×8 grid — the order every snapshot builder and the {@link RegionSnapshotSplitter} layout use),
 * so a mutation targeting an un-arrived/un-verified section fails closed inside
 * {@code WorldMutationApplier} before any write.
 *
 * <p>Thread-context: as thread-safe as the underlying {@link ChunkLockMap}.
 */
public final class ChunkLockEditability implements WorldMutationApplier.ChunkEditability {

    private final ChunkLockMap locks;

    public ChunkLockEditability(ChunkLockMap locks) {
        if (locks == null) {
            throw new IllegalArgumentException("locks must not be null");
        }
        this.locks = locks;
    }

    @Override
    public boolean editable(RegionId region, NBlockPos pos) {
        int chunkX = Math.floorDiv(pos.x(), 16);
        int chunkZ = Math.floorDiv(pos.z(), 16);
        int dx = chunkX - region.originChunkX();
        int dz = chunkZ - region.originChunkZ();
        if (dx < 0 || dx >= NoderaConstants.REGION_SIZE_CHUNKS
                || dz < 0 || dz >= NoderaConstants.REGION_SIZE_CHUNKS) {
            // Outside the region's own footprint (halo reads never write): fail closed.
            return false;
        }
        return locks.isChunkEditable(region, dx * NoderaConstants.REGION_SIZE_CHUNKS + dz);
    }
}
