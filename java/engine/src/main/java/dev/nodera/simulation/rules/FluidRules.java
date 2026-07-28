package dev.nodera.simulation.rules;

import dev.nodera.core.region.RegionBounds;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.border.BorderSignal;

import java.util.HashSet;
import java.util.Set;

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

    private enum BoundaryFace {
        EAST(15, -1, 1, 0),
        WEST(0, -1, -1, 0),
        SOUTH(-1, 15, 0, 1),
        NORTH(-1, 0, 0, -1);

        private final int fixedX;
        private final int fixedZ;
        private final int stepX;
        private final int stepZ;

        BoundaryFace(int fixedX, int fixedZ, int stepX, int stepZ) {
            this.fixedX = fixedX;
            this.fixedZ = fixedZ;
            this.stepX = stepX;
            this.stepZ = stepZ;
        }

        int localX(int offset) {
            return fixedX >= 0 ? fixedX : offset;
        }

        int localZ(int offset) {
            return fixedZ >= 0 ? fixedZ : offset;
        }
    }

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
        // L-2 fluid interactions come FIRST: a lava cell that water has reached stops being a
        // fluid entirely, so computing its desired fluid state would be answering the wrong
        // question. The outcome is a pure function of the neighbourhood, like everything else here.
        int solidified = solidification(state, pos, current);
        if (solidified != FlatWorldRules.AIR) {
            state.setBlock(pos, solidified, null, rng);
            RedstoneRules.observersOnChange(state, pos, tick);
            for (NBlockPos n : dev.nodera.simulation.NeighborUpdateOrder.neighborsOf(pos)) {
                if (state.inOwnedRegion(n) && isFluid(state.getBlock(n))) {
                    schedule(state, n, tick, isWater(state.getBlock(n)));
                }
            }
            return;
        }
        int desired = desiredAt(state, pos, current);
        if (desired != current) {
            state.setBlock(pos, desired, null, rng);
            RedstoneRules.observersOnChange(state, pos, tick);
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

    /**
     * Schedule the owned cells a neighbour's fluid is about to flow into (engine L-2, the
     * cross-region half).
     *
     * <p>A fluid reaching a region edge emits a {@code BorderSignal} and stops, because the engine
     * never mutates halo. The receiving side is what was missing: its own automaton would flow
     * correctly, but nothing ever scheduled a tick at the cell to make it look. Nothing did, because
     * with an empty halo that cell reads air next to air and there is genuinely nothing to do.
     *
     * <p>With a backed halo the answer is derivable from the request alone — no message, no
     * delivery, nothing for two replicas to disagree about: walk the halo columns this batch was
     * given, and wherever one holds fluid, schedule the adjacent owned cell to re-evaluate. The
     * cell then decides for itself through {@link #desiredAt}, exactly as an interior cell does, so
     * a neighbour can cause a look but never dictate a result.
     *
     * <p>Walking the halo rather than the owned boundary is deliberate. Only the one 16×16 face of
     * each side-adjacent halo column can feed an owned cell; diagonal columns are skipped. Work is
     * therefore bounded to 256 candidates per relevant section, in canonical column/section/y/offset
     * order. Existing scheduled positions are indexed once before that walk, so dedupe never copies
     * and sorts the hashed queue per candidate. An empty halo still costs one branch.
     *
     * @param state the working state, whose halo carries the neighbour slices.
     * @param tick  the tick the inflow is scheduled from.
     * @return how many owned cells were scheduled.
     */
    public static int seedBorderInflow(MutableRegionState state, long tick) {
        dev.nodera.simulation.border.RegionHalo halo = state.halo();
        if (halo == null || halo.isEmpty()) {
            return 0;
        }
        Set<NBlockPos> scheduledPositions = new HashSet<>();
        for (var entry : state.scheduledTicks()) {
            scheduledPositions.add(entry.pos());
        }
        RegionBounds bounds = state.bounds();
        int scheduled = 0;
        for (ChunkColumnState column : halo.backing()) {
            BoundaryFace face = ownershipFacingFace(bounds, column);
            if (face == null) {
                continue; // diagonal and non-adjacent halo columns cannot feed an owned cell
            }
            int[] uniform = column.paletteStateIdsPerSection();
            var denseSections = column.denseSections();
            int denseIndex = 0;
            for (int section = 0; section < column.sectionCount(); section++) {
                ChunkColumnState.DenseSection dense = denseIndex < denseSections.size()
                        && denseSections.get(denseIndex).sectionIndex() == section
                        ? denseSections.get(denseIndex++) : null;
                int uniformId = uniform[section];
                if (dense == null && !isFluid(uniformId)) {
                    continue;
                }
                int[] blocks = dense == null ? null : dense.blocks();
                int sectionY = column.minY() + section * 16;
                for (int localY = 0; localY < 16; localY++) {
                    for (int offset = 0; offset < 16; offset++) {
                        int localX = face.localX(offset);
                        int localZ = face.localZ(offset);
                        int id = blocks == null ? uniformId
                                : blocks[(localY << 8) | (localZ << 4) | localX];
                        scheduled += seedBorderCell(
                                state, column, id, localX, localY, localZ,
                                face.stepX, face.stepZ, sectionY, tick, scheduledPositions);
                    }
                }
            }
        }
        return scheduled;
    }

    private static BoundaryFace ownershipFacingFace(RegionBounds bounds, ChunkColumnState column) {
        int chunkX = column.chunkX();
        int chunkZ = column.chunkZ();
        if ((long) chunkX == (long) bounds.minChunkX() - 1
                && chunkZ >= bounds.minChunkZ() && chunkZ <= bounds.maxChunkZ()) {
            return BoundaryFace.EAST;
        }
        if ((long) chunkX == (long) bounds.maxChunkX() + 1
                && chunkZ >= bounds.minChunkZ() && chunkZ <= bounds.maxChunkZ()) {
            return BoundaryFace.WEST;
        }
        if ((long) chunkZ == (long) bounds.minChunkZ() - 1
                && chunkX >= bounds.minChunkX() && chunkX <= bounds.maxChunkX()) {
            return BoundaryFace.SOUTH;
        }
        if ((long) chunkZ == (long) bounds.maxChunkZ() + 1
                && chunkX >= bounds.minChunkX() && chunkX <= bounds.maxChunkX()) {
            return BoundaryFace.NORTH;
        }
        return null;
    }

    private static int seedBorderCell(MutableRegionState state, ChunkColumnState column, int id,
                                       int localX, int localY, int localZ, int stepX, int stepZ,
                                      int sectionY, long tick, Set<NBlockPos> scheduledPositions) {
        if (!isFluid(id)) {
            return 0;
        }
        NBlockPos target = new NBlockPos(
                column.chunkX() * 16 + localX + stepX,
                sectionY + localY,
                column.chunkZ() * 16 + localZ + stepZ);
        if (!state.inOwnedRegion(target)) {
            return 0;
        }
        int current = state.getBlock(target);
        if (current != FlatWorldRules.AIR || scheduledPositions.contains(target)) {
            return 0;
        }
        int desired = desiredAt(state, target, FlatWorldRules.AIR);
        if (!isFluid(desired)) {
            return 0;
        }
        state.scheduleTick(target, current, tick + delayOf(isWater(desired)), 0);
        scheduledPositions.add(target);
        return 1;
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


    /**
     * The lava-meets-water rule (L-2), vanilla-shaped and deterministic:
     *
     * <ul>
     *   <li>a lava <b>source</b> touched by water anywhere in its six-neighbourhood becomes
     *       {@link FlatWorldRules#OBSIDIAN} — the block that makes a nether portal buildable and a
     *       lava lake survivable;</li>
     *   <li>a lava <b>flow</b> with water directly <b>below</b> it becomes
     *       {@link FlatWorldRules#STONE} — vanilla's "lava flows onto water" case, which is why the
     *       cell underneath is checked separately from the other five;</li>
     *   <li>a lava <b>flow</b> touched by water anywhere else becomes
     *       {@link FlatWorldRules#COBBLESTONE}.</li>
     * </ul>
     *
     * <p>Water is never consumed. That is vanilla's rule and it is also the one that keeps the
     * outcome a pure function of the neighbourhood: a rule that removed the water would make the
     * result depend on which of the two cells the engine happened to visit first, which is exactly
     * the class of order dependence the hashed queue exists to eliminate.
     *
     * @return the solid the cell becomes, or {@link FlatWorldRules#AIR} when nothing solidifies.
     */
    static int solidification(MutableRegionState state, NBlockPos pos, int current) {
        if (!isLava(current)) {
            return FlatWorldRules.AIR;
        }
        boolean waterBelow = false;
        if (pos.y() > FlatWorldRules.MIN_Y) {
            waterBelow = isWater(state.getBlock(new NBlockPos(pos.x(), pos.y() - 1, pos.z())));
        }
        boolean waterAdjacent = waterBelow;
        if (!waterAdjacent && pos.y() < FlatWorldRules.MAX_Y) {
            waterAdjacent = isWater(state.getBlock(new NBlockPos(pos.x(), pos.y() + 1, pos.z())));
        }
        if (!waterAdjacent) {
            for (NBlockPos n : horizontalNeighbors(pos)) {
                if (isWater(state.getBlock(n))) {
                    waterAdjacent = true;
                    break;
                }
            }
        }
        if (!waterAdjacent) {
            return FlatWorldRules.AIR;
        }
        if (current == FlatWorldRules.LAVA_SOURCE) {
            return FlatWorldRules.OBSIDIAN;
        }
        return waterBelow ? FlatWorldRules.STONE : FlatWorldRules.COBBLESTONE;
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
