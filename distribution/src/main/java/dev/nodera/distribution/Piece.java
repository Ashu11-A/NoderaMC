package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

import java.util.Objects;

/**
 * One addressable slice of a content blob (Task 19). A piece is the unit the swarm actually moves:
 * it has its own SHA-256, so it can be requested from any peer that holds it and verified on
 * arrival without trusting the sender.
 *
 * <p>The parent blob's {@code ContentId} deliberately lives once on the {@link PieceManifest}, not
 * repeated per piece — a manifest of a few thousand pieces would otherwise carry a few thousand
 * redundant copies of the same 32-byte hash.
 *
 * <p>Wire form: {@code [u16 PIECE][u16 ENCODING_VERSION][u32 index][u64 offset][u64 length]
 * [bytes pieceHash]}.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param index     position of this piece in the blob (0-based, contiguous, ascending).
 * @param offset    byte offset of this piece within the parent blob.
 * @param length    byte length of this piece; always {@code > 0}.
 * @param pieceHash SHA-256 over exactly this piece's bytes (ciphertext bytes when encrypted).
 */
public record Piece(int index, long offset, long length, Bytes pieceHash) implements Encodable {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code pieceHash} is null or not 32 bytes, if
     *                                  {@code index}/{@code offset} is negative, or if
     *                                  {@code length} is not positive.
     */
    public Piece {
        Objects.requireNonNull(pieceHash, "pieceHash");
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative: " + index);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative: " + offset);
        }
        // A zero-length piece is never legitimate: it would carry no data yet still occupy an
        // index, letting two different piece layouts of the same blob produce different roots.
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive: " + length);
        }
        if (pieceHash.length() != NoderaConstants.STATE_ROOT_BYTES) {
            throw new IllegalArgumentException(
                    "pieceHash must be " + NoderaConstants.STATE_ROOT_BYTES
                            + " bytes, got " + pieceHash.length());
        }
    }

    /** @return the exclusive end offset of this piece within the parent blob. */
    public long endOffset() {
        return offset + length;
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.PIECE).writeU16(ENCODING_VERSION);
        w.writeU32(Integer.toUnsignedLong(index));
        w.writeU64(offset);
        w.writeU64(length);
        w.writeBytes(pieceHash);
    }

    /**
     * Full-frame decode.
     *
     * @param r the reader positioned at this piece's tag.
     * @return the decoded piece.
     * @throws IllegalStateException if the next tag is not {@code PIECE}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static Piece decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.PIECE) {
            throw new IllegalStateException("expected PIECE tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        int index = (int) r.readU32();
        long offset = r.readU64();
        long length = r.readU64();
        Bytes hash = r.readBytesValue();
        return new Piece(index, offset, length, hash);
    }

    @Override
    public String toString() {
        return "Piece[#" + index + " @" + offset + "+" + length + " " + pieceHash.toShortHex(4) + "]";
    }
}
