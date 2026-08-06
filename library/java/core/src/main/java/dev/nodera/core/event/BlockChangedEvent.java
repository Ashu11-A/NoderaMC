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

    static BlockChangedEvent decodeBody(CanonicalReader r) {
        NBlockPos pos = NBlockPos.decode(r);
        int oldStateId = r.readU32AsInt();
        int newStateId = r.readU32AsInt();
        return new BlockChangedEvent(pos, oldStateId, newStateId);
    }
}
