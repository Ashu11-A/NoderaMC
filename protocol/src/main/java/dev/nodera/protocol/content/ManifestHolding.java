package dev.nodera.protocol.content;

import dev.nodera.core.Bytes;

import java.util.Objects;

/**
 * One entry of a {@link ContentAvailability} advertisement: "of the blob {@code manifestRoot}, I
 * hold the pieces marked in {@code pieceBitmap}" (Task 19).
 *
 * <p>Holdings are piece-level rather than manifest-level on purpose: rarest-first selection and
 * partial seeders (the Task 19 acceptance requires reassembly from holders each holding &lt;40% of
 * the pieces) cannot be expressed at manifest granularity. Task 20's {@code ArchiveInventory} and
 * Task 21's placement audit both index these entries.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param manifestRoot the blob's manifest root.
 * @param pieceBitmap  held piece indexes, packed per {@link PieceBitmap}.
 */
public record ManifestHolding(Bytes manifestRoot, Bytes pieceBitmap) {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if an argument is null.
     */
    public ManifestHolding {
        Objects.requireNonNull(manifestRoot, "manifestRoot");
        Objects.requireNonNull(pieceBitmap, "pieceBitmap");
    }

    /**
     * @param index the piece index to test.
     * @return {@code true} if this holding claims that piece.
     * @Thread-context any thread.
     */
    public boolean holds(int index) {
        return PieceBitmap.holds(pieceBitmap, index);
    }

    /**
     * @return how many pieces this holding claims.
     * @Thread-context any thread.
     */
    public int pieceCount() {
        return PieceBitmap.count(pieceBitmap);
    }

    @Override
    public String toString() {
        return "ManifestHolding[" + manifestRoot.toShortHex(6) + " x" + pieceCount() + "]";
    }
}
