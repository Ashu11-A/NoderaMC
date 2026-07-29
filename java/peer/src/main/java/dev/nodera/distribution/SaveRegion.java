package dev.nodera.distribution;

import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;

import java.util.Objects;
import java.util.Optional;

/**
 * A Minecraft <b>region file</b> — {@code r.0.0.mca} — as an addressable unit of the peer network.
 *
 * <p>Java Edition stores a world as a grid of region files, each covering <b>32×32 chunks</b>
 * (512×512 blocks), in parallel trees: {@code region/} for blocks, {@code entities/} for entities
 * and {@code poi/} for points of interest, per dimension. That grid is the unit the game itself
 * loads, writes and evicts, which makes it the honest unit for a peer network to transmit and hold:
 * a peer can serve the ground a player is standing on without holding the rest of the world, and
 * two peers can hold disjoint halves of a world and between them hold all of it.
 *
 * <h2>Why this is not {@link RegionId}</h2>
 *
 * <p>Nodera's simulated {@code RegionId} is a different grid — {@code region.regionSizeChunks}
 * chunks per side, 8 by default — because the unit you want to <i>simulate</i> (small enough for a
 * committee to re-execute in a tick) is not the unit Minecraft wants to <i>store</i>. Conflating
 * them would silently re-scale one of the two. They convert explicitly: {@link #toRegionId()} files
 * a save region under its own dimension namespace so the piece plane can address it without
 * pretending it is a simulated region, and {@link #ofChunk} maps the other way.
 *
 * <h2>The world bucket</h2>
 *
 * <p>Not every byte of a save belongs to a region: {@code level.dat}, {@code playerdata/} and the
 * world's signed Nodera records describe the world as a whole. Those are filed under {@link #WORLD}
 * rather than being spread across regions or left unaddressed, so the rule "every piece of stored
 * and transmitted data names the region it belongs to" holds with no exceptions and no silent
 * remainder — {@code WORLD} is a name, and a piece carrying it is making a claim you can check.
 *
 * <p>Thread-context: immutable value; safe for any thread.
 *
 * @param dimension the dimension's save-tree prefix ({@code ""} for the overworld, {@code "DIM-1"},
 *                  {@code "DIM1"}, or {@code "dimensions/<namespace>/<path>"}); {@code ""} with
 *                  {@link #WORLD_MARKER} coordinates for the world bucket.
 * @param regionX   the region's X coordinate on the 32-chunk grid.
 * @param regionZ   the region's Z coordinate on the 32-chunk grid.
 */
public record SaveRegion(String dimension, int regionX, int regionZ) {

    /** Chunks per region file side — the Java Edition region format's 32×32 grid. */
    public static final int CHUNKS_PER_REGION = 32;

    /** Blocks per region file side: {@value #CHUNKS_PER_REGION} chunks × 16 blocks. */
    public static final int BLOCKS_PER_REGION = CHUNKS_PER_REGION * 16;

    /**
     * The coordinate that marks {@link #WORLD}. {@link Integer#MIN_VALUE} because it is the one
     * value no real region grid can reach: a region coordinate is a chunk coordinate divided by 32.
     */
    static final int WORLD_MARKER = Integer.MIN_VALUE;

    /** The bucket for save data that belongs to the world rather than to any one region. */
    public static final SaveRegion WORLD = new SaveRegion("", WORLD_MARKER, WORLD_MARKER);

    /** The dimension namespace save regions are filed under in the piece plane. */
    private static final String PIECE_NAMESPACE = "nodera";

    public SaveRegion {
        Objects.requireNonNull(dimension, "dimension");
    }

    /** @return whether this is the world bucket rather than a region file. */
    public boolean isWorld() {
        return regionX == WORLD_MARKER && regionZ == WORLD_MARKER;
    }

    /** @return the region file's name, e.g. {@code r.0.-1.mca}. */
    public String fileName() {
        if (isWorld()) {
            throw new IllegalStateException("the world bucket is not a region file");
        }
        return "r." + regionX + "." + regionZ + ".mca";
    }

    /**
     * The region containing a chunk.
     *
     * @param dimension the dimension's save-tree prefix.
     * @param chunkX    the chunk's X coordinate.
     * @param chunkZ    the chunk's Z coordinate.
     * @return the region file that chunk lives in.
     */
    public static SaveRegion ofChunk(String dimension, int chunkX, int chunkZ) {
        return new SaveRegion(dimension,
                Math.floorDiv(chunkX, CHUNKS_PER_REGION),
                Math.floorDiv(chunkZ, CHUNKS_PER_REGION));
    }

    /**
     * The region containing a block position.
     *
     * @param dimension the dimension's save-tree prefix.
     * @param blockX    the block's X coordinate.
     * @param blockZ    the block's Z coordinate.
     * @return the region file that block lives in.
     */
    public static SaveRegion ofBlock(String dimension, int blockX, int blockZ) {
        return new SaveRegion(dimension,
                Math.floorDiv(blockX, BLOCKS_PER_REGION),
                Math.floorDiv(blockZ, BLOCKS_PER_REGION));
    }

    /**
     * Which region a save file belongs to.
     *
     * <p>Recognises the three per-dimension region trees ({@code region/}, {@code entities/},
     * {@code poi/}) under the overworld root, the legacy {@code DIM-1}/{@code DIM1} prefixes and
     * the modern {@code dimensions/<namespace>/<path>/} prefix. Anything else — {@code level.dat},
     * {@code playerdata/}, {@code data/}, the signed Nodera records — is {@link #WORLD}.
     *
     * @param relativePath the {@code /}-separated save-relative path.
     * @return the region it belongs to; never empty, because every file has an answer.
     * @Thread-context any thread; pure function.
     */
    public static SaveRegion of(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        int lastSlash = relativePath.lastIndexOf('/');
        if (lastSlash < 0) {
            return WORLD; // a file at the save root is world data
        }
        Optional<int[]> coords = parseRegionFileName(relativePath.substring(lastSlash + 1));
        if (coords.isEmpty()) {
            return WORLD;
        }
        String dir = relativePath.substring(0, lastSlash);
        Optional<String> dimension = dimensionOf(dir);
        return dimension
                .map(d -> new SaveRegion(d, coords.get()[0], coords.get()[1]))
                .orElse(WORLD);
    }

    /**
     * The dimension prefix of a directory that holds region files, if it is one.
     *
     * <p>Deliberately strict: only the three known trees count. A mod's own {@code r.1.2.mca} in
     * some other directory is world data as far as this lane is concerned — filing it as a region
     * would put it in a bucket a joiner assembles by coordinate, and it would be lost the first
     * time a peer served that coordinate from a save that did not have it.
     */
    private static Optional<String> dimensionOf(String directory) {
        for (String tree : new String[] {"region", "entities", "poi"}) {
            if (directory.equals(tree)) {
                return Optional.of(""); // overworld
            }
            String suffix = "/" + tree;
            if (directory.endsWith(suffix)) {
                String prefix = directory.substring(0, directory.length() - suffix.length());
                if (prefix.equals("DIM-1") || prefix.equals("DIM1")
                        || (prefix.startsWith("dimensions/") && prefix.chars()
                                .filter(c -> c == '/').count() == 2)) {
                    return Optional.of(prefix);
                }
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** {@code r.<x>.<z>.mca} → its two coordinates. */
    private static Optional<int[]> parseRegionFileName(String fileName) {
        if (!fileName.startsWith("r.") || !fileName.endsWith(".mca")) {
            return Optional.empty();
        }
        String middle = fileName.substring(2, fileName.length() - 4);
        int dot = middle.indexOf('.');
        if (dot <= 0 || dot == middle.length() - 1 || middle.indexOf('.', dot + 1) >= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new int[] {
                    Integer.parseInt(middle.substring(0, dot)),
                    Integer.parseInt(middle.substring(dot + 1))});
        } catch (NumberFormatException notARegion) {
            return Optional.empty();
        }
    }

    /**
     * This save region as a piece-plane region id.
     *
     * <p>Filed under a {@code nodera} namespace whose path names the save tree, so a save region can
     * never collide with a simulated region of the same coordinates — the two grids are different
     * sizes and mean different things, and a manifest has to say which one it carries.
     *
     * @return the id the piece plane files this region's manifests under.
     */
    public RegionId toRegionId() {
        String path = isWorld()
                ? "save/world"
                : "save/" + (dimension.isEmpty() ? "overworld" : dimension.replace('/', '.'));
        return new RegionId(DimensionKey.of(PIECE_NAMESPACE, path),
                isWorld() ? 0 : regionX, isWorld() ? 0 : regionZ);
    }

    @Override
    public String toString() {
        if (isWorld()) {
            return "world";
        }
        return (dimension.isEmpty() ? "overworld" : dimension) + "/" + fileName();
    }
}
