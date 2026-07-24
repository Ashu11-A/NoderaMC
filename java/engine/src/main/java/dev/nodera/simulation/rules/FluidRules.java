package dev.nodera.simulation.rules;

import dev.nodera.core.state.NBlockPos;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.border.BorderSignal;

/**
 * The Task 14 finite fluid lane (L-2): water and lava spread as a deterministic PER-CELL
 * automaton riding the T13 hashed scheduled-tick queue — every pending fluid update is
 * consensus state, so in-flight spread survives delta boundaries exactly like piston motion.
 *
 * <p><b>Model (finite, vanilla-shaped, bounded):</b> the fluid level is encoded IN the block
 * id (source = level 0; water flows 1..7, lava flows 1..3). A cell's desired state is a pure
 * function of its neighborhood: a same-family fluid ABOVE makes it a falling level-1 flow;
 * otherwise the best horizontal contribution is {@code min(neighborLevel) + 1} — and a
 * neighbor only contributes horizontally when it SITS ON SOLID (a column of fluid falls, it
 * does not pyramid). A flow whose desired level exceeds the family maximum decays to air, so
 * removing the source drains the network; sources persist until broken. Water outcompetes
 * lava when both contribute (deterministic, documented — interactions like obsidian arrive
 * with a later increment).
 *
 * <p>Updates fire {@code WATER_DELAY}/{@code LAVA_DELAY} ticks after scheduling (vanilla
 * cadence 5/30); spread INTO a cell is realised by scheduling that cell and letting it pull
 * its desired state at fire time. A spread target outside owned bounds becomes a
 * {@link BorderSignal} ({@code Kind.FLUID}) — the engine never mutates halo state.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class FluidRules {

    /** Region ticks between water updates (vanilla flow cadence). */
    public static final int WATER_DELAY = 5;
    /** Region ticks between lava updates (vanilla overworld cadence). */
    public static final int LAVA_DELAY = 30;
    /** Maximum water flow level (7 = the last cell a source reaches on flat ground). */
    public static final int WATER_MAX_FLOW = 7;
    /** Maximum lava flow level (3 — lava dies out faster). */
    public static final int LAVA_MAX_FLOW = 3;

    private FluidRules() {
    }

    /** @return whether {@code id} is any fluid state (either family, source or flow). */
    public static boolean isFluid(int id) {
        return id >= FlatWorldRules.WATER_SOURCE && id <= FlatWorldRules.FLUID_MAX;
    }

    /** @return whether {@code id} belongs to the water family. */
    public static boolean isWater(int id) {
        return id >= FlatWorldRules.WATER_SOURCE && id < FlatWorldRules.LAVA_SOURCE;
    }

    /** @return whether {@code id} belongs to the lava family. */
    public static boolean isLava(int id) {
        return id >= FlatWorldRules.LAVA_SOURCE && id <= FlatWorldRules.FLUID_MAX;
    }

    /** The fluid level: 0 for a source, 1..max for flows; only valid when {@link #isFluid}. */
    public static int levelOf(int id) {
        if (id == FlatWorldRules.WATER_SOURCE || id == FlatWorldRules.LAVA_SOURCE) {
            return 0;
        }
        return isWater(id)
                ? id - FlatWorldRules.WATER_FLOW_BASE + 1
                : id - FlatWorldRules.LAVA_FLOW_BASE + 1;
    }

    private static int flowId(boolean water, int level) {
        return water
                ? FlatWorldRules.WATER_FLOW_BASE + level - 1
                : FlatWorldRules.LAVA_FLOW_BASE + level - 1;
    }

    private static int delayOf(boolean water) {
        return water ? WATER_DELAY : LAVA_DELAY;
    }

    private static int maxFlowOf(boolean water) {
        return water ? WATER_MAX_FLOW : LAVA_MAX_FLOW;
    }

    /**
     * Post-mutation hook (place/break arms): schedule updates for every fluid in the changed
     * cell's neighborhood — a placed source starts its own clock, a broken dam wakes the
     * flows that will now advance or decay.
     */
    public static void onChanged(MutableRegionState state, NBlockPos pos, long currentTick) {
        maybeSchedule(state, pos, currentTick);
        for (NBlockPos n : dev.nodera.simulation.NeighborUpdateOrder.neighborsOf(pos)) {
            maybeSchedule(state, n, currentTick);
        }
    }

    private static void maybeSchedule(MutableRegionState state, NBlockPos pos, long currentTick) {
        if (!state.inOwnedRegion(pos)) {
            return;
        }
        int id = state.getBlock(pos);
        if (isFluid(id)) {
            schedule(state, pos, currentTick, isWater(id));
        }
    }

    /** Schedule one fluid update for {@code pos} (deduped through the hashed queue). */
    private static void schedule(MutableRegionState state, NBlockPos pos,
                                 long currentTick, boolean water) {
        if (!state.inOwnedRegion(pos)) {
            return;
        }
        boolean alreadyScheduled = state.scheduledTicks().stream()
                .anyMatch(entry -> entry.pos().equals(pos));
        if (!alreadyScheduled) {
            state.scheduleTick(pos, state.getBlock(pos), currentTick + delayOf(water), 0);
        }
    }

    /**
     * Fire one fluid update at {@code pos} (the scheduled-tick catch-all dispatch): pull the
     * cell's desired state, apply it, and wake whoever the change affects. Solid cells no-op
     * (stale entries are harmless).
     */
    public static void update(MutableRegionState state, NBlockPos pos,
                              long tick, DeterministicRandom rng) {
        int current = state.getBlock(pos);
        if (current != FlatWorldRules.AIR && !isFluid(current)) {
            return; // a solid block arrived since scheduling: stale entry no-ops
        }
        int desired = desiredAt(state, pos, current);
        if (desired != current) {
            state.setBlock(pos, desired, null, rng);
            boolean water = isFluid(desired) ? isWater(desired)
                    : isFluid(current) && isWater(current);
            for (NBlockPos n : dev.nodera.simulation.NeighborUpdateOrder.neighborsOf(pos)) {
                if (state.inOwnedRegion(n)
                        && (isFluid(state.getBlock(n)) || state.getBlock(n) == FlatWorldRules.AIR)) {
                    if (isFluid(state.getBlock(n))) {
                        schedule(state, n, tick, isWater(state.getBlock(n)));
                    }
                }
            }
        }
        // Whether or not this cell changed, an active fluid here pushes its frontier: the
        // cell below (falling) and, when sitting on solid, the four horizontal targets.
        int now = state.getBlock(pos);
        if (isFluid(now)) {
            boolean water = isWater(now);
            NBlockPos below = new NBlockPos(pos.x(), pos.y() - 1, pos.z());
            if (below.y() >= FlatWorldRules.MIN_Y) {
                scheduleSpreadTarget(state, below, pos, tick, water);
            }
            if (sitsOnSolid(state, pos) && levelOf(now) < maxFlowOf(water)) {
                for (NBlockPos n : horizontalNeighbors(pos)) {
                    scheduleSpreadTarget(state, n, pos, tick, water);
                }
            }
        }
    }

    private static void scheduleSpreadTarget(MutableRegionState state, NBlockPos target,
                                             NBlockPos from, long tick, boolean water) {
        if (!state.inOwnedRegion(target)) {
            state.emitBorderSignal(new BorderSignal(
                    BorderSignal.Kind.FLUID, from, target, tick));
            return; // the engine never mutates halo state
        }
        int id = state.getBlock(target);
        if (id == FlatWorldRules.AIR
                && desiredAt(state, target, FlatWorldRules.AIR) != FlatWorldRules.AIR) {
            boolean alreadyScheduled = state.scheduledTicks().stream()
                    .anyMatch(entry -> entry.pos().equals(target));
            if (!alreadyScheduled) {
                state.scheduleTick(target, id, tick + delayOf(water), 0);
            }
        }
    }

    /** The cell's settled fluid state as a pure function of its neighborhood. */
    static int desiredAt(MutableRegionState state, NBlockPos pos, int current) {
        if (current == FlatWorldRules.WATER_SOURCE || current == FlatWorldRules.LAVA_SOURCE) {
            return current; // sources persist until broken
        }
        // Falling: a same-family fluid directly above feeds a level-1 flow here.
        boolean waterAbove = false;
        boolean lavaAbove = false;
        if (pos.y() < FlatWorldRules.MAX_Y) {
            int above = state.getBlock(new NBlockPos(pos.x(), pos.y() + 1, pos.z()));
            waterAbove = isWater(above);
            lavaAbove = isLava(above);
        }
        // Horizontal: the strongest neighbor SITTING ON SOLID contributes level+1.
        int bestWater = waterAbove ? 1 : Integer.MAX_VALUE;
        int bestLava = lavaAbove ? 1 : Integer.MAX_VALUE;
        for (NBlockPos n : horizontalNeighbors(pos)) {
            int id = state.getBlock(n);
            if (!isFluid(id) || !sitsOnSolid(state, n)) {
                continue;
            }
            int contribution = levelOf(id) + 1;
            if (isWater(id)) {
                bestWater = Math.min(bestWater, contribution);
            } else {
                bestLava = Math.min(bestLava, contribution);
            }
        }
        // Water outcompetes lava when both reach the cell (deterministic MVP rule).
        if (bestWater <= WATER_MAX_FLOW) {
            return flowId(true, bestWater);
        }
        if (bestLava <= LAVA_MAX_FLOW) {
            return flowId(false, bestLava);
        }
        return FlatWorldRules.AIR; // no support: flows decay, air stays air
    }

    /** A fluid spreads horizontally only when it rests on something solid (no pyramids). */
    private static boolean sitsOnSolid(MutableRegionState state, NBlockPos pos) {
        if (pos.y() <= FlatWorldRules.MIN_Y) {
            return true; // the world floor counts as support
        }
        int below = state.getBlock(new NBlockPos(pos.x(), pos.y() - 1, pos.z()));
        return below != FlatWorldRules.AIR && !isFluid(below);
    }

    private static NBlockPos[] horizontalNeighbors(NBlockPos pos) {
        return new NBlockPos[]{
                new NBlockPos(pos.x(), pos.y(), pos.z() - 1),
                new NBlockPos(pos.x(), pos.y(), pos.z() + 1),
                new NBlockPos(pos.x() - 1, pos.y(), pos.z()),
                new NBlockPos(pos.x() + 1, pos.y(), pos.z())
        };
    }
}
