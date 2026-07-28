package dev.nodera.simulation.entity;

import dev.nodera.core.crypto.StableHash;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedPoint;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.rules.FlatWorldRules;

import java.util.ArrayList;
import java.util.List;

/**
 * The Task 15 deterministic TNT lane (L-9 warmup rung): primed TNT entities that fuse in the
 * region root and detonate as a SEEDED, replica-identical blast. A primed TNT entity is
 * engine-owned state ({@link EntityKind#TNT}) whose {@code despawnTick} IS its detonation tick;
 * at detonation a blast-local {@link DeterministicRandom} — seeded from the entity id, position,
 * and detonation tick — drives a per-cell destruction pass over a bounded sphere, so every
 * replica computes the identical crater from the root alone. The species' behaviour originates
 * here, never in a captured vanilla entity.
 *
 * <p><b>Blast model (Nodera-defined, not an NMS port):</b> for each block cell in the cube
 * {@code [-R,R]^3} around the detonation centre (canonical {@code dy, dx, dz} order), a cell
 * within the Euclidean cutoff {@code R} is destroyed when a single draw
 * {@code blastRng.nextInt(R^2) < R^2 - dist^2} — the destruction probability falls off linearly
 * with squared distance (centre always clears, edge never does), producing a roughly spherical
 * crater. Cells outside the owned region are skipped (border fail-closed: the engine never
 * mutates the halo — cross-region blast via the T13 group-migration lane is a later increment,
 * {@code @Invariant(11)}). One draw per in-range non-air cell in fixed order keeps the rng
 * stream aligned across replicas.
 *
 * <p><b>Chain ignition:</b> a detonation shortens the fuse of every other TNT entity within the
 * blast radius to {@code detonateTick + 1}, so chained TNT clears in deterministic sequence
 * (vanilla ignites primed TNT caught in a blast — replicated here without a captured mob).
 *
 * <p><b>Knockback:</b> every kinematic non-TNT entity inside the blast radius is shoved outward —
 * a per-axis impulse whose direction is the delta sign and whose magnitude decays linearly with
 * squared distance ({@code (R²−dist²)×KNOCKBACK_BASE}; centre strongest, edge vanishes), added to
 * the entity's velocity. Pure fixed-point, no sqrt. (Mobs are shoved too: {@code MobAiRules}
 * consumes an imparted velocity before wandering — decaying it until it settles or hits a wall.)
 *
 * <p>Remaining (later L-9 increments): blast-destruction hooks into redstone/gravity/observer
 * recompute, mob knockback, cross-region blast via migration, and the player action that ignites a
 * placed TNT block into a primed entity.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class TntRules {

    /** Vanilla primed-TNT fuse (4s @ 20 tps); the documented parity envelope. */
    public static final int FUSE_TICKS = 80;
    /** TNT explosion radius (the vanilla value; the documented parity envelope). */
    public static final int BLAST_RADIUS = 4;
    /** Squared cutoff = {@link #BLAST_RADIUS}^2 (the destruction sphere's edge). */
    public static final int BLAST_RADIUS_SQ = BLAST_RADIUS * BLAST_RADIUS;
    /** The primed-TNT entity type id (the mod maps it when mirroring the blast). */
    public static final int TNT_TYPE_ID = 200;
    /** Tag mixed into the blast-seed domain (never collides with action seqs / the spawn domain). */
    private static final long BLAST_DOMAIN = 0x544E_5452L << 32; // "TNTR"
    /** Per-axis knockback impulse per unit of (R²−dist²) decay, Q32.32 (1/16 ⇒ 1 block/tick at the centre). */
    public static final long KNOCKBACK_BASE = FixedVec3.ONE / 16;

    private TntRules() {
    }

    /** The per-tick phase: detonate every TNT entity whose fuse has expired. */
    public static void tick(MutableRegionState state, long tick, DeterministicRandom rng) {
        // Snapshot first: a detonation mutates the entity table (chain ignition + removal).
        List<PersistedEntityState> primed = new ArrayList<>();
        for (PersistedEntityState entity : state.entities()) {
            if (entity.kind() == EntityKind.TNT) {
                primed.add(entity);
            }
        }
        for (PersistedEntityState tnt : primed) {
            if (tnt.despawnTick() == PersistedEntityState.NEVER_DESPAWN) {
                continue; // a primed TNT always carries a detonation tick
            }
            long detonateAt = Integer.toUnsignedLong(tnt.despawnTick());
            if (tick < detonateAt) {
                continue;
            }
            detonate(state, tnt, detonateAt);
            state.removeEntity(tnt.id());
        }
    }

    private static void detonate(MutableRegionState state, PersistedEntityState tnt,
                                 long detonateAt) {
        int cx = tnt.pos().blockX();
        int cy = tnt.pos().blockY();
        int cz = tnt.pos().blockZ();
        DeterministicRandom blast = new DeterministicRandom(StableHash.of(
                BLAST_DOMAIN, tnt.id().value(), detonateAt,
                tnt.pos().x(), tnt.pos().y(), tnt.pos().z()));
        // Block destruction: one draw per in-range non-air owned cell, canonical order.
        for (int dy = BLAST_RADIUS; dy >= -BLAST_RADIUS; dy--) {
            for (int dx = -BLAST_RADIUS; dx <= BLAST_RADIUS; dx++) {
                for (int dz = -BLAST_RADIUS; dz <= BLAST_RADIUS; dz++) {
                    int distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > BLAST_RADIUS_SQ) {
                        continue;
                    }
                    NBlockPos cell = new NBlockPos(cx + dx, cy + dy, cz + dz);
                    if (cell.y() < FlatWorldRules.MIN_Y || cell.y() > FlatWorldRules.MAX_Y) {
                        continue;
                    }
                    if (!state.inOwnedRegion(cell)) {
                        continue; // border fail-closed — cross-region blast is a later increment
                    }
                    int block = state.getBlock(cell);
                    if (block == FlatWorldRules.AIR) {
                        continue;
                    }
                    // P(destroy) = 1 - distSq/R^2: centre always clears, edge never does.
                    if (blast.nextInt(BLAST_RADIUS_SQ) < BLAST_RADIUS_SQ - distSq) {
                        state.setBlock(cell, FlatWorldRules.AIR, null, blast);
                    }
                }
            }
        }
        // Knockback: shove every kinematic non-TNT entity inside the blast radius outward, impulse
        // decaying linearly with squared distance (centre strongest, edge vanishes). Pure fixed-point
        // — the per-axis direction is the delta sign, the magnitude is (R²−dist²)×KNOCKBACK_BASE.
        for (PersistedEntityState victim : state.entities()) {
            if (victim.kind() == EntityKind.TNT || victim.id().equals(tnt.id())) {
                continue; // TNT is not kinematic here; the detonator is removed by the caller
            }
            int ddx = victim.pos().blockX() - cx;
            int ddy = victim.pos().blockY() - cy;
            int ddz = victim.pos().blockZ() - cz;
            int victimDistSq = ddx * ddx + ddy * ddy + ddz * ddz;
            if (victimDistSq == 0 || victimDistSq > BLAST_RADIUS_SQ) {
                continue;
            }
            // Blast damage first (L-13): an engine-owned MOB loses health by proximity and a
            // kill removes it from the root — the dead take no knockback. GHOST vitals are
            // server-authoritative, so ghosts are shoved but never wounded here.
            MobCombatRules.damage(state, victim,
                    MobCombatRules.blastDamageAt(victimDistSq, BLAST_RADIUS_SQ));
            PersistedEntityState survivor = state.entity(victim.id());
            if (survivor == null) {
                continue;
            }
            long mag = FixedPoint.multiply(
                    KNOCKBACK_BASE, (long) (BLAST_RADIUS_SQ - victimDistSq) << 32);
            long nvx = survivor.vel().x() + impulse(ddx, mag);
            long nvy = survivor.vel().y() + impulse(ddy, mag);
            long nvz = survivor.vel().z() + impulse(ddz, mag);
            state.updateEntity(survivor.withMotion(
                    survivor.pos(), new FixedVec3(nvx, nvy, nvz)));
        }
        // Chain ignition: every other TNT within the radius detonates one tick later.
        for (PersistedEntityState other : state.entities()) {
            if (other.kind() != EntityKind.TNT || other.id().equals(tnt.id())) {
                continue;
            }
            if (other.despawnTick() == PersistedEntityState.NEVER_DESPAWN) {
                continue;
            }
            long otherDetonate = Integer.toUnsignedLong(other.despawnTick());
            if (otherDetonate <= detonateAt + 1) {
                continue; // already detonating at or before the chained tick — leave it
            }
            int ox = other.pos().blockX();
            int oy = other.pos().blockY();
            int oz = other.pos().blockZ();
            int d = (ox - cx) * (ox - cx) + (oy - cy) * (oy - cy) + (oz - cz) * (oz - cz);
            if (d <= BLAST_RADIUS_SQ) {
                state.updateEntity(new PersistedEntityState(
                        other.id(), other.kind(), other.typeId(), other.pos(), other.vel(),
                        other.ageTicks(), (int) (detonateAt + 1), other.payload()));
            }
        }
    }

    /** Knockback impulse along one axis: ±mag by the delta sign, 0 when the axis is level. */
    private static long impulse(int delta, long mag) {
        if (delta > 0) {
            return mag;
        }
        if (delta < 0) {
            return -mag;
        }
        return 0L;
    }

}
