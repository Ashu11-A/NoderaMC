package dev.nodera.protocol.wire;

/**
 * The physical shape of one TLV field's value (Task 14 phase 3).
 *
 * <p>The wire type is carried in the field header so a decoder that has never heard of a field id
 * still knows what it is skipping, and so a field whose type changed under it is rejected instead of
 * being reinterpreted. It is <b>not</b> a type system: the schema decides what a field means, and
 * this only says how many bytes it occupies and how they are laid out.
 *
 * <p>Codes are assigned explicitly and are permanent — the same rule as kinds. They are never
 * {@code ordinal()} ({@code Plan.7} D-R5).
 *
 * <p>Thread-context: immutable enum; any thread.
 */
public enum WireType {

    /** Unsigned 8-bit integer; value length is exactly 1. */
    U8(1, 1),
    /** Unsigned 16-bit integer, big-endian; value length is exactly 2. */
    U16(2, 2),
    /** Unsigned 32-bit integer, big-endian; value length is exactly 4. */
    U32(3, 4),
    /** Unsigned 64-bit integer, big-endian; value length is exactly 8. */
    U64(4, 8),
    /** A single byte that must be exactly 0 or 1 — one value, one spelling. */
    BOOL(5, 1),
    /** Raw bytes; the value is the payload, and its length is the field length. */
    BYTES(6, -1),
    /** UTF-8 text, validated strictly on decode. */
    STRING(7, -1),
    /** A nested TLV body — the same {@code fieldId | wireType | len | value} grammar, recursively. */
    NESTED(8, -1),
    /** {@code count:u32} followed by {@code count} × {@code len:u32 | elementBytes}. */
    LIST(9, -1);

    private final int code;
    private final int fixedLength;

    WireType(int code, int fixedLength) {
        this.code = code;
        this.fixedLength = fixedLength;
    }

    /** The permanent wire code. Never {@code ordinal()}. */
    public int code() {
        return code;
    }

    /** The exact value length this type requires, or {@code -1} when it is variable. */
    public int fixedLength() {
        return fixedLength;
    }

    /**
     * Resolve a wire code.
     *
     * @param code the byte from a field header.
     * @return the type.
     * @throws IllegalStateException if no type carries that code — a field whose type cannot be
     *         identified cannot be skipped safely, because its length would be the only thing
     *         standing between a hostile frame and the rest of the body.
     * @Thread-context any thread.
     */
    public static WireType fromCode(int code) {
        for (WireType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalStateException("unknown TLV wire type code " + code);
    }
}
