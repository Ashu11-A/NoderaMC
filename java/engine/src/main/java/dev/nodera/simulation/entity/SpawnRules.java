package dev.nodera.simulation.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.lighting.LightField;
import dev.nodera.simulation.rules.FlatWorldRules;

/**
 * The Task 15 deterministic spawn lane (L-8): engine-owned hostile spawn cycles replacing
 * vanilla's for delegated regions. Every decision draws from the per-tick
 * {@link DeterministicRandom} and gates on {@link LightField} — the committed state IS the
 * spawn condition, so three replicas produce the identical mob population by construction.
 *
 * <p><b>Cycle (vanilla-shaped, bounded):</b> every {@link #SPAWN_INTERVAL_TICKS} region ticks
 * one attempt runs: a random owned column and a ground scan pick the candidate cell (solid
 * floor, two transparent cells of headroom); the attempt succeeds when the region's ghost-mob
 * population is under {@link #MOB_CAP} and the cell's combined light is below
 * {@link #MAX_SPAWN_LIGHT}. Spawned mobs enter the root as {@link EntityKind#GHOST} entities
 * — the live lane mirrors them into vanilla view exactly like captured mobs, and the vanilla
 * rate envelope is matched by interval x cap rather than per-chunk density heuristics
 * (documented envelope).
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class SpawnRules {

    /** Region ticks between spawn attempts (interval × cap ≈ the vanilla rate envelope). */
    public static final int SPAWN_INTERVAL_TICKS = 20;
    /** Maximum engine-spawned ghost mobs alive per region. */
    public static final int MOB_CAP = 8;
    /** Hostile spawns need combined light BELOW this (classic hostile rule). */
    public static final int MAX_SPAWN_LIGHT = 8;
    /**
     * The spawned species' type id — the vanilla network id for ZOMBIE is mapped by the mod
     * when mirroring ghosts; the engine only commits to the number.
     */
    public static final int ZOMBIE_TYPE_ID = 54;
    /** Engine-spawned mobs despawn after this many ticks without the entity lane claiming them. */
    public static final int DESPAWN_AFTER_TICKS = 6000;

    /** High tag bits marking the spawn allocation domain (never collides with action seqs). */
    private static final long SPAWN_SEQ_DOMAIN = 0x5350_4157L << 32;

    private SpawnRules() {
    }

    /** The per-tick phase: at the interval boundary, run one deterministic spawn attempt. */
    public static void tick(MutableRegionState state, long tick, DeterministicRandom rng) {
        if (tick % SPAWN_INTERVAL_TICKS != 0) {
            return;
        }
        long ghosts = state.entities().stream()
                .filter(e -> e.kind() == EntityKind.GHOST)
                .count();
        if (ghosts >= MOB_CAP) {
            return; // cap reached: population state is replicated, so replicas agree
        }
        // One attempt: a random owned column, then a downward ground scan.
        int originX = state.region().originChunkX() * 16;
        int originZ = state.region().originChunkZ() * 16;
        int x = originX + rng.nextInt(128);
        int z = originZ + rng.nextInt(128);
        java.util.List<NBlockPos> stands = standingCells(state, x, z);
        if (stands.isEmpty()) {
            return;
        }
        // Vanilla draws a random height; we draw among the column's actual stands (roof AND
        // cave floor are both candidates), which keeps the attempt useful without changing
        // the deterministic draw structure.
        NBlockPos cell = stands.get(rng.nextInt(stands.size()));
        if (LightField.lightAt(state, cell) >= MAX_SPAWN_LIGHT) {
            return; // hostile spawns want darkness — the light IS committed state
        }
        NetworkEntityId id = NetworkEntityId.allocate(
                state.region(), state.baseVersion(), SPAWN_SEQ_DOMAIN | tick);
        state.createEntity(new PersistedEntityState(
                id, EntityKind.GHOST, ZOMBIE_TYPE_ID,
                FixedVec3.fromExternal(x + 0.5, cell.y(), z + 0.5),
                FixedVec3.ZERO, 0, (int) (tick + DESPAWN_AFTER_TICKS), Bytes.empty()));
    }

    /** Every cell in the column with a solid floor and two air cells of headroom (top-down). */
    private static java.util.List<NBlockPos> standingCells(
            MutableRegionState state, int x, int z) {
        java.util.List<NBlockPos> stands = new java.util.ArrayList<>();
        int above = state.getBlock(new NBlockPos(x, FlatWorldRules.MAX_Y, z));
        int cell = state.getBlock(new NBlockPos(x, FlatWorldRules.MAX_Y - 1, z));
        for (int y = FlatWorldRules.MAX_Y - 1; y > FlatWorldRules.MIN_Y; y--) {
            int floor = state.getBlock(new NBlockPos(x, y - 1, z));
            if (!LightField.isTransparent(floor) && cell == FlatWorldRules.AIR
                    && above == FlatWorldRules.AIR) {
                stands.add(new NBlockPos(x, y, z));
            }
            above = cell;
            cell = floor;
        }
        return stands;
    }
}
