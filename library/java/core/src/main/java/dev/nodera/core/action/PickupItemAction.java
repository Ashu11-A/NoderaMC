package dev.nodera.core.action;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.state.NetworkEntityId;

/**
 * A player picking up an item entity (Task 12a). References the entity by its deterministic
 * {@link NetworkEntityId}; the engine removes the entity from the region root and the commit delta
 * credits the player's vanilla inventory (one-way credit — inventory itself stays out of the root
 * until the player lane, Task 16).
 *
 * <p>Wire form: {@code [u16 PICKUP_ITEM_ACTION][u16 ENCODING_VERSION][NetworkEntityId entityId]}.
 *
 * @Thread-context immutable, any thread.
 */
public record PickupItemAction(NetworkEntityId entityId) implements GameAction {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code entityId} is null.
     */
    public PickupItemAction {
        if (entityId == null) {
            throw new IllegalArgumentException("entityId must not be null");
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.PICKUP_ITEM_ACTION).writeU16(ENCODING_VERSION);
        encodeBody(w);
    }

    private void encodeBody(CanonicalWriter w) {
        entityId.encode(w);
    }

    static PickupItemAction decodeBody(CanonicalReader r) {
        return new PickupItemAction(NetworkEntityId.decode(r));
    }
}
