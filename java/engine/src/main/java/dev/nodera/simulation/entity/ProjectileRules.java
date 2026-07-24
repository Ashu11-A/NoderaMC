package dev.nodera.simulation.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.region.RegionId;
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
 * The Task 15 deterministic projectile lane (L-9): engine-owned arrows/pearls/snowballs on pure
 * fixed-point ballistics — never a captured vanilla entity. Every continuous quantity is Q32.32
 * (the determinism rule), integrated in a FIXED order so three replicas draw the identical
 * trajectory from the root alone.
 *
 * <p><b>Integration (one tick, fixed order):</b> horizontal velocity decays by air drag
 * ({@link #DRAG}); vertical velocity first loses gravity then decays; position advances by the
 * new velocity. {@code vy = (vy - gravity) * drag; vx = vx * drag; vz = vz * drag; pos += vel} —
 * gravity-before-drag is the documented canonical order (swapping it changes the arc, so it is
 * pinned). Constants are pre-baked Q32.32 literals (drag 0.99, gravity 0.05 blocks/tick² — the
 * arrow envelope); no float ever enters hashed state.
 *
 * <p><b>Hit detection (block stop):</b> the per-tick move is marched in ≤1-block sub-steps and
 * the shot STICKS at the first opaque cell (not {@link LightField#isTransparent}) it meets —
 * velocity zeroed, held one sub-step short (the player-visible "arrow embedded in the wall"). The
 * march never skips a voxel, so a thin wall stops even a fast shot; transparent cells (air, fluids)
 * are passed. If a block mutation earlier in the same tick filled the shot's own cell, it is held
 * in place. Border crossings hand the entity to the neighbour region exactly like items.
 *
 * <p>Remaining (later L-9 increments): entity-hit detection (arrow strikes a mob), a true voxel-DDA
 * face-snap (the unit-step march is exact on the dominant axis, approximate on diagonals) + water
 * slow-down, the player action that fires a projectile, live evidence.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class ProjectileRules {

    /** Air-drag / inertia retention per tick, Q32.32 literal (0.99 — the arrow drag envelope). */
    public static final long DRAG = 4_252_017_623L;
    /** Per-tick downward acceleration, Q32.32 literal (≈ 0.05 blocks/tick² — the arrow envelope). */
    public static final long GRAVITY = 214_748_365L;
    /** Engine projectiles despawn after this many ticks (≈ vanilla's stuck-arrow lifetime). */
    public static final int LIFETIME_TICKS = 1_200;
    /** Arrow entity type id (the mod maps it when mirroring the shot). */
    public static final int ARROW_TYPE_ID = 300;

    private ProjectileRules() {
    }

    /** The per-tick phase: advance every projectile one deterministic step, sticking or transferring. */
    public static void tick(MutableRegionState state, long tick, DeterministicRandom rng) {
        // Snapshot first: a transfer removes the entity mid-iteration.
        List<PersistedEntityState> shots = new ArrayList<>();
        for (PersistedEntityState entity : state.entities()) {
            if (entity.kind() == EntityKind.PROJECTILE) {
                shots.add(entity);
            }
        }
        for (PersistedEntityState shot : shots) {
            if (shot.despawnTick() != PersistedEntityState.NEVER_DESPAWN
                    && tick >= Integer.toUnsignedLong(shot.despawnTick())) {
                state.removeEntity(shot.id());
                continue;
            }
            if (shot.vel().equals(FixedVec3.ZERO)) {
                continue; // stuck in a block — at rest until its lifetime ends
            }
            step(state, shot);
        }
    }

    private static void step(MutableRegionState state, PersistedEntityState shot) {
        FixedVec3 vel = shot.vel();
        // Fixed order: gravity before drag on Y, then drag on X/Z.
        long vy = multiplyFixed(vel.y() - GRAVITY, DRAG);
        long vx = multiplyFixed(vel.x(), DRAG);
        long vz = multiplyFixed(vel.z(), DRAG);
        FixedVec3 nextVel = new FixedVec3(vx, vy, vz);

        // Origin embed: if a block mutation earlier this tick filled the projectile's own cell, the
        // shot is trapped — kill its velocity in place (it despawns at its lifetime horizon).
        FixedVec3 origin = shot.pos();
        NBlockPos originCell = new NBlockPos(origin.blockX(), origin.blockY(), origin.blockZ());
        if (state.inOwnedRegion(originCell)) {
            int originBlock = state.getBlock(originCell);
            if (originBlock != FlatWorldRules.AIR && !LightField.isTransparent(originBlock)) {
                state.updateEntity(withMotion(shot, origin, FixedVec3.ZERO));
                return;
            }
        }

        // March from the current position toward pos+vel in <=1-block sub-steps so no voxel is
        // skipped (kills tunneling through a thin wall at high speed). Stick at the first opaque
        // cell, one sub-step short; hand off to the neighbour region at the first off-region step.
        long remX = vx;
        long remY = vy;
        long remZ = vz;
        FixedVec3 pos = origin;
        while (remX != 0 || remY != 0 || remZ != 0) {
            long sx = clampStep(remX);
            long sy = clampStep(remY);
            long sz = clampStep(remZ);
            FixedVec3 candidate = pos.add(new FixedVec3(sx, sy, sz));
            NBlockPos cell = new NBlockPos(candidate.blockX(), candidate.blockY(), candidate.blockZ());
            if (!state.inOwnedRegion(cell)) {
                RegionId target = RegionId.fromChunk(
                        state.region().dimension(),
                        Math.floorDiv(candidate.blockX(), 16),
                        Math.floorDiv(candidate.blockZ(), 16));
                state.transferEntity(target, withMotion(shot, candidate, nextVel));
                return;
            }
            int block = state.getBlock(cell);
            if (block != FlatWorldRules.AIR && !LightField.isTransparent(block)) {
                state.updateEntity(withMotion(shot, pos, FixedVec3.ZERO)); // stick one sub-step short
                return;
            }
            pos = candidate;
            remX -= sx;
            remY -= sy;
            remZ -= sz;
        }
        state.updateEntity(withMotion(shot, pos, nextVel));
    }

    /** One sub-step of the march: the remaining displacement, clamped to ±1 block. */
    private static long clampStep(long remaining) {
        if (remaining > FixedVec3.ONE) {
            return FixedVec3.ONE;
        }
        if (remaining < -FixedVec3.ONE) {
            return -FixedVec3.ONE;
        }
        return remaining;
    }

    private static PersistedEntityState withMotion(
            PersistedEntityState entity, FixedVec3 position, FixedVec3 velocity) {
        return new PersistedEntityState(
                entity.id(), entity.kind(), entity.typeId(), position, velocity,
                entity.ageTicks() + 1, entity.despawnTick(), entity.payload());
    }

    /** Signed Q32.32 product (the sanctioned {@code Math.multiplyHigh} idiom, cf. ItemEntityRules). */
    private static long multiplyFixed(long value, long multiplier) {
        return Math.multiplyHigh(value, multiplier) << 32 | (value * multiplier) >>> 32;
    }
}
