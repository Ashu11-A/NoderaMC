package dev.nodera.core.region;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

/**
 * Horizontal (XZ) footprint of a {@link RegionId} on the static grid (Plan §3.2). A region owns
 * an {@link NoderaConstants#REGION_SIZE_CHUNKS} × {@link NoderaConstants#REGION_SIZE_CHUNKS}
 * square of chunks; it also has a read-only {@link NoderaConstants#HALO_CHUNKS}-chunk ring (the
 * "halo") that it needs to simulate border interactions but never owns.
 *
 * <p>Block coordinates are 16× chunk coordinates: chunk {@code [c, c]} covers blocks
 * {@code [c*16, c*16+15]} on each axis. Y is unbounded; bounds are horizontal only.
 *
 * <p>"Owned" vs "halo" vs "contained":
 * <ul>
 *   <li>{@link #ownsBlock(int, int)} — strictly inside the owned chunk square (excludes halo).</li>
 *   <li>{@link #isHaloBlock(int, int)} — inside the halo-inclusive footprint but NOT owned.</li>
 *   <li>{@link #containsBlock(int, int)} / {@link #containsChunk(int, int)} — owned + halo
 *       (the full footprint this region holds data for).</li>
 * </ul>
 *
 * <p>Negative coordinates are fully supported: region {@code (-1,-1)} owns chunks
 * {@code [-8,-1]×[-8,-1]} and blocks {@code [-128,-1]×[-128,-1]}.
 *
 * <p>Thread-context: immutable, any thread.
 */
public record RegionBounds(
        RegionId region,
        int minChunkX,
        int maxChunkX,
        int minChunkZ,
        int maxChunkZ,
        int minChunkXWithHalo,
        int maxChunkXWithHalo,
        int minChunkZWithHalo,
        int maxChunkZWithHalo) implements Encodable {

    /**
     * Build the bounds for a region from its grid coordinate (computes owned + halo ranges).
     *
     * @param region the region; must not be {@code null}.
     * @return the bounds.
     * @Thread-context any thread.
     */
    public static RegionBounds of(RegionId region) {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        int originX = region.originChunkX();
        int originZ = region.originChunkZ();
        int size = NoderaConstants.REGION_SIZE_CHUNKS;
        int halo = NoderaConstants.HALO_CHUNKS;
        int minCx = originX;
        int maxCx = originX + size - 1;
        int minCz = originZ;
        int maxCz = originZ + size - 1;
        return new RegionBounds(
                region,
                minCx, maxCx, minCz, maxCz,
                minCx - halo, maxCx + halo, minCz - halo, maxCz + halo);
    }

    /**
     * @return {@code true} if the chunk is within the owned + halo footprint.
     * @Thread-context any thread.
     */
    public boolean containsChunk(int chunkX, int chunkZ) {
        return chunkX >= minChunkXWithHalo && chunkX <= maxChunkXWithHalo
                && chunkZ >= minChunkZWithHalo && chunkZ <= maxChunkZWithHalo;
    }

    /**
     * @return {@code true} if the block is within the owned + halo footprint (block = chunk×16).
     * @Thread-context any thread.
     */
    public boolean containsBlock(int x, int z) {
        return x >= minChunkXWithHalo * 16 && x <= maxChunkXWithHalo * 16 + 15
                && z >= minChunkZWithHalo * 16 && z <= maxChunkZWithHalo * 16 + 15;
    }

    /**
     * @return {@code true} if the block is inside the OWNED square (excludes the halo ring).
     * @Thread-context any thread.
     */
    public boolean ownsBlock(int x, int z) {
        return x >= minChunkX * 16 && x <= maxChunkX * 16 + 15
                && z >= minChunkZ * 16 && z <= maxChunkZ * 16 + 15;
    }

    /**
     * @return {@code true} if the block is in the halo ring (in footprint but not owned).
     * @Thread-context any thread.
     */
    public boolean isHaloBlock(int x, int z) {
        return containsBlock(x, z) && !ownsBlock(x, z);
    }

    /**
     * @return number of OWNED chunks in the region (always REGION_SIZE_CHUNKS²).
     * @Thread-context any thread.
     */
    public int ownedChunkCount() {
        int s = NoderaConstants.REGION_SIZE_CHUNKS;
        return s * s;
    }

    /**
     * Canonical encoding: {@code tag(u16) + version(u16) + region(encodable) +
     * minChunkX(u32) + maxChunkX(u32) + minChunkZ(u32) + maxChunkZ(u32) +
     * minChunkXWithHalo(u32) + maxChunkXWithHalo(u32) + minChunkZWithHalo(u32) +
     * maxChunkZWithHalo(u32)}. Coordinates are written unsigned (two's-complement bit pattern)
     * so negative values round-trip exactly, mirroring {@link RegionId}.
     *
     * @param writer the canonical sink.
     * @Thread-context any thread; does not retain the writer.
     */
    @Override
    public void encode(CanonicalWriter writer) {
        writer.writeU16(TypeTags.REGION_BOUNDS).writeU16(ENCODING_VERSION);
        writer.writeEncodable(region);
        writer.writeU32(Integer.toUnsignedLong(minChunkX));
        writer.writeU32(Integer.toUnsignedLong(maxChunkX));
        writer.writeU32(Integer.toUnsignedLong(minChunkZ));
        writer.writeU32(Integer.toUnsignedLong(maxChunkZ));
        writer.writeU32(Integer.toUnsignedLong(minChunkXWithHalo));
        writer.writeU32(Integer.toUnsignedLong(maxChunkXWithHalo));
        writer.writeU32(Integer.toUnsignedLong(minChunkZWithHalo));
        writer.writeU32(Integer.toUnsignedLong(maxChunkZWithHalo));
    }

    /**
     * Inverse of {@link #encode(CanonicalWriter)}.
     *
     * @param r the canonical source, positioned at the {@code REGION_BOUNDS} tag.
     * @return the decoded bounds.
     * @throws IllegalStateException if the tag or version is invalid.
     * @Thread-context any thread; one reader per decode call (not thread-safe).
     */
    public static RegionBounds decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.REGION_BOUNDS) {
            throw new IllegalStateException("expected REGION_BOUNDS tag, got " + tag);
        }
        int version = r.readU16();
        if (version != ENCODING_VERSION) {
            throw new IllegalStateException("unsupported REGION_BOUNDS encoding version " + version);
        }
        RegionId region = RegionId.decode(r);
        int minCx = (int) r.readU32();
        int maxCx = (int) r.readU32();
        int minCz = (int) r.readU32();
        int maxCz = (int) r.readU32();
        int minCxH = (int) r.readU32();
        int maxCxH = (int) r.readU32();
        int minCzH = (int) r.readU32();
        int maxCzH = (int) r.readU32();
        return new RegionBounds(region, minCx, maxCx, minCz, maxCz, minCxH, maxCxH, minCzH, maxCzH);
    }
}
