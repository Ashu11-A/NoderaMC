package dev.nodera.core.action;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;

/**
 * A player striking a tracked entity in melee (Task 16, L-13). References the target by its
 * deterministic {@link NetworkEntityId} and carries the attacker's position so the committee can
 * validate reach — the DAMAGE dealt is a rule-set constant, never client-supplied, so a modified
 * client cannot hit harder than the rules allow. The engine wounds the target through the single
 * combat mutation point ({@code MobCombatRules.damage}); a kill removes the entity from the root.
 *
 * <p>Wire form: {@code [u16 ATTACK_ENTITY_ACTION][u16 ENCODING_VERSION][NetworkEntityId target]
 * [FixedVec3 origin]}.
 *
 * @Thread-context immutable, any thread.
 */
public record AttackEntityAction(NetworkEntityId target, FixedVec3 origin) implements GameAction {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any field is null.
     */
    public AttackEntityAction {
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        if (origin == null) {
            throw new IllegalArgumentException("origin must not be null");
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.ATTACK_ENTITY_ACTION).writeU16(ENCODING_VERSION);
        encodeBody(w);
    }

    private void encodeBody(CanonicalWriter w) {
        target.encode(w);
        origin.encode(w);
    }

    static AttackEntityAction decodeBody(CanonicalReader r) {
        return new AttackEntityAction(NetworkEntityId.decode(r), FixedVec3.decode(r));
    }
}
