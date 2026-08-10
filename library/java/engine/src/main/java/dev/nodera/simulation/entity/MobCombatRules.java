package dev.nodera.simulation.entity;

import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.crypto.StableHash;
import dev.nodera.simulation.MutableRegionState;

/**
 * The Task 16 combat lane opener (L-13): mob vitals live IN the root and are mutated only by
 * engine rules. A {@link EntityKind#MOB} entity carries its vitals in the leading fields of
 * the canonical {@link MobState} payload; {@link #damage} is the single mutation point every
 * damage source routes through — projectile strikes ({@link ProjectileRules}) and blast
 * proximity ({@link TntRules}) today, player melee once the player-action lane ships. Health
 * reaching zero removes the entity from the root: death is a committed, replica-identical
 * state transition, not a server event.
 *
 * <p>{@link EntityKind#GHOST} mobs are NEVER damaged here — their vitals are
 * server-authoritative by definition (the mirror lane owns them); the engine only fights mobs
 * it owns.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class MobCombatRules {

    /** Vitals of the first engine-owned species (zombie halves-of-hearts, vanilla 20). */
    public static final int ZOMBIE_MAX_HEALTH = 20;
    /** Flat arrow damage (vanilla mid-charge arrow ≈ 5 halves; speed scaling is later polish). */
    public static final int ARROW_DAMAGE = 5;
    /** Flat melee damage per validated attack action (item-based scaling is later polish). */
    public static final int MELEE_DAMAGE = 6;
    /** Maximum per-axis reach for a melee attack, Q32.32 (vanilla survival reach ≈ 3–4.5). */
    public static final long MELEE_REACH = 4L << 32;
    /** Maximum blast damage at the blast centre; falls off linearly with distance squared. */
    public static final int BLAST_DAMAGE_MAX = 14;

    private MobCombatRules() {
    }

    /** Fingerprint contribution pinning combat constants and the vitals payload shape. */
    public static long semanticFingerprint() {
        return StableHash.of(
                StableHash.of("nodera.simulation.mob-combat-rules.v1"),
                ZOMBIE_MAX_HEALTH,
                ARROW_DAMAGE,
                MELEE_DAMAGE,
                MELEE_REACH,
                BLAST_DAMAGE_MAX);
    }

    /**
     * Apply {@code amount} halves of damage to an engine-owned mob: health decrements in the
     * root; zero or below removes the entity (death). Any other kind — GHOST included — is
     * left untouched and {@code false} is returned, so callers can gate side effects (an
     * arrow still stops on a ghost, it just cannot wound it).
     *
     * @return {@code true} when the target was an engine-owned mob and took the damage.
     */
    public static boolean damage(MutableRegionState state, PersistedEntityState mob, int amount) {
        if (amount <= 0) {
            return false;
        }
        if (mob.kind() == EntityKind.PLAYER) {
            return damagePlayer(state, mob, amount);
        }
        if (mob.kind() != EntityKind.MOB) {
            return false;
        }
        MobState decoded = MobState.decode(mob.payload());
        int remaining = decoded.health() - amount;
        if (remaining <= 0) {
            state.removeEntity(mob.id());
            return true;
        }
        // withHealth, not a fresh payload: being hit is not a reason to forget where you were
        // going, and rebuilding the payload from the vitals alone would silently erase the AI
        // memory of every mob that ever takes damage.
        state.updateEntity(new PersistedEntityState(
                mob.id(), mob.kind(), mob.typeId(), mob.pos(), mob.vel(),
                mob.ageTicks(), mob.despawnTick(),
                decoded.withHealth(remaining).encode()));
        return true;
    }

    /**
     * PvP (L-13): a player's committed health decrements through the same single mutation point;
     * death removes the root presence AND spills the committed inventory as validated ITEM
     * entities at the death position — nothing dupes, nothing vanishes; respawn is the live
     * lane's re-registration.
     */
    private static boolean damagePlayer(
            MutableRegionState state, PersistedEntityState player, int amount) {
        PlayerRules.PlayerState decoded = PlayerRules.decode(player.payload());
        int remaining = decoded.health() - amount;
        if (remaining > 0) {
            state.updateEntity(new PersistedEntityState(
                    player.id(), player.kind(), player.typeId(), player.pos(), player.vel(),
                    player.ageTicks(), player.despawnTick(),
                    PlayerRules.payload(decoded.withHealth(remaining))));
            return true;
        }
        state.removeEntity(player.id());
        long seq = 0;
        for (dev.nodera.core.state.ContainerEntry.ItemSlot slot : decoded.inventory()) {
            if (slot.isEmpty()) {
                continue;
            }
            state.createEntity(new PersistedEntityState(
                    dev.nodera.core.state.NetworkEntityId.allocate(
                            state.region(), state.baseVersion(),
                            (0x44454144L << 32) | (player.id().value() & 0xFFFFFF) << 8 | seq++),
                    EntityKind.ITEM, slot.itemStackId(), player.pos(), FixedVec3.ZERO,
                    0, ItemEntityRules.DESPAWN_AGE_TICKS,
                    ItemEntityRules.payload(slot.itemStackId(), slot.count())));
        }
        return true;
    }

    /**
     * Deterministic blast damage for a victim {@code distSq} blocks² from the centre:
     * linear falloff of {@link #BLAST_DAMAGE_MAX} over the blast radius squared (integer
     * math only), minimum 1 inside the radius.
     */
    public static int blastDamageAt(long distSq, long blastRadiusSq) {
        if (distSq >= blastRadiusSq) {
            return 0;
        }
        long scaled = BLAST_DAMAGE_MAX * (blastRadiusSq - distSq) / blastRadiusSq;
        return (int) Math.max(1, scaled);
    }
}
