package dev.nodera.core.event;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.state.NBlockPos;

/**
 * A single block-state change observed by the simulation (Task 2 event/). Emitted when a delta
 * commits and a block's palette id transitions from {@code oldStateId} to {@code newStateId}.
 *
 * <p>Wire form: {@code [u16 BLOCK_CHANGED_EVENT][u16 ENCODING_VERSION][NBlockPos][u32 oldStateId]
     * [u32 newStateId]}.
 *
 * @Thread-context immutable, any thread.
 */
public record BlockChangedEvent(
        NBlockPos pos,
        int oldStateId,
        int newStateId
) implements RegionEvent {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code pos} is null.
     */
    public BlockChangedEvent {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.BLOCK_CHANGED_EVENT).writeU16(ENCODING_VERSION);
        encodeBody(w);
    }

    private void encodeBody(CanonicalWriter w) {
        pos.encode(w);
        w.writeU32(Integer.toUnsignedLong(oldStateId));
        w.writeU32(Integer.toUnsignedLong(newStateId));
    }

    /**
     * Full-frame decode (tag + version + body).
     *
     * @throws IllegalStateException if the next tag is not {@code BLOCK_CHANGED_EVENT}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static BlockChangedEvent decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.BLOCK_CHANGED_EVENT) {
            throw new IllegalStateException("expected BLOCK_CHANGED_EVENT tag, got " + tag);
        }
        r.readU16();
        return decodeBody(r);
    }

    static BlockChangedEvent decodeBody(CanonicalReader r) {
        NBlockPos pos = NBlockPos.decode(r);
        int oldStateId = (int) r.readU32();
        int newStateId = (int) r.readU32();
        return new BlockChangedEvent(pos, oldStateId, newStateId);
    }
}
