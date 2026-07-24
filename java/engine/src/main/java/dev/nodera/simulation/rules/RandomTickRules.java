package dev.nodera.simulation.rules;

import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;

/**
 * The Task 14 engine-owned random-tick lane (L-1): deterministic replacement for vanilla's
 * per-section random ticks inside delegated regions. Selection and effects draw ONLY from the
 * per-tick {@link DeterministicRandom} (seeded from the committed context — reserved for this
 * purpose since Task 3), iterate owned columns in canonical order, and mutate through the
 * normal per-block path — so three replicas produce identical roots by construction.
 *
 * <p><b>Selection model (vanilla-shaped):</b> {@link #TICKS_PER_SECTION} random positions per
 * eligible 16³ section per region tick (vanilla {@code randomTickSpeed} = 3). A section is
 * eligible when it could possibly react: its uniform id is random-tickable or it is dense.
 * Ineligible sections are skipped WITHOUT consuming randomness — the skip criterion is derived
 * from replicated state, so every replica skips identically.
 *
 * <p><b>Grass semantics (bounded MVP, parity envelope documented on the L-3 row):</b> a
 * selected grass block with a non-air block above dies to dirt; otherwise it attempts ONE
 * spread to a random offset in the 3×3×3 neighborhood — the target converts when it is dirt
 * with air above. Light gates arrive with L-4; cross-border spread is skipped (owned-only)
 * until the halo lane consumes it.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class RandomTickRules {

    /** Random-tick attempts per eligible section per region tick (vanilla randomTickSpeed). */
    public static final int TICKS_PER_SECTION = 3;

    private static final int SECTION = 16;

    private RandomTickRules() {
    }

    /** @return whether {@code id} reacts to random ticks (drives section eligibility). */
    public static boolean isRandomTickable(int id) {
        return id == FlatWorldRules.GRASS_BLOCK;
    }

    /** The per-tick phase: deterministic selection over eligible owned sections. */
    public static void tick(MutableRegionState state, long tick, DeterministicRandom rng) {
        for (ChunkColumnState column : state.ownedColumns()) {
            int[] uniform = column.paletteStateIdsPerSection();
            for (int section = 0; section < uniform.length; section++) {
                if (!sectionEligible(column, section, uniform[section])) {
                    continue; // no randomness consumed: the skip is state-derived
                }
                for (int draw = 0; draw < TICKS_PER_SECTION; draw++) {
                    int x = rng.nextInt(SECTION);
                    int y = rng.nextInt(SECTION);
                    int z = rng.nextInt(SECTION);
                    NBlockPos pos = new NBlockPos(
                            column.chunkX() * SECTION + x,
                            column.minY() + section * SECTION + y,
                            column.chunkZ() * SECTION + z);
                    applyRandomTick(state, pos, rng);
                }
            }
        }
    }

    private static boolean sectionEligible(ChunkColumnState column, int section, int uniformId) {
        if (isRandomTickable(uniformId)) {
            return true;
        }
        for (ChunkColumnState.DenseSection dense : column.denseSections()) {
            if (dense.sectionIndex() == section) {
                return true; // dense sections may contain tickable blocks anywhere
            }
        }
        return false;
    }

    /** Apply one random tick at {@code pos} (package-visible: semantics pin directly). */
    static void applyRandomTick(MutableRegionState state, NBlockPos pos, DeterministicRandom rng) {
        int id = state.getBlock(pos);
        if (id != FlatWorldRules.GRASS_BLOCK) {
            return;
        }
        NBlockPos above = new NBlockPos(pos.x(), pos.y() + 1, pos.z());
        if (pos.y() < FlatWorldRules.MAX_Y && state.getBlock(above) != FlatWorldRules.AIR) {
            state.setBlock(pos, FlatWorldRules.DIRT, null, rng); // smothered grass dies
            return;
        }
        // One spread attempt into the 3×3×3 neighborhood (offsets drawn even when the target
        // is unusable — the draw count must not depend on unreplicated context).
        int dx = rng.nextInt(3) - 1;
        int dy = rng.nextInt(3) - 1;
        int dz = rng.nextInt(3) - 1;
        if (dx == 0 && dy == 0 && dz == 0) {
            return;
        }
        NBlockPos target = new NBlockPos(pos.x() + dx, pos.y() + dy, pos.z() + dz);
        if (!state.inOwnedRegion(target) || target.y() < FlatWorldRules.MIN_Y
                || target.y() >= FlatWorldRules.MAX_Y) {
            return; // cross-border spread waits for the halo lane
        }
        NBlockPos aboveTarget = new NBlockPos(target.x(), target.y() + 1, target.z());
        if (state.getBlock(target) == FlatWorldRules.DIRT
                && state.getBlock(aboveTarget) == FlatWorldRules.AIR) {
            state.setBlock(target, FlatWorldRules.GRASS_BLOCK, null, rng);
        }
    }
}
