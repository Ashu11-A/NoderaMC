package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeId;

/**
 * Replay-safe one-way inventory effect emitted when a player picks up an item (Task 12a). The
 * identity key is {@code (actor, entityId)}: replaying the same certified delta cannot credit the
 * stack twice. Player inventory itself remains outside the region root until Task 16.
 *
 * <p>Wire form: {@code [u16 INVENTORY_CREDIT][u16 ENCODING_VERSION][NodeId actor]
 * [NetworkEntityId][u32 itemStackId][u8 count]}.
 *
 * @Thread-context immutable, any thread.
 */
public record InventoryCredit(
        NodeId actor,
        NetworkEntityId entityId,
        int itemStackId,
        int count
) implements Encodable {

    public InventoryCredit {
        if (actor == null) {
            throw new IllegalArgumentException("actor must not be null");
        }
        if (entityId == null) {
            throw new IllegalArgumentException("entityId must not be null");
        }
        if (count <= 0 || count > 255) {
            throw new IllegalArgumentException("count must be in [1, 255]: " + count);
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.INVENTORY_CREDIT).writeU16(ENCODING_VERSION);
        actor.encode(w);
        entityId.encode(w);
        w.writeU32(Integer.toUnsignedLong(itemStackId));
        w.writeU8(count);
    }

    /** Decode one full inventory-credit frame. */
    public static InventoryCredit decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.INVENTORY_CREDIT) {
            throw new IllegalStateException("expected INVENTORY_CREDIT tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        return new InventoryCredit(
                NodeId.decode(r),
                NetworkEntityId.decode(r),
                (int) r.readU32(),
                r.readU8());
    }
}
