package dev.nodera.core.action;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NBlockPos;

/**
 * A player moving one stack between their inventory and a container block (Task 16 / L-10).
 * Carries the container position, the actor's position (reach validation), the slot index, and
 * the item + count — the committee validates the container kind, slot bounds, reach, and the
 * slot's current contents before the mutation enters the root; a WITHDRAW credits the player's
 * inventory through the same replay-safe one-way lane as item pickup.
 *
 * <p>Wire form: {@code [u16 CONTAINER_ACTION][u16 ENCODING_VERSION][NBlockPos pos]
 * [FixedVec3 origin][u8 mode][u8 slot][u32 itemStackId][u8 count]}.
 *
 * @Thread-context immutable, any thread.
 */
public record ContainerAction(
        NBlockPos pos, FixedVec3 origin, Mode mode, int slot, int itemStackId, int count)
        implements GameAction {

    /** The transfer direction. */
    public enum Mode {
        /** Player → container. */
        DEPOSIT,
        /** Container → player (credits inventory). */
        WITHDRAW
    }

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if a reference is null or a bound is violated.
     */
    public ContainerAction {
        if (pos == null || origin == null || mode == null) {
            throw new IllegalArgumentException("pos, origin, and mode must not be null");
        }
        if (slot < 0 || slot > 255) {
            throw new IllegalArgumentException("slot must be in [0, 255]: " + slot);
        }
        if (count <= 0 || count > 255) {
            throw new IllegalArgumentException("count must be in [1, 255]: " + count);
        }
        if (itemStackId == 0) {
            throw new IllegalArgumentException("itemStackId must be non-zero");
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeFrame(TypeTags.CONTAINER_ACTION, ENCODING_VERSION);
        encodeBody(w);
    }

    private void encodeBody(CanonicalWriter w) {
        pos.encode(w);
        origin.encode(w);
        w.writeU8(mode.ordinal());
        w.writeU8(slot);
        w.writeU32(Integer.toUnsignedLong(itemStackId));
        w.writeU8(count);
    }

    /**
     * Full-frame decode (tag + version + body).
     *
     * @throws IllegalStateException if the next tag is not {@code CONTAINER_ACTION}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static ContainerAction decode(CanonicalReader r) {
        r.expectFrame(TypeTags.CONTAINER_ACTION, "CONTAINER_ACTION", ENCODING_VERSION);
        return decodeBody(r);
    }

    static ContainerAction decodeBody(CanonicalReader r) {
        NBlockPos pos = NBlockPos.decode(r);
        FixedVec3 origin = FixedVec3.decode(r);
        int modeOrdinal = r.readU8();
        if (modeOrdinal >= Mode.values().length) {
            throw new IllegalStateException("unknown container-action mode " + modeOrdinal);
        }
        Mode mode = Mode.values()[modeOrdinal];
        int slot = r.readU8();
        int itemStackId = (int) r.readU32();
        int count = r.readU8();
        return new ContainerAction(pos, origin, mode, slot, itemStackId, count);
    }
}
