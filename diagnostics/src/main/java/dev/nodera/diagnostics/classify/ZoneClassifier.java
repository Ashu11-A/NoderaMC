package dev.nodera.diagnostics.classify;

import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.diagnostics.model.RegionOwnership;
import dev.nodera.diagnostics.state.OwnershipState;

/**
 * Maps a world position to its {@link OwnershipState} (Task 18).
 *
 * <p>Pure and unit-tested with negative coordinates (Task 18 acceptance #1). {@code block ≫ 4 →
 * chunk} (via {@link Math#floorDiv(int, int)}, correct for negatives), then chunk → {@link RegionId}
 * via {@link RegionId#fromChunk}, then a lookup against the snapshot's ownership sets.
 *
 * <p><b>Region-granular, not halo-aware.</b> Classification is by owning {@link RegionId} only; the
 * read-only {@code HALO_CHUNKS} halo ring that {@link dev.nodera.core.region.RegionBounds} adds for
 * border simulation is a data-loading concept, not an ownership one — a block in region A's halo
 * still belongs to its own {@link RegionId} and classifies by that region's ownership. A
 * {@code ZoneClassifierTest} pins this against {@code RegionBounds.ownsBlock} so the geometric
 * contract Task 6 will hand off is locked.
 *
 * <p>The ownership sets are placeholders (empty) until Task 6 — so every region currently classifies
 * {@link OwnershipState#UNASSIGNED}.
 *
 * <p>Thread-context: stateless; safe from any thread.
 */
public final class ZoneClassifier {

    private ZoneClassifier() {}

    /** Block-to-chunk shift: 16 blocks per chunk. */
    private static final int BLOCKS_PER_CHUNK = 16;

    /**
     * @return the {@link RegionId} that owns the block column {@code (blockX, blockZ)} in {@code dim}.
     * @Thread-context any thread.
     */
    public static RegionId regionAt(DimensionKey dim, int blockX, int blockZ) {
        int chunkX = Math.floorDiv(blockX, BLOCKS_PER_CHUNK);
        int chunkZ = Math.floorDiv(blockZ, BLOCKS_PER_CHUNK);
        return RegionId.fromChunk(dim, chunkX, chunkZ);
    }

    /**
     * Classify the block column.
     *
     * @param dim       the dimension.
     * @param blockX    world block X (any sign).
     * @param blockZ    world block Z (any sign).
     * @param ownership this peer's region ownership (may be the placeholder empty set).
     * @return the {@link OwnershipState} for the region containing that column.
     * @Thread-context any thread.
     */
    public static OwnershipState classify(DimensionKey dim, int blockX, int blockZ,
                                          RegionOwnership ownership) {
        RegionId region = regionAt(dim, blockX, blockZ);
        if (ownership.primary().contains(region)) {
            return OwnershipState.OWNED;
        }
        if (ownership.validator().contains(region)) {
            return OwnershipState.VALIDATING;
        }
        if (ownership.replica().contains(region)) {
            return OwnershipState.REPLICA;
        }
        // If at least one region is delegated to this peer, an unlisted region is genuinely foreign;
        // if nothing is delegated yet (placeholder), the region is merely unassigned.
        return ownership.isEmpty() ? OwnershipState.UNASSIGNED : OwnershipState.FOREIGN;
    }
}
