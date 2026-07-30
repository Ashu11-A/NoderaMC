package dev.nodera.simulation.lighting;

import dev.nodera.core.state.NBlockPos;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.simulation.rules.FluidRules;

import java.util.ArrayDeque;

/**
 * The Task 14 deterministic lighting lane (L-4): sky and block light as a PURE FUNCTION of
 * committed block state. Light is never stored beside the root — it is derived from it, so
 * replicas agree on every light-dependent decision by construction and there is nothing to
 * drift (the same argument that keeps {@code ExecutionStats} out of the hash).
 *
 * <p><b>Sky light:</b> every cell above its column's highest opaque block reads 15; from
 * there the light propagates through transparent cells with the vanilla asymmetry — straight
 * DOWN from a 15-cell stays 15, every other hop decays by 1.
 *
 * <p><b>Block light:</b> emitters (fire and lava 15, lit redstone torch 7) seed a BFS that
 * decays 1 per hop through transparent cells and stops at opaque ones — light goes AROUND
 * walls, never through them. The visit order is the fixed D-U-N-S-W-E of
 * {@code NeighborUpdateOrder}; with max-wins settling the result is order-independent anyway,
 * but the fixed order keeps even the intermediate states replica-identical.
 *
 * <p><b>Sampling model (bounded):</b> {@link #sample} computes both channels over a
 * {@code ±RANGE} box around the queried cell (15 is the maximum reach of any light source,
 * so the box is exact, not an approximation). The all-open fast path answers sky-lit queries
 * from the column scan alone without running any BFS.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class LightField {

    /** Light values are nibbles: 0..15. */
    public static final int MAX_LIGHT = 15;

    /** The exact reach of any light contribution (level 15 decaying 1 per hop). */
    private static final int RANGE = 15;

    private static final int[][] ORDER = {
            {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    private LightField() {
    }

    /** The light level {@code id} emits (block-light channel). */
    public static int emissionOf(int id) {
        if (id == FlatWorldRules.FIRE || FluidRules.isLava(id)) {
            return 15;
        }
        if (id == FlatWorldRules.TORCH_ON) {
            return 7;
        }
        return 0;
    }

    /**
     * Whether light passes through {@code id}. Opacity is a WHITELIST of full solid cubes;
     * everything else (air, glass, fluids, and every non-solid component — torches, wire,
     * levers, buttons, fire) is transparent, which also lets emitters shine out of their own
     * cell exactly like vanilla's non-solid light sources.
     */
    public static boolean isTransparent(int id) {
        return !isOpaque(id);
    }

    private static boolean isOpaque(int id) {
        return id == FlatWorldRules.STONE || id == FlatWorldRules.DIRT
                || id == FlatWorldRules.GRASS_BLOCK || id == FlatWorldRules.COBBLESTONE
                || id == FlatWorldRules.OAK_PLANKS || id == FlatWorldRules.OAK_LOG
                || id == FlatWorldRules.SAND || id == FlatWorldRules.GRAVEL
                || id == FlatWorldRules.REDSTONE_BLOCK
                || (id >= FlatWorldRules.PISTON_RETRACTED_BASE && id <= FlatWorldRules.PISTON_MAX)
                || (id >= FlatWorldRules.OBSERVER_NORTH_OFF && id <= FlatWorldRules.OBSERVER_EAST_ON);
    }

    /** Combined light at {@code pos}: {@code max(sky, block)} — what mechanics gate on. */
    public static int lightAt(MutableRegionState state, NBlockPos pos) {
        Sample sample = sample(state, pos);
        return Math.max(sample.sky(), sample.block());
    }

    /** One sampled cell: both channels, computed exactly over the bounded box. */
    public record Sample(int sky, int block) {
    }

    /** Compute both light channels at {@code pos} from the committed state around it. */
    public static Sample sample(MutableRegionState state, NBlockPos pos) {
        int id = state.getBlock(pos);
        if (isOpaque(id)) {
            return new Sample(0, 0); // light never enters a full solid cube
        }
        // Fast path: an open column reads full skylight; nothing can exceed 15.
        if (columnOpenAbove(state, pos)) {
            return new Sample(MAX_LIGHT, blockChannel(state, pos));
        }
        return new Sample(skyChannel(state, pos), blockChannel(state, pos));
    }

    private static boolean columnOpenAbove(MutableRegionState state, NBlockPos pos) {
        if (!isTransparent(state.getBlock(pos))) {
            return false;
        }
        for (int y = pos.y() + 1; y <= FlatWorldRules.MAX_Y; y++) {
            if (!isTransparent(state.getBlock(new NBlockPos(pos.x(), y, pos.z())))) {
                return false;
            }
        }
        return true;
    }

    private static int skyChannel(MutableRegionState state, NBlockPos pos) {
        return bfs(state, pos, true);
    }

    private static int blockChannel(MutableRegionState state, NBlockPos pos) {
        return bfs(state, pos, false);
    }

    /**
     * Bounded max-wins BFS over the {@code ±RANGE} box around {@code query}. Sky mode seeds
     * open-column cells at 15 and keeps 15 on straight-down hops; block mode seeds emitters
     * at their emission level (emitters glow even when the block itself is not transparent).
     */
    private static int bfs(MutableRegionState state, NBlockPos query, boolean sky) {
        int size = 2 * RANGE + 1;
        int minX = query.x() - RANGE;
        int minY = Math.max(FlatWorldRules.MIN_Y, query.y() - RANGE);
        int minZ = query.z() - RANGE;
        int maxY = Math.min(FlatWorldRules.MAX_Y, query.y() + RANGE);
        int height = maxY - minY + 1;
        int[] level = new int[size * height * size];
        ArrayDeque<int[]> queue = new ArrayDeque<>();

        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                // One downward column scan seeds the whole (x,z) column: a cell is sky-open
                // when no opaque block sits anywhere above it (including above the box).
                boolean open = true;
                if (sky) {
                    for (int y = FlatWorldRules.MAX_Y; y > maxY; y--) {
                        if (!isTransparent(state.getBlock(
                                new NBlockPos(minX + x, y, minZ + z)))) {
                            open = false;
                            break;
                        }
                    }
                }
                for (int y = height - 1; y >= 0; y--) {
                    NBlockPos cell = new NBlockPos(minX + x, minY + y, minZ + z);
                    int id = state.getBlock(cell);
                    int seed = 0;
                    if (sky) {
                        if (!isTransparent(id)) {
                            open = false;
                        } else if (open) {
                            seed = MAX_LIGHT;
                        }
                    } else {
                        seed = emissionOf(id);
                    }
                    if (seed > 0) {
                        level[(x * height + y) * size + z] = seed;
                        queue.add(new int[]{x, y, z});
                    }
                }
            }
        }

        while (!queue.isEmpty()) {
            int[] cell = queue.removeFirst();
            int current = level[(cell[0] * height + cell[1]) * size + cell[2]];
            for (int[] d : ORDER) {
                int nx = cell[0] + d[0];
                int ny = cell[1] + d[1];
                int nz = cell[2] + d[2];
                if (nx < 0 || nx >= size || ny < 0 || ny >= height || nz < 0 || nz >= size) {
                    continue;
                }
                NBlockPos npos = new NBlockPos(minX + nx, minY + ny, minZ + nz);
                if (!isTransparent(state.getBlock(npos))) {
                    continue; // light goes around walls, never through them
                }
                // Vanilla sky asymmetry: full skylight falls straight down without decay.
                int next = sky && d[1] == -1 && current == MAX_LIGHT
                        ? MAX_LIGHT : current - 1;
                int idx = (nx * height + ny) * size + nz;
                if (next > level[idx]) {
                    level[idx] = next;
                    queue.addLast(new int[]{nx, ny, nz});
                }
            }
        }
        return level[(RANGE * height + (query.y() - minY)) * size + RANGE];
    }
}
