package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

/**
 * One compare-and-set mutation of the canonical entity table (Task 12a). A null
 * {@code expectedPrevious} means CREATE, a null {@code newState} means REMOVE, and two present
 * states mean UPDATE. At least one side must be present and every present state must carry
 * {@code id}.
 *
 * <p>Wire form: {@code [u16 ENTITY_MUTATION][u16 ENCODING_VERSION][NetworkEntityId]
 * [u8 expected-present][expected?][u8 new-present][new?]}.
 *
 * @Thread-context immutable, any thread.
 */
public record EntityMutation(
        NetworkEntityId id,
        PersistedEntityState expectedPrevious,
        PersistedEntityState newState
) implements Encodable {

    public EntityMutation {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (expectedPrevious == null && newState == null) {
            throw new IllegalArgumentException("entity mutation must have an expected or new state");
        }
        if (expectedPrevious != null && !id.equals(expectedPrevious.id())) {
            throw new IllegalArgumentException("expectedPrevious id does not match mutation id");
        }
        if (newState != null && !id.equals(newState.id())) {
            throw new IllegalArgumentException("newState id does not match mutation id");
        }
    }

    /** @return {@code true} for an absent-to-present transition. */
    public boolean isCreate() {
        return expectedPrevious == null && newState != null;
    }

    /** @return {@code true} for a present-to-absent transition. */
    public boolean isRemove() {
        return expectedPrevious != null && newState == null;
    }

    /** @return {@code true} for a present-to-present transition. */
    public boolean isUpdate() {
        return expectedPrevious != null && newState != null;
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.ENTITY_MUTATION).writeU16(ENCODING_VERSION);
        id.encode(w);
        w.writeOptional(expectedPrevious);
        if (expectedPrevious != null) {
            expectedPrevious.encode(w);
        }
        w.writeOptional(newState);
        if (newState != null) {
            newState.encode(w);
        }
    }

    /** Decode one full entity-mutation frame. */
    public static EntityMutation decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.ENTITY_MUTATION) {
            throw new IllegalStateException("expected ENTITY_MUTATION tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        NetworkEntityId id = NetworkEntityId.decode(r);
        PersistedEntityState expected = r.readOptional() ? PersistedEntityState.decode(r) : null;
        PersistedEntityState next = r.readOptional() ? PersistedEntityState.decode(r) : null;
        return new EntityMutation(id, expected, next);
    }
}
