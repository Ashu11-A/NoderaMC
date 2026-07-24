package dev.nodera.simulation.entity;

import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.lighting.LightField;
import dev.nodera.simulation.rules.FlatWorldRules;

import java.util.ArrayList;
import java.util.List;

/**
 * The Task 15 deterministic mob-AI lane (L-7): engine-driven behavior for {@code GHOST} mobs,
 * the first step of per-species ghost retirement. Once a species' behavior comes from HERE —
 * seeded draws over replicated state — the live lane stops mirroring the server's mob and
 * starts mirroring the ROOT's, and the species' ghost-share drops to zero.
 *
 * <p><b>Behavior (bounded wander MVP):</b> every {@link #AI_INTERVAL_TICKS} region ticks each
 * ghost draws one decision in canonical id order: idle (3/8), or a one-block step in one of
 * the four horizontal directions (mirroring the vanilla zombie's aimless idle wander). A step
 * lands only on a WALKABLE cell — solid floor, two air of headroom, and a climb/drop of at
 * most one block — otherwise the mob idles; positions stay block-centred, so the root always
 * holds a legal stance. Mobs past their despawn horizon are removed (the population breathes
 * exactly like vanilla's despawn cycle, deterministically).
 *
 * <p>Pathfinding, targeting, and combat arrive with later increments; the point of THIS one
 * is that mob state changes now originate in the validated engine, not in vanilla.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class MobAiRules {

    /** Region ticks between AI decisions (vanilla-ish idle cadence, cheap and visible). */
    public static final int AI_INTERVAL_TICKS = 10;

    private static final int[] STEP_DX = {0, 0, -1, 1};
    private static final int[] STEP_DZ = {-1, 1, 0, 0};

    private MobAiRules() {
    }

    /** The per-tick phase: despawn expiries every tick, one decision per ghost per interval. */
    public static void tick(MutableRegionState state, long tick, DeterministicRandom rng) {
        // Snapshot first: mutations during iteration must not affect the pass.
        List<PersistedEntityState> ghosts = new ArrayList<>();
        for (PersistedEntityState entity : state.entities()) {
            if (entity.kind() == EntityKind.GHOST) {
                ghosts.add(entity);
            }
        }
        for (PersistedEntityState ghost : ghosts) {
            if (ghost.despawnTick() != PersistedEntityState.NEVER_DESPAWN
                    && tick >= Integer.toUnsignedLong(ghost.despawnTick())) {
                state.removeEntity(ghost.id());
                continue;
            }
            if (tick % AI_INTERVAL_TICKS != 0) {
                continue;
            }
            wanderStep(state, ghost, tick, rng);
        }
    }

    private static void wanderStep(MutableRegionState state, PersistedEntityState ghost,
                                   long tick, DeterministicRandom rng) {
        int decision = rng.nextInt(8);
        if (decision < 3) {
            return; // idle — but the draw happened, so the stream stays aligned
        }
        int dir = (decision - 3) & 3; // fixed draw count per decision — no branch-dependent rng
        int x = (int) Math.floor(FixedVec3.toExternal(ghost.pos().x()));
        int y = (int) Math.floor(FixedVec3.toExternal(ghost.pos().y()));
        int z = (int) Math.floor(FixedVec3.toExternal(ghost.pos().z()));
        int tx = x + STEP_DX[dir];
        int tz = z + STEP_DZ[dir];
        // Try the same level, one up (climb), one down (drop) — first walkable wins.
        for (int dy : new int[]{0, 1, -1}) {
            NBlockPos cell = new NBlockPos(tx, y + dy, tz);
            if (state.inOwnedRegion(cell) && isWalkable(state, cell)) {
                PersistedEntityState moved = new PersistedEntityState(
                        ghost.id(), ghost.kind(), ghost.typeId(),
                        FixedVec3.fromExternal(tx + 0.5, cell.y(), tz + 0.5),
                        FixedVec3.ZERO,
                        ghost.ageTicks() + AI_INTERVAL_TICKS,
                        ghost.despawnTick(), ghost.payload());
                state.updateEntity(moved);
                return;
            }
        }
        // No walkable target: idle in place (region borders fail closed like everything else).
    }

    /** Walkable: solid floor below, the cell and the one above are air. */
    static boolean isWalkable(MutableRegionState state, NBlockPos cell) {
        if (cell.y() <= FlatWorldRules.MIN_Y || cell.y() >= FlatWorldRules.MAX_Y) {
            return false;
        }
        int floor = state.getBlock(new NBlockPos(cell.x(), cell.y() - 1, cell.z()));
        return !LightField.isTransparent(floor)
                && state.getBlock(cell) == FlatWorldRules.AIR
                && state.getBlock(new NBlockPos(cell.x(), cell.y() + 1, cell.z()))
                == FlatWorldRules.AIR;
    }
}
