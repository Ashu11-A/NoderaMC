package dev.nodera.simulation.entity;

import dev.nodera.core.crypto.StableHash;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.lighting.LightField;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Task 11 deterministic mob-AI lane (L-7): engine-driven behavior for {@code GHOST} and
 * engine-owned {@code MOB} entities, the first step of per-species ghost retirement. Once a
 * species' behavior comes from HERE — seeded draws over replicated state — the live lane stops
 * mirroring the server's mob and starts mirroring the ROOT's, and the species' ghost-share
 * drops to zero ({@code MOB} entities additionally carry validated vitals, L-13).
 *
 * <p><b>Two behaviours, because there are two kinds of mob.</b>
 *
 * <ul>
 *   <li>An engine-owned {@link EntityKind#MOB} carries a {@link MobState} in the root, so it can
 *       <i>remember</i>. Every {@link #AI_INTERVAL_TICKS} region ticks it either adopts a wander
 *       goal — a destination up to {@link #WANDER_RADIUS} blocks away — or advances along one it
 *       already holds, one block per decision, using {@link IntPathfinder} to choose the step.
 *       A mob therefore crosses a room instead of jittering in place, and it routes <i>around</i>
 *       an obstacle rather than idling in front of it.</li>
 *   <li>A {@link EntityKind#GHOST} is a mirror of a server-owned mob and its payload belongs to
 *       the mirroring lane, not to the engine. It keeps the memoryless one-block wander: the
 *       engine will not invent an intention for an entity whose authority is elsewhere. Giving a
 *       species memory is exactly what retiring it from GHOST to MOB means.</li>
 * </ul>
 *
 * <p><b>A fixed draw count per decision.</b> An engine-owned MOB draws exactly
 * {@link #DRAWS_PER_DECISION} values from the per-tick {@link DeterministicRandom} at every
 * decision opportunity and discards what the branch it took did not need; a GHOST draws exactly
 * one. The count varies with {@link PersistedEntityState#kind()} and with nothing else — kind is
 * hashed root state, so two replicas holding the same root necessarily draw the same number of
 * times in the same order. What is forbidden is a count that varies with the branch a mob
 * <i>chose</i>: a mob that drew once when idling and twice when adopting a goal would make every
 * LATER mob's draws depend on the earlier mob's branch, so a single one-block state difference
 * between two replicas would not stay one block — it would re-randomise the whole region. Mobs are
 * processed in canonical entity-id order for the same reason.
 *
 * <p>Mobs past their despawn horizon are removed every tick (the population breathes exactly like
 * vanilla's despawn cycle, deterministically). Targeting and combat goals arrive with later
 * increments; see {@code docs/engine/Task.11.md} §"The remaining lane".
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class MobAiRules {

    /** Region ticks between AI decisions (vanilla-ish idle cadence, cheap and visible). */
    public static final int AI_INTERVAL_TICKS = 10;
    /** Draws an engine-owned MOB takes per decision opportunity, whatever it decides. */
    public static final int DRAWS_PER_DECISION = 2;
    /** Horizontal reach of a wander destination, in blocks. */
    public static final int WANDER_RADIUS = 6;
    /** How far above/below its own stance a mob will accept a destination. */
    public static final int WANDER_CLIMB = 3;
    /** Region ticks a wander goal stays live before it is abandoned. */
    public static final int WANDER_BUDGET_TICKS = 200;
    /** Weight of "stay put" in the goal draw, out of {@link #GOAL_DRAW_RANGE}. */
    public static final int IDLE_WEIGHT = 3;
    /** Denominator of the goal draw (idle 3/8, wander 5/8 — the pre-memory idle rate). */
    public static final int GOAL_DRAW_RANGE = 8;
    /** Per-tick retention of an imparted (knockback) velocity, Q32.32 literal (0.9 — mobs settle). */
    public static final long KNOCKBACK_FRICTION = 3_865_470_566L;
    /** Below this per-axis speed an imparted velocity is treated as settled (Q32.32; 0.125 block/tick). */
    public static final long KNOCKBACK_SETTLE = FixedVec3.ONE / 8;

    /**
     * Candidate destination offsets, in fixed order: the four cardinals then the four diagonals,
     * each scaled by {@link #WANDER_RADIUS}. A table rather than trigonometry — the offsets have to
     * be integers anyway, and a table is auditable. Note the diagonals land at
     * {@code radius × √2} rather than at {@code radius}; the destination only has to be *somewhere
     * plausible*, and normalising it would need either a square root or a second table of
     * per-direction lengths, neither of which buys anything a mob can perceive.
     */
    private static final int[] WANDER_DX = {0, 0, -1, 1, -1, 1, -1, 1};
    private static final int[] WANDER_DZ = {-1, 1, 0, 0, -1, -1, 1, 1};

    /** Height offsets tried when snapping a destination column to a stance, nearest first. */
    private static final int[] SNAP_DY = {0, -1, 1, -2, 2, -3, 3};

    private MobAiRules() {
    }

    /**
     * Fingerprint contribution pinning the AI constants and the {@link MobState} payload shape.
     * Mixed into {@link dev.nodera.simulation.rules.FlatWorldRules#registryFingerprint()}, so two
     * builds whose mobs would decide differently — or whose mob payload has a different shape —
     * refuse to validate for each other instead of diverging.
     */
    public static long semanticFingerprint() {
        return StableHash.of(
                StableHash.of("nodera.simulation.mob-ai-rules.v2-ai-memory"),
                AI_INTERVAL_TICKS,
                DRAWS_PER_DECISION,
                WANDER_RADIUS,
                WANDER_CLIMB,
                WANDER_BUDGET_TICKS,
                IDLE_WEIGHT,
                GOAL_DRAW_RANGE,
                IntPathfinder.DEFAULT_NODE_BUDGET,
                MobState.ENCODED_SIZE,
                MobCombatRules.semanticFingerprint());
    }

    /** The per-tick phase: despawn expiries every tick, one decision per mob per interval. */
    public static void tick(MutableRegionState state, long tick, DeterministicRandom rng) {
        // Snapshot first: mutations during iteration must not affect the pass.
        List<PersistedEntityState> ghosts = new ArrayList<>();
        for (PersistedEntityState entity : state.entities()) {
            if (entity.kind() == EntityKind.GHOST || entity.kind() == EntityKind.MOB) {
                ghosts.add(entity);
            }
        }
        for (PersistedEntityState ghost : ghosts) {
            if (ghost.despawnTick() != PersistedEntityState.NEVER_DESPAWN
                    && tick >= Integer.toUnsignedLong(ghost.despawnTick())) {
                state.removeEntity(ghost.id());
                continue;
            }
            if (!ghost.vel().equals(FixedVec3.ZERO)) {
                applyKnockback(state, ghost);
                continue;
            }
            if (tick % AI_INTERVAL_TICKS != 0) {
                continue;
            }
            if (ghost.kind() == EntityKind.MOB) {
                decide(state, ghost, tick, rng);
            } else {
                wanderStep(state, ghost, rng);
            }
        }
    }

    /**
     * Consume an imparted velocity (a TNT blast's knockback): translate the mob by its velocity,
     * decay the velocity, and stop at a wall or once it has settled — then wander resumes. Pure
     * fixed-point; mobs are not otherwise kinematic (wander teleports), so this is the one path
     * that reads velocity.
     */
    private static void applyKnockback(MutableRegionState state, PersistedEntityState ghost) {
        if (settled(ghost.vel())) {
            state.updateEntity(ghost.withMotion(ghost.pos(), FixedVec3.ZERO));
            return;
        }
        FixedVec3 target = ghost.pos().add(ghost.vel());
        NBlockPos cell = new NBlockPos(target.blockX(), target.blockY(), target.blockZ());
        if (!state.inOwnedRegion(cell)) {
            state.transferEntity(dev.nodera.core.region.RegionId.fromChunk(
                            state.region().dimension(),
                            Math.floorDiv(target.blockX(), 16),
                            Math.floorDiv(target.blockZ(), 16)),
                    ghost.withMotion(target, ghost.vel().scale(KNOCKBACK_FRICTION)));
            return;
        }
        if (!LightField.isTransparent(state.getBlock(cell))) {
            // Ran into an opaque block: stop dead where it is.
            state.updateEntity(ghost.withMotion(ghost.pos(), FixedVec3.ZERO));
            return;
        }
        state.updateEntity(ghost.withMotion(target, ghost.vel().scale(KNOCKBACK_FRICTION)));
    }

    /** True when every axis of an imparted velocity has decayed below the settle threshold. */
    private static boolean settled(FixedVec3 vel) {
        return Math.abs(vel.x()) <= KNOCKBACK_SETTLE
                && Math.abs(vel.y()) <= KNOCKBACK_SETTLE
                && Math.abs(vel.z()) <= KNOCKBACK_SETTLE;
    }

    /**
     * One decision for an engine-owned mob: adopt a goal if it holds none, then advance along
     * whatever goal it holds. Both draws are taken up front, unconditionally — see the class
     * comment on the fixed draw count.
     */
    private static void decide(MutableRegionState state, PersistedEntityState mob,
                               long tick, DeterministicRandom rng) {
        int goalDraw = rng.nextInt(GOAL_DRAW_RANGE);
        int directionDraw = rng.nextInt(WANDER_DX.length);

        MobState decoded = MobState.decode(mob.payload());
        MobState.AiMemory memory = decoded.ai();
        NBlockPos here = new NBlockPos(
                mob.pos().blockX(), mob.pos().blockY(), mob.pos().blockZ());

        if (!memory.isLiveAt(tick)) {
            memory = adopt(state, here, tick, goalDraw, directionDraw);
        }
        if (memory.goal() == MobState.GOAL_NONE) {
            commit(state, mob, decoded.withAi(memory), null);
            return;
        }
        if (here.equals(memory.destination())) {
            // Arrived: drop the goal so the next decision draws a fresh one.
            commit(state, mob, decoded.withAi(MobState.AiMemory.IDLE), null);
            return;
        }
        Optional<NBlockPos> step = IntPathfinder.firstStep(
                state, here, memory.destination(), IntPathfinder.DEFAULT_NODE_BUDGET);
        if (step.isEmpty()) {
            // Nowhere to go from here (walled in, budget spent, or the destination stopped being
            // a stance): abandon rather than keep re-searching the same dead end every interval.
            commit(state, mob, decoded.withAi(MobState.AiMemory.IDLE), null);
            return;
        }
        NBlockPos landed = step.get();
        MobState.AiMemory reached = landed.equals(memory.destination())
                ? MobState.AiMemory.IDLE : memory;
        commit(state, mob, decoded.withAi(reached), landed);
    }

    /**
     * Choose this mob's next intention from the two draws already taken. Idle
     * {@link #IDLE_WEIGHT}/{@link #GOAL_DRAW_RANGE} of the time; otherwise walk towards one of the
     * eight compass offsets, snapped to the nearest legal stance in that column. A destination
     * that is not a stance is simply not adopted — a goal the mob cannot reach is a goal it would
     * spend every future decision failing at.
     */
    private static MobState.AiMemory adopt(MutableRegionState state, NBlockPos here,
                                           long tick, int goalDraw, int directionDraw) {
        if (goalDraw < IDLE_WEIGHT) {
            return MobState.AiMemory.IDLE;
        }
        int x = here.x() + WANDER_DX[directionDraw] * WANDER_RADIUS;
        int z = here.z() + WANDER_DZ[directionDraw] * WANDER_RADIUS;
        for (int dy : SNAP_DY) {
            if (Math.abs(dy) > WANDER_CLIMB) {
                continue;
            }
            NBlockPos candidate = new NBlockPos(x, here.y() + dy, z);
            if (state.inOwnedRegion(candidate) && IntPathfinder.isWalkable(state, candidate)) {
                return MobState.AiMemory.wanderTo(candidate, tick + WANDER_BUDGET_TICKS);
            }
        }
        return MobState.AiMemory.IDLE;
    }

    /**
     * Write one decision back into the root: the mob's new memory, and — when it moved — its new
     * block-centred position and advanced age. One update per decision, so the delta carries one
     * entity revision rather than two.
     *
     * @param landed the stance stepped onto, or {@code null} when the mob stayed put.
     */
    private static void commit(MutableRegionState state, PersistedEntityState mob,
                               MobState updated, NBlockPos landed) {
        if (landed == null) {
            state.updateEntity(new PersistedEntityState(
                    mob.id(), mob.kind(), mob.typeId(), mob.pos(), mob.vel(),
                    mob.ageTicks(), mob.despawnTick(), updated.encode()));
            return;
        }
        // Half-block-centred fixed position, pure integer math (no double round-trip — the
        // determinism rule: every coordinate read is the integer block shift).
        FixedVec3 position = new FixedVec3(
                ((long) landed.x() << 32) + (1L << 31),
                (long) landed.y() << 32,
                ((long) landed.z() << 32) + (1L << 31));
        state.updateEntity(new PersistedEntityState(
                mob.id(), mob.kind(), mob.typeId(), position, FixedVec3.ZERO,
                mob.ageTicks() + AI_INTERVAL_TICKS, mob.despawnTick(), updated.encode()));
    }

    /**
     * The memoryless step a GHOST takes: one draw for the whole decision — idle
     * {@link #IDLE_WEIGHT}/{@link #GOAL_DRAW_RANGE}, otherwise one block in one of four
     * directions onto the first walkable of {level, climb one, drop one}. A ghost's payload is
     * the mirroring lane's, so there is nowhere to keep an intention and none is invented.
     */
    private static void wanderStep(MutableRegionState state, PersistedEntityState ghost,
                                   DeterministicRandom rng) {
        int decision = rng.nextInt(GOAL_DRAW_RANGE);
        if (decision < IDLE_WEIGHT) {
            return; // idle — but the draw happened, so the stream stays aligned
        }
        int dir = (decision - IDLE_WEIGHT) & 3; // fixed draw count per decision
        int tx = ghost.pos().blockX() + IntPathfinder.STEP_DX[dir];
        int tz = ghost.pos().blockZ() + IntPathfinder.STEP_DZ[dir];
        int y = ghost.pos().blockY();
        // Try the same level, one up (climb), one down (drop) — first walkable wins.
        for (int dy : new int[]{0, 1, -1}) {
            NBlockPos cell = new NBlockPos(tx, y + dy, tz);
            if (state.inOwnedRegion(cell) && IntPathfinder.isWalkable(state, cell)) {
                FixedVec3 landed = new FixedVec3(
                        ((long) tx << 32) + (1L << 31),
                        (long) cell.y() << 32,
                        ((long) tz << 32) + (1L << 31));
                state.updateEntity(ghost.withMotionAndAge(
                        landed, FixedVec3.ZERO, ghost.ageTicks() + AI_INTERVAL_TICKS));
                return;
            }
        }
        // No walkable target: idle in place (region borders fail closed like everything else).
    }
}
