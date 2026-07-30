package dev.nodera.core.event;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.state.NetworkEntityId;

/**
 * A tracked entity left the region's validated entity table (Task 12a) — picked up, despawned, or
 * transferred out (Task 12c). Carries only the {@link NetworkEntityId}; the table row is removed.
 *
 * <p>Wire form: {@code [u16 ENTITY_REMOVED_EVENT][u16 ENCODING_VERSION][NetworkEntityId]}.
 *
 * @Thread-context immutable, any thread.
 */
public record EntityRemovedEvent(NetworkEntityId entityId) implements RegionEvent {

    /** @throws IllegalArgumentException if {@code entityId} is null. */
    public EntityRemovedEvent {
        if (entityId == null) {
            throw new IllegalArgumentException("entityId must not be null");
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.ENTITY_REMOVED_EVENT).writeU16(ENCODING_VERSION);
        entityId.encode(w);
    }

    static EntityRemovedEvent decodeBody(CanonicalReader r) {
        return new EntityRemovedEvent(NetworkEntityId.decode(r));
    }
}
