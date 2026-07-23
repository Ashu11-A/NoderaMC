package dev.nodera.core.action;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.state.NBlockPos;

/**
 * A player placing (or replacing) a block (Task 2 action/). {@code face} is the Minecraft block
 * face index in {@code [0,5]}: {@code 0=DOWN, 1=UP, 2=NORTH, 3=SOUTH, 4=WEST, 5=EAST}. Encoded as a
 * {@code u8}. {@code blockStateId} is the target palette entry; it is written as a {@code u32} so
 * the full unsigned id range is transportable.
 *
 * <p>Wire form: {@code [u16 PLACE_BLOCK_ACTION][u16 ENCODING_VERSION][NBlockPos][u32 blockStateId]
 * [u8 face]}.
 *
 * @Thread-context immutable, any thread.
 */
public record PlaceBlockAction(NBlockPos pos, int blockStateId, int face) implements GameAction {

    /** Minimum legal face index (DOWN). */
    public static final int FACE_MIN = 0;
    /** Maximum legal face index (EAST). */
    public static final int FACE_MAX = 5;

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code pos} is null or {@code face} is outside
     *                                  {@code [FACE_MIN, FACE_MAX]}.
     */
    public PlaceBlockAction {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
        if (face < FACE_MIN || face > FACE_MAX) {
            throw new IllegalArgumentException("face must be in [" + FACE_MIN + "," + FACE_MAX + "]: " + face);
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.PLACE_BLOCK_ACTION).writeU16(ENCODING_VERSION);
        encodeBody(w);
    }

    private void encodeBody(CanonicalWriter w) {
        pos.encode(w);
        w.writeU32(Integer.toUnsignedLong(blockStateId));
        w.writeU8(face);
    }

    /**
     * Full-frame decode (reads tag + version + body).
     *
     * @throws IllegalStateException if the next tag is not {@code PLACE_BLOCK_ACTION}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static PlaceBlockAction decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.PLACE_BLOCK_ACTION) {
            throw new IllegalStateException("expected PLACE_BLOCK_ACTION tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        return decodeBody(r);
    }

    static PlaceBlockAction decodeBody(CanonicalReader r) {
        NBlockPos pos = NBlockPos.decode(r);
        int blockStateId = r.readU32AsInt();
        int face = r.readU8();
        return new PlaceBlockAction(pos, blockStateId, face);
    }
}
