package dev.nodera.core.action;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.state.FixedVec3;

/**
 * A player dropping an item stack into the world (Task 12a). The engine allocates a deterministic
 * {@link dev.nodera.core.state.NetworkEntityId} for the new item entity from the batch context
 * (NOT carried by the action — the player never picks the id), then validates fixed-point item
 * physics from {@code origin}. {@code itemStackId}/{@code count} identify the stack; the action
 * itself carries no float state.
 *
 * <p>Wire form: {@code [u16 DROP_ITEM_ACTION][u16 ENCODING_VERSION][u32 itemStackId][u8 count]
 * [FixedVec3 origin]}.
 *
 * @Thread-context immutable, any thread.
 */
public record DropItemAction(int itemStackId, int count, FixedVec3 origin) implements GameAction {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code origin} is null or {@code count} is not positive.
     */
    public DropItemAction {
        if (origin == null) {
            throw new IllegalArgumentException("origin must not be null");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive: " + count);
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.DROP_ITEM_ACTION).writeU16(ENCODING_VERSION);
        encodeBody(w);
    }

    private void encodeBody(CanonicalWriter w) {
        w.writeU32(Integer.toUnsignedLong(itemStackId));
        w.writeU8(count);
        origin.encode(w);
    }

    /**
     * Full-frame decode (tag + version + body).
     *
     * @throws IllegalStateException if the next tag is not {@code DROP_ITEM_ACTION}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static DropItemAction decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.DROP_ITEM_ACTION) {
            throw new IllegalStateException("expected DROP_ITEM_ACTION tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        return decodeBody(r);
    }

    static DropItemAction decodeBody(CanonicalReader r) {
        int itemStackId = (int) r.readU32();
        int count = r.readU8();
        FixedVec3 origin = FixedVec3.decode(r);
        return new DropItemAction(itemStackId, count, origin);
    }
}
