package dev.nodera.simulation.entity;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedPoint;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.simulation.rules.RedstoneRules;

import java.util.ArrayList;
import java.util.List;

/**
 * The Task 15 deterministic minecart lane (L-9): engine-owned carts on a rail graph — pure
 * fixed-point kinematics, never a captured vanilla entity. A cart holds an axis-aligned
 * horizontal velocity; each tick it rolls along the rail, boosted by powered rails and bled by
 * friction, and FOLLOWS the track by inferring its direction from the rail connectivity at its
 * cell (so a closed loop circulates forever — every replica traces the identical lap from the
 * root alone).
 *
 * <p><b>Direction model (no rail-shape states):</b> a single uniform {@code RAIL} block carries no
 * facing — the cart's next heading is the rail neighbour in its current direction if one exists
 * (continue), else the unique perpendicular rail neighbour (turn; canonical N,E,S,W tie-break),
 * else nothing (dead-end ⇒ stop). The reverse direction is never chosen, so a cart entering a
 * corner turns onto the outgoing leg rather than backing up. Position is snapped to the rail
 * centerline on the cross-axis each tick, so the cart rides the middle of the track.
 *
 * <p><b>Speed:</b> over a {@code POWERED_RAIL} that is receiving redstone power
 * ({@link RedstoneRules#cellReceivingPower}) the speed is set to {@link #MAX_SPEED} (boost);
 * an unpowered powered rail or a plain rail decays by {@link #FRICTION} (carts coast). A cart at
 * rest stays at rest —
 * it needs an initial push (vanilla needs a slope or a shove), so a stopped cart never spontaneously
 * reverses at a dead-end. Constants are pre-baked Q32.32 literals; no float enters hashed state.
 *
 * <p>Remaining (later L-9 increments): redstone-gated powered rails (read T13 power), rail
 * slopes/ascent gravity, entity-carrying + collision, the player place/ride actions, live evidence.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class RailRules {

    /** Cart top speed, Q32.32 literal (0.4 blocks/tick ≈ vanilla's 8 b/s). */
    public static final long MAX_SPEED = 1_717_986_918L;
    /** Per-tick speed retention on a plain rail, Q32.32 literal (0.998 — carts coast a long way). */
    public static final long FRICTION = 4_286_406_117L;
    /** Minecart entity type id (the mod maps it when mirroring the cart). */
    public static final int MINECART_TYPE_ID = 400;

    /** Horizontal unit headings as {dx, dy-ignored, dz}, canonical tie-break order N, E, S, W. */
    private static final int[][] HEADINGS = {
            {0, 0, -1},  // N (-z)
            {1, 0, 0},   // E (+x)
            {0, 0, 1},   // S (+z)
            {-1, 0, 0},  // W (-x)
    };

    private RailRules() {
    }

    /** Whether a block id is a rail cell (plain or powered). */
    public static boolean isRail(int id) {
        return id == FlatWorldRules.RAIL || id == FlatWorldRules.POWERED_RAIL;
    }

    /** The per-tick phase: roll every minecart one deterministic step along the rail graph. */
    public static void tick(MutableRegionState state, long tick, DeterministicRandom rng) {
        List<PersistedEntityState> carts = new ArrayList<>();
        for (PersistedEntityState entity : state.entities()) {
            if (entity.kind() == EntityKind.MINECART) {
                carts.add(entity);
            }
        }
        for (PersistedEntityState cart : carts) {
            step(state, cart);
        }
    }

    private static void step(MutableRegionState state, PersistedEntityState cart) {
        int cx = cart.pos().blockX();
        int cy = cart.pos().blockY();
        int cz = cart.pos().blockZ();
        NBlockPos cell = new NBlockPos(cx, cy, cz);
        if (!state.inOwnedRegion(cell) || !isRail(state.getBlock(cell))) {
            return; // off-track: the cart rests where it is
        }
        int[] heading = facing(cart.vel());
        if (heading == null) {
            return; // at rest — needs a push, never auto-reverses
        }
        if (!railAt(state, cx + heading[0], cy, cz + heading[2])) {
            NBlockPos forward = new NBlockPos(cx + heading[0], cy, cz + heading[2]);
            if (!state.inOwnedRegion(forward)) {
                // The forward cell is in a neighbour region: the cart rolls across the border
                // (handed off below). Do NOT treat the region edge as a dead-end.
            } else {
                int[] turn = turnExit(state, cell, heading);
                if (turn == null) {
                    // Dead-end: stop at the rail head, snapped to its centre.
                    state.updateEntity(cart.withMotionAndAge(
                            centerSnap(cart.pos(), cell), FixedVec3.ZERO, cart.ageTicks() + 1));
                    return;
                }
                heading = turn;
            }
        }
        long speed = speedAlong(cart.vel());
        boolean boost = state.getBlock(cell) == FlatWorldRules.POWERED_RAIL
                && RedstoneRules.cellReceivingPower(state, cell);
        if (boost) {
            speed = MAX_SPEED;
        } else {
            speed = FixedPoint.multiply(speed, FRICTION);
        }
        long vx = heading[0] * speed;
        long vz = heading[2] * speed;
        FixedVec3 nextPos = advance(cart.pos(), cell, heading, vx, vz);
        if (!state.inOwnedRegion(new NBlockPos(nextPos.blockX(), cy, nextPos.blockZ()))) {
            RegionId target = RegionId.fromChunk(
                    state.region().dimension(),
                    Math.floorDiv(nextPos.blockX(), 16),
                    Math.floorDiv(nextPos.blockZ(), 16));
            state.transferEntity(target, cart.withMotionAndAge(
                    nextPos, new FixedVec3(vx, 0L, vz), cart.ageTicks() + 1));
            return;
        }
        state.updateEntity(cart.withMotionAndAge(
                nextPos, new FixedVec3(vx, 0L, vz), cart.ageTicks() + 1));
    }

    /** The heading matching an axis-aligned velocity, or {@code null} when at rest. */
    private static int[] facing(FixedVec3 vel) {
        long vx = vel.x();
        long vz = vel.z();
        if (vx == 0 && vz == 0) {
            return null;
        }
        if (vx != 0 && vz != 0) {
            // Should not occur (velocity stays axis-aligned); collapse to the dominant axis.
            if (Math.abs(vx) >= Math.abs(vz)) {
                vz = 0;
            } else {
                vx = 0;
            }
        }
        if (vx > 0) {
            return HEADINGS[1];
        }
        if (vx < 0) {
            return HEADINGS[3];
        }
        return vz > 0 ? HEADINGS[2] : HEADINGS[0];
    }

    /** Speed magnitude along the (axis-aligned) velocity. */
    private static long speedAlong(FixedVec3 vel) {
        return Math.max(Math.abs(vel.x()), Math.abs(vel.z()));
    }

    /**
     * The perpendicular rail exit at a corner — never the reverse direction; canonical N,E,S,W
     * tie-break. Forward is already known to be absent (the caller only turns when it is).
     */
    private static int[] turnExit(MutableRegionState state, NBlockPos cell, int[] heading) {
        for (int[] d : HEADINGS) {
            if (d[0] == -heading[0] && d[2] == -heading[2]) {
                continue; // never reverse onto the incoming leg
            }
            if (railAt(state, cell.x() + d[0], cell.y(), cell.z() + d[2])) {
                return d;
            }
        }
        return null;
    }

    private static boolean railAt(MutableRegionState state, int x, int y, int z) {
        NBlockPos pos = new NBlockPos(x, y, z);
        return state.inOwnedRegion(pos) && isRail(state.getBlock(pos));
    }

    /** Advance along the heading axis; snap the cross-axis to the rail centreline. */
    private static FixedVec3 advance(FixedVec3 pos, NBlockPos cell, int[] heading, long vx, long vz) {
        long centerX = ((long) cell.x() << 32) + (FixedVec3.ONE / 2);
        long centerZ = ((long) cell.z() << 32) + (FixedVec3.ONE / 2);
        long x = heading[0] != 0 ? pos.x() + vx : centerX;
        long z = heading[2] != 0 ? pos.z() + vz : centerZ;
        return new FixedVec3(x, pos.y(), z);
    }

    /** Snap both horizontal axes to the cell centreline (a stopped cart rests mid-rail). */
    private static FixedVec3 centerSnap(FixedVec3 pos, NBlockPos cell) {
        long centerX = ((long) cell.x() << 32) + (FixedVec3.ONE / 2);
        long centerZ = ((long) cell.z() << 32) + (FixedVec3.ONE / 2);
        return new FixedVec3(centerX, pos.y(), centerZ);
    }

}
