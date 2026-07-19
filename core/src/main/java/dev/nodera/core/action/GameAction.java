package dev.nodera.core.action;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

/**
 * Root of the sealed {@code GameAction} hierarchy (Task 2 action/). A {@code GameAction} is one
 * discrete, signed unit of player/server intent against a region. The hierarchy is
 * <b>self-describing on the wire</b>: each permit opens its encoded form with its own typeTag, so
 * a decoder reads the next {@code u16} tag to know which concrete action follows. New action kinds
 * are appended by adding a new permit and a new {@link TypeTags} constant (never renumber).
 *
 * <p>Current permits: {@link PlaceBlockAction}, {@link BreakBlockAction},
 * {@link DropItemAction}, {@link PickupItemAction}. Later tasks append interaction/entity-attack
 * actions against the reserved tags in {@link TypeTags}.
 *
 * @Thread-context immutable, any thread.
 */
public sealed interface GameAction extends Encodable
        permits PlaceBlockAction, BreakBlockAction, DropItemAction, PickupItemAction {

    /**
     * Decode a polymorphic {@code GameAction} by reading the next typeTag and dispatching to the
     * matching permit. Throws on an unknown tag so a peer that sends garbage cannot be confused
     * with a valid action stream.
     *
     * @param r the reader, positioned at the typeTag.
     * @return the decoded action.
     * @throws IllegalStateException if the tag does not name a known {@code GameAction} permit.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    static GameAction decode(CanonicalReader r) {
        int tag = r.readU16();
        r.readVersion(ENCODING_VERSION);
        return switch (tag) {
            case TypeTags.PLACE_BLOCK_ACTION -> PlaceBlockAction.decodeBody(r);
            case TypeTags.BREAK_BLOCK_ACTION -> BreakBlockAction.decodeBody(r);
            case TypeTags.DROP_ITEM_ACTION -> DropItemAction.decodeBody(r);
            case TypeTags.PICKUP_ITEM_ACTION -> PickupItemAction.decodeBody(r);
            default -> throw new IllegalStateException("unknown GameAction tag " + tag);
        };
    }
}
