package dev.nodera.simulation.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.crypto.StableHash;
import dev.nodera.simulation.MutableRegionState;

/**
 * The Task 16 combat lane opener (L-13): mob vitals live IN the root and are mutated only by
 * engine rules. A {@link EntityKind#MOB} entity carries the canonical vitals payload
 * ({@code [u16 health][u16 maxHealth]}); {@link #damage} is the single mutation point every
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
                BLAST_DAMAGE_MAX);
    }

    /** Canonical opaque MOB vitals payload: {@code [u16 health][u16 maxHealth]}. */
    public static Bytes vitalsPayload(int health, int maxHealth) {
        if (maxHealth <= 0 || maxHealth > 0xFFFF) {
            throw new IllegalArgumentException("maxHealth must be in [1, 65535]: " + maxHealth);
        }
        if (health <= 0 || health > maxHealth) {
            throw new IllegalArgumentException(
                    "health must be in [1, maxHealth]: " + health + "/" + maxHealth);
        }
        CanonicalWriter w = new CanonicalWriter(4);
        w.writeU16(health);
        w.writeU16(maxHealth);
        return w.toBytes();
    }

    /** Decoded MOB vitals. */
    public record Vitals(int health, int maxHealth) {
    }

    /** Decode and validate a MOB vitals payload. */
    public static Vitals decodeVitals(Bytes payload) {
        CanonicalReader r = new CanonicalReader(payload);
        int health = r.readU16();
        int maxHealth = r.readU16();
        if (health == 0 || health > maxHealth || r.available() != 0) {
            throw new IllegalStateException("malformed mob vitals payload");
        }
        return new Vitals(health, maxHealth);
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
        if (mob.kind() != EntityKind.MOB || amount <= 0) {
            return false;
        }
        Vitals vitals = decodeVitals(mob.payload());
        int remaining = vitals.health() - amount;
        if (remaining <= 0) {
            state.removeEntity(mob.id());
            return true;
        }
        state.updateEntity(new PersistedEntityState(
                mob.id(), mob.kind(), mob.typeId(), mob.pos(), mob.vel(),
                mob.ageTicks(), mob.despawnTick(),
                vitalsPayload(remaining, vitals.maxHealth())));
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
