package dev.nodera.core.event;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

/**
 * Root of the sealed {@code RegionEvent} hierarchy (Task 2 event/). A {@code RegionEvent} is one
 * observable side-effect of committing a delta: block changes (now), and later entity, scheduled
 * tick, and block-entity events. Like {@code GameAction}, the hierarchy is self-describing on the
 * wire: each permit opens its encoded form with its own typeTag, so {@link #decodeEvent} reads the
 * next {@code u16} tag to know which concrete event follows.
 *
 * <p>Current permits: {@link BlockChangedEvent}, {@link EntityCreatedEvent},
 * {@link EntityUpdatedEvent}, {@link EntityRemovedEvent}. Later tasks append scheduled-tick and
 * block-entity events against reserved tags in {@link TypeTags}.
 *
 * @Thread-context immutable, any thread.
 */
public sealed interface RegionEvent extends Encodable
        permits BlockChangedEvent, EntityCreatedEvent, EntityUpdatedEvent, EntityRemovedEvent {

    /**
     * Decode a polymorphic {@code RegionEvent} by reading the next typeTag and dispatching to the
     * matching permit. Throws on an unknown tag so a malformed stream cannot masquerade as a known
     * event.
     *
     * @param r the reader, positioned at the typeTag.
     * @return the decoded event.
     * @throws IllegalStateException if the tag does not name a known {@code RegionEvent} permit.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    static RegionEvent decodeEvent(CanonicalReader r) {
        int tag = r.readU16();
        r.readVersion(ENCODING_VERSION);
        return switch (tag) {
            case TypeTags.BLOCK_CHANGED_EVENT -> BlockChangedEvent.decodeBody(r);
            case TypeTags.ENTITY_CREATED_EVENT -> EntityCreatedEvent.decodeBody(r);
            case TypeTags.ENTITY_UPDATED_EVENT -> EntityUpdatedEvent.decodeBody(r);
            case TypeTags.ENTITY_REMOVED_EVENT -> EntityRemovedEvent.decodeBody(r);
            default -> throw new IllegalStateException("unknown RegionEvent tag " + tag);
        };
    }
}
