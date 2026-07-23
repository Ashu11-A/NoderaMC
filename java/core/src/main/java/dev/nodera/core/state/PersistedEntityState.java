package dev.nodera.core.state;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

/**
 * The canonical persisted state of one tracked entity (Task 12a) — the unit that lives in a
 * region's {@code EntityStore} and enters the state root. Position/velocity are {@link FixedVec3}
 * (Q32.32) so the entity table is bit-stable across replicas (A-5). {@code payload} carries the
 * kind-specific canonical bytes (for an ITEM: the item-stack id + count); it is opaque here so the
 * state type does not depend on the item model.
 *
 * <p>Wire form: {@code [u16 PERSISTED_ENTITY_STATE][u16 ENCODING_VERSION][NetworkEntityId]
 * [u8 kind ordinal][u32 typeId][FixedVec3 pos][FixedVec3 vel][u32 ageTicks][u32 despawnTick]
 * [bytes payload]}.
 *
 * @Thread-context immutable, any thread.
 */
public record PersistedEntityState(
        NetworkEntityId id,
        EntityKind kind,
        int typeId,
        FixedVec3 pos,
        FixedVec3 vel,
        int ageTicks,
        int despawnTick,
        Bytes payload
) implements Encodable {

    /** {@code despawnTick} sentinel: the entity never despawns. */
    public static final int NEVER_DESPAWN = -1;

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any reference is null.
     */
    public PersistedEntityState {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
        if (vel == null) {
            throw new IllegalArgumentException("vel must not be null");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
    }

    /** Advance the entity one tick (age only — physics live in the simulation rules). */
    public PersistedEntityState tick() {
        return new PersistedEntityState(id, kind, typeId, pos, vel, ageTicks + 1, despawnTick, payload);
    }

    /** True when this entity has reached its despawn age. */
    public boolean shouldDespawn() {
        return despawnTick >= 0 && ageTicks >= despawnTick;
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.PERSISTED_ENTITY_STATE).writeU16(ENCODING_VERSION);
        id.encode(w);
        w.writeU8(kind.ordinal());
        w.writeU32(Integer.toUnsignedLong(typeId));
        pos.encode(w);
        vel.encode(w);
        w.writeU32(Integer.toUnsignedLong(ageTicks));
        w.writeU32(Integer.toUnsignedLong(despawnTick));
        w.writeBytes(payload);
    }

    /**
     * Full-frame decode.
     *
     * @throws IllegalStateException if the next tag is not {@code PERSISTED_ENTITY_STATE} or the
     *         kind ordinal is out of range.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static PersistedEntityState decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.PERSISTED_ENTITY_STATE) {
            throw new IllegalStateException("expected PERSISTED_ENTITY_STATE tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        NetworkEntityId id = NetworkEntityId.decode(r);
        int kindOrd = r.readU8();
        EntityKind[] kinds = EntityKind.values();
        if (kindOrd >= kinds.length) {
            throw new IllegalStateException("EntityKind ordinal out of range: " + kindOrd);
        }
        int typeId = r.readU32AsInt();
        FixedVec3 pos = FixedVec3.decode(r);
        FixedVec3 vel = FixedVec3.decode(r);
        int ageTicks = r.readU32AsInt();
        // despawnTick keeps the wrapping cast: NEVER_DESPAWNS (-1) round-trips as 0xFFFFFFFF.
        int despawnTick = (int) r.readU32();
        Bytes payload = r.readBytesValue();
        return new PersistedEntityState(id, kinds[kindOrd], typeId, pos, vel, ageTicks, despawnTick, payload);
    }
}
