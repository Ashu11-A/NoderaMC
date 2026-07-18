package dev.nodera.protocol.content;

import dev.nodera.core.Bytes;

import java.util.BitSet;
import java.util.Collection;
import java.util.Objects;

/**
 * The wire convention for "which piece indexes does a peer hold" (Task 19). A holdings bitmap is a
 * little-endian bit array: piece index {@code i} lives in byte {@code i / 8} at bit
 * {@code i % 8} counted from the least-significant bit — exactly {@link BitSet#toByteArray()} /
 * {@link BitSet#valueOf(byte[])}. Fixing the convention here (in {@code protocol}, next to the
 * message that carries it) keeps it a single wire contract that {@code distribution} (selection)
 * and {@code peer-runtime} (inventory) both read rather than each inventing their own packing.
 *
 * <p>Trailing all-zero bytes are NOT written, so the encoded form is canonical: two peers holding
 * the same piece set always produce byte-identical bitmaps regardless of the manifest's piece
 * count. Reading a bit beyond the encoded length yields {@code false} (not held), which is the
 * correct default for a shorter-than-expected bitmap.
 *
 * <p>Thread-context: stateless static helpers; safe for any thread. The {@link BitSet} instances
 * handed in and out are caller-owned and never retained.
 */
public final class PieceBitmap {

    private PieceBitmap() {}

    /** An empty bitmap — the holder has no pieces of the manifest. */
    public static Bytes empty() {
        return Bytes.empty();
    }

    /**
     * Pack the given piece indexes into a canonical bitmap.
     *
     * @param indexes the held piece indexes (order irrelevant; duplicates ignored).
     * @return the packed bitmap.
     * @throws IllegalArgumentException if {@code indexes} is null or contains a negative index.
     * @Thread-context any thread.
     */
    public static Bytes of(Collection<Integer> indexes) {
        Objects.requireNonNull(indexes, "indexes");
        BitSet bits = new BitSet();
        for (Integer i : indexes) {
            Objects.requireNonNull(i, "index");
            if (i < 0) {
                throw new IllegalArgumentException("piece index must be non-negative: " + i);
            }
            bits.set(i);
        }
        return pack(bits);
    }

    /**
     * Pack a {@link BitSet} into a canonical bitmap.
     *
     * @param bits the held piece indexes as a bit set.
     * @return the packed bitmap.
     * @throws IllegalArgumentException if {@code bits} is null.
     * @Thread-context any thread.
     */
    public static Bytes pack(BitSet bits) {
        Objects.requireNonNull(bits, "bits");
        // BitSet.toByteArray() already omits trailing zero bytes, which is what makes the
        // encoding canonical.
        return Bytes.unsafeWrap(bits.toByteArray());
    }

    /**
     * Unpack a bitmap into a fresh, caller-owned {@link BitSet}.
     *
     * @param bitmap the packed bitmap.
     * @return the held piece indexes as a bit set.
     * @throws IllegalArgumentException if {@code bitmap} is null.
     * @Thread-context any thread.
     */
    public static BitSet unpack(Bytes bitmap) {
        Objects.requireNonNull(bitmap, "bitmap");
        return BitSet.valueOf(bitmap.toArray());
    }

    /**
     * Test one piece index without materialising a {@link BitSet}.
     *
     * @param bitmap the packed bitmap.
     * @param index  the piece index to test.
     * @return {@code true} if the holder claims that piece.
     * @throws IllegalArgumentException if {@code bitmap} is null or {@code index} is negative.
     * @Thread-context any thread.
     */
    public static boolean holds(Bytes bitmap, int index) {
        Objects.requireNonNull(bitmap, "bitmap");
        if (index < 0) {
            throw new IllegalArgumentException("piece index must be non-negative: " + index);
        }
        int byteIndex = index >>> 3;
        if (byteIndex >= bitmap.length()) {
            // Beyond the encoded length: a shorter bitmap means "not held", never an error —
            // trailing zero bytes are elided by the canonical packing.
            return false;
        }
        byte[] raw = bitmap.toArray();
        return (raw[byteIndex] & (1 << (index & 7))) != 0;
    }

    /**
     * @param bitmap the packed bitmap.
     * @return how many pieces the holder claims.
     * @Thread-context any thread.
     */
    public static int count(Bytes bitmap) {
        return unpack(bitmap).cardinality();
    }
}
