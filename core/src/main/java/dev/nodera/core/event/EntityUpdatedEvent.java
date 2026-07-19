package dev.nodera.core.event;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;

/**
 * A tracked entity's persisted state changed (Task 12a) — physics step, age, or payload update.
 * Carries the full new {@link PersistedEntityState}; the table upsert is keyed by its id.
 *
 * <p>Wire form: {@code [u16 ENTITY_UPDATED_EVENT][u16 ENCODING_VERSION][PersistedEntityState]}.
 *
 * @Thread-context immutable, any thread.
 */
public record EntityUpdatedEvent(PersistedEntityState entity) implements RegionEvent {

    /** @throws IllegalArgumentException if {@code entity} is null. */
    public EntityUpdatedEvent {
        if (entity == null) {
            throw new IllegalArgumentException("entity must not be null");
        }
    }

    /** The entity this event concerns. */
    public NetworkEntityId entityId() {
        return entity.id();
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.ENTITY_UPDATED_EVENT).writeU16(ENCODING_VERSION);
        entity.encode(w);
    }

    static EntityUpdatedEvent decodeBody(CanonicalReader r) {
        return new EntityUpdatedEvent(PersistedEntityState.decode(r));
    }
}
