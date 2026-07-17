package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

/**
 * A single block-level state transition (Task 2 state/). Carries the target position, the
 * {@code expectedPreviousStateId} (CAS-style guard the validator checks against its local state),
 * the {@code newStateId} to apply, and a {@code flags} bitmask reserved for future per-mutation
 * metadata (e.g. "player-caused", "rollback-eligible"). State ids are written as {@code u32}.
 *
 * <p>Wire form: {@code [u16 BLOCK_MUTATION][u16 ENCODING_VERSION][NBlockPos][u32 expectedPreviousStateId]
     * [u32 newStateId][u32 flags]}.
 *
 * @Thread-context immutable, any thread.
 */
public record BlockMutation(
        NBlockPos pos,
        int expectedPreviousStateId,
        int newStateId,
        int flags
) implements Encodable {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code pos} is null.
     */
    public BlockMutation {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.BLOCK_MUTATION).writeU16(ENCODING_VERSION);
        pos.encode(w);
        w.writeU32(Integer.toUnsignedLong(expectedPreviousStateId));
        w.writeU32(Integer.toUnsignedLong(newStateId));
        w.writeU32(Integer.toUnsignedLong(flags));
    }

    /**
     * Full-frame decode.
     *
     * @throws IllegalStateException if the next tag is not {@code BLOCK_MUTATION}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static BlockMutation decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.BLOCK_MUTATION) {
            throw new IllegalStateException("expected BLOCK_MUTATION tag, got " + tag);
        }
        r.readU16();
        NBlockPos pos = NBlockPos.decode(r);
        int expectedPreviousStateId = (int) r.readU32();
        int newStateId = (int) r.readU32();
        int flags = (int) r.readU32();
        return new BlockMutation(pos, expectedPreviousStateId, newStateId, flags);
    }
}
