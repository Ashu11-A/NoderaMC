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

    /**
     * The rule production installs: locked <b>only while a fetch is in flight</b> for the region.
     *
     * <h2>Why this exists rather than the bare adapter</h2>
     *
     * <p>{@link ChunkLockMap#isChunkEditable} answers {@code false} for a region it is not tracking,
     * and that default is right for the map: a section registered for download and not yet verified
     * must never read as editable. It is the wrong default for the <i>applier</i>, which is the one
     * choke point every {@code ServerLevel} write in the product passes through. A worker that is
     * not downloading anything tracks nothing, so installing the bare adapter would answer "locked"
     * for every position in every region — normal gameplay would stop, not just fetches. The
     * network register records that exact regression as the reason the wiring was never done
     * ({@code docs/network/LIMITATIONS.md}, L-33: "installing the adapter as it stands would lock
     * the whole world against editing").
     *
     * <p>So the question is asked in two steps: is this region being fetched at all, and if it is,
     * has the piece backing this column arrived and verified? An untracked region is fully editable;
     * a tracked one is locked exactly where its content has not landed. Absence fails <b>open</b>,
     * arrival-in-progress fails closed.
     *
     * @param locks the download lane's live lock map (never null; it may simply be empty).
     * @return the seam to hand {@link WorldMutationApplier}.
     * @Thread-context any thread; the returned seam is as thread-safe as {@code locks}.
     */
    public static WorldMutationApplier.ChunkEditability whileFetching(ChunkLockMap locks) {
        ChunkLockEditability arrived = new ChunkLockEditability(locks);
        return (region, pos) -> locks.trackedRoot(region) == null || arrived.editable(region, pos);
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
