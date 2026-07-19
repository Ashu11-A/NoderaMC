package dev.nodera.core.event;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;

/**
 * A tracked entity entered the region's validated entity table (Task 12a). Carries the full
 * initial {@link PersistedEntityState}.
 *
 * <p>Wire form: {@code [u16 ENTITY_CREATED_EVENT][u16 ENCODING_VERSION][PersistedEntityState]}.
 *
 * @Thread-context immutable, any thread.
 */
public record EntityCreatedEvent(PersistedEntityState entity) implements RegionEvent {

    /** @throws IllegalArgumentException if {@code entity} is null. */
    public EntityCreatedEvent {
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
        w.writeU16(TypeTags.ENTITY_CREATED_EVENT).writeU16(ENCODING_VERSION);
        entity.encode(w);
    }

    static EntityCreatedEvent decodeBody(CanonicalReader r) {
        return new EntityCreatedEvent(PersistedEntityState.decode(r));
    }
}
