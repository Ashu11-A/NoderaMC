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
 * <p><b>Hit detection (block stop):</b> after the move, if the projectile's destination block is
 * opaque (not {@link LightField#isTransparent}), the shot STICKS — velocity is zeroed and the
 * position is held at the last free cell (one tick short of the wall, the player-visible "arrow
 * embedded in the wall"). Transparent cells (air, fluids) are passed through; a precise voxel-DDA
 * raycast (sub-tunnel face snap) is a later refinement. Border crossings transfer the entity to
 * the neighbour region exactly like items (the validated lane owns the trajectory across regions).
 *
 * <p>Remaining (later L-9 increments): entity-hit detection (arrow strikes a mob), voxel-DDA
 * face-snap + water slow-down, the player action that fires a projectile, live evidence.
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
        FixedVec3 nextPos = shot.pos().add(nextVel);

        NBlockPos dest = new NBlockPos(nextPos.blockX(), nextPos.blockY(), nextPos.blockZ());
        if (!state.inOwnedRegion(dest)) {
            // Crossed into a neighbour region: the trajectory continues under its committee.
            RegionId target = RegionId.fromChunk(
                    state.region().dimension(),
                    Math.floorDiv(nextPos.blockX(), 16),
                    Math.floorDiv(nextPos.blockZ(), 16));
            state.transferEntity(target, withMotion(shot, nextPos, nextVel));
            return;
        }
        int block = state.getBlock(dest);
        if (block != FlatWorldRules.AIR && !LightField.isTransparent(block)) {
            // Hit an opaque block: stick just short of the wall, velocity killed.
            state.updateEntity(withMotion(shot, shot.pos(), FixedVec3.ZERO));
            return;
        }
        state.updateEntity(withMotion(shot, nextPos, nextVel));
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
