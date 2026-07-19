package dev.nodera.core.region;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

/**
 * Stable region identity on the static 8×8-chunk grid (Plan §3.2).
 *
 * <p>{@code RegionId = (dimension, floorDiv(chunkX, REGION_SIZE_CHUNKS), floorDiv(chunkZ, …))}.
 * {@link Math#floorDiv(int, int)} is used everywhere so negative chunk coordinates map correctly
 * (Task 2 acceptance #3): chunk −1 → region −1; chunk −8 → region −1; chunk −9 → region −2.
 *
 * <p>Static grid (unlike Folia's merge/split) so committee leases and epochs have stable identity.
 *
 * <p>Thread-context: immutable, any thread.
 */
public record RegionId(DimensionKey dimension, int regionX, int regionZ) implements Encodable {

    public RegionId {
        if (dimension == null) {
            throw new IllegalArgumentException("dimension must not be null");
        }
    }

    /** Derive a region id from a chunk coordinate (any sign). */
    public static RegionId fromChunk(DimensionKey dimension, int chunkX, int chunkZ) {
        return new RegionId(
                dimension,
                Math.floorDiv(chunkX, NoderaConstants.REGION_SIZE_CHUNKS),
                Math.floorDiv(chunkZ, NoderaConstants.REGION_SIZE_CHUNKS));
    }

    /**
     * The chunk X of the region's origin (min chunk in the region). {@code regionX} is already the
     * floor-divided region index, so the origin is a plain multiplication (correct for negatives:
     * region −1 → origin chunk −8).
     */
    public int originChunkX() {
        return regionX * NoderaConstants.REGION_SIZE_CHUNKS;
    }

    /** The chunk Z of the region's origin — see {@link #originChunkX()}. */
    public int originChunkZ() {
        return regionZ * NoderaConstants.REGION_SIZE_CHUNKS;
    }

    /** Min world block X of the owned area. */
    public int originBlockX() {
        return originChunkX() * 16;
    }

    public int originBlockZ() {
        return originChunkZ() * 16;
    }

    @Override
    public String toString() {
        return "Region[" + dimension + " @ " + regionX + "," + regionZ + "]";
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.REGION_ID).writeU16(ENCODING_VERSION);
        dimension.encode(w);
        w.writeU32(Integer.toUnsignedLong(regionX));
        w.writeU32(Integer.toUnsignedLong(regionZ));
    }

    public static RegionId decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.REGION_ID) {
            throw new IllegalStateException("expected REGION_ID tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        DimensionKey dim = DimensionKey.decode(r);
        int rx = (int) r.readU32();
        int rz = (int) r.readU32();
        return new RegionId(dim, rx, rz);
    }
}
