package dev.nodera.simulation.worldgen;

import dev.nodera.core.crypto.StableHash;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.simulation.rules.FlatWorldRules;

/**
 * Deterministic world generation from the seed (Task 16 / L-15): terrain is a PURE FUNCTION of
 * {@code (worldSeed, chunkX, chunkZ)} built entirely on {@link StableHash} integer math — no
 * doubles, no JVM noise libraries, no platform variance (A-5). Every committee member generates
 * the identical column bytes from the seed alone, so a region outside already-generated terrain
 * is GENERABLE on demand instead of non-delegable: the member that needs it derives it.
 *
 * <p><b>Terrain shape (MVP):</b> a smoothed integer heightmap around {@link #BASE_HEIGHT} —
 * per-lattice-point offsets are hash-derived in {@code [-8, 8)} on a 16-block lattice, bilinearly
 * interpolated in integer space — then bedrock-stone-dirt-grass layering. Caves/biomes/features
 * arrive as later increments; the point of THIS one is that the shape is consensus-grade.
 *
 * @Thread-context pure functions; safe from any thread.
 */
public final class WorldGenRules {

    /** The surface sits around this Y (vanilla-ish sea-level plains). */
    public static final int BASE_HEIGHT = 64;
    /** Half-range of the hash-derived lattice offsets (surface varies within ±8). */
    public static final int HEIGHT_VARIATION = 8;
    /** The lattice spacing in blocks (one height sample per chunk corner). */
    public static final int LATTICE = 16;
    /** Fingerprint of THIS generator's semantics — bump on any shape change. */
    public static final long GENERATOR_FINGERPRINT =
            StableHash.of(StableHash.of("nodera.simulation.worldgen.v1"),
                    BASE_HEIGHT, HEIGHT_VARIATION, LATTICE);

    private WorldGenRules() {
    }

    /** The hash-derived height offset at one lattice point, in {@code [-HEIGHT_VARIATION, +HV)}. */
    static int latticeOffset(long worldSeed, int latticeX, int latticeZ) {
        long h = StableHash.of(worldSeed, GENERATOR_FINGERPRINT, latticeX, latticeZ);
        return (int) Math.floorMod(h, 2L * HEIGHT_VARIATION) - HEIGHT_VARIATION;
    }

    /** The deterministic surface height at a world block column (integer bilinear interpolation). */
    public static int surfaceHeight(long worldSeed, int blockX, int blockZ) {
        int lx = Math.floorDiv(blockX, LATTICE);
        int lz = Math.floorDiv(blockZ, LATTICE);
        int fx = Math.floorMod(blockX, LATTICE);
        int fz = Math.floorMod(blockZ, LATTICE);
        int h00 = latticeOffset(worldSeed, lx, lz);
        int h10 = latticeOffset(worldSeed, lx + 1, lz);
        int h01 = latticeOffset(worldSeed, lx, lz + 1);
        int h11 = latticeOffset(worldSeed, lx + 1, lz + 1);
        // Integer bilinear interpolation, scaled by LATTICE² then divided once — order-stable.
        int top = h00 * (LATTICE - fx) + h10 * fx;
        int bottom = h01 * (LATTICE - fx) + h11 * fx;
        int blended = top * (LATTICE - fz) + bottom * fz;
        return BASE_HEIGHT + Math.floorDiv(blended, LATTICE * LATTICE);
    }

    /**
     * Generate one chunk column's canonical state from the seed alone. Layering: bedrock floor
     * (MIN_Y as stone in the MVP palette), stone to {@code surface-4}, dirt to {@code surface-1},
     * grass at the surface, air above.
     */
    public static ChunkColumnState generateColumn(long worldSeed, int chunkX, int chunkZ) {
        int minY = FlatWorldRules.MIN_Y;
        int sectionCount = (FlatWorldRules.MAX_Y + 1 - minY) / 16;
        ChunkColumnState column = uniform(chunkX, chunkZ, FlatWorldRules.AIR, minY, sectionCount);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = chunkX * 16 + x;
                int worldZ = chunkZ * 16 + z;
                int surface = surfaceHeight(worldSeed, worldX, worldZ);
                for (int y = minY; y <= surface; y++) {
                    int block = y >= surface ? FlatWorldRules.GRASS_BLOCK
                            : y >= surface - 3 ? FlatWorldRules.DIRT
                            : FlatWorldRules.STONE;
                    column = column.withBlock(
                            Math.floorDiv(y - minY, 16), x, Math.floorMod(y - minY, 16), z, block);
                }
            }
        }
        return column;
    }

    private static ChunkColumnState uniform(
            int chunkX, int chunkZ, int stateId, int minY, int sectionCount) {
        int[] palette = new int[sectionCount];
        java.util.Arrays.fill(palette, stateId);
        return new ChunkColumnState(chunkX, chunkZ, palette, minY, sectionCount);
    }
}
