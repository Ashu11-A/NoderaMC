package dev.nodera.protocol.content;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.NoderaMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * "Send me these pieces of the blob whose manifest root is {@code manifestRoot}" (Task 19).
 *
 * <p>The request addresses content by <b>hash</b>, never by peer or path: any peer holding the
 * manifest can answer, which is what makes multi-seeder fetch possible. The answer is one
 * {@link ContentChunk} per requested index.
 *
 * <p>{@code pieceIndexes} is canonicalised on construction — de-duplicated and sorted ascending —
 * so a request for the same piece set always encodes to identical bytes regardless of the order
 * the selector emitted them in.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param manifestRoot the requested blob's manifest root (SHA-256 over its piece-hash list).
 * @param pieceIndexes the wanted piece indexes, de-duplicated and ascending.
 */
public record ContentRequest(Bytes manifestRoot, List<Integer> pieceIndexes) implements NoderaMessage {

    /**
     * Compact constructor: validates and canonicalises the index list.
     *
     * @throws IllegalArgumentException if an argument is null, the index list is empty, or any
     *                                  index is negative.
     */
    public ContentRequest {
        Objects.requireNonNull(manifestRoot, "manifestRoot");
        Objects.requireNonNull(pieceIndexes, "pieceIndexes");
        if (pieceIndexes.isEmpty()) {
            throw new IllegalArgumentException("pieceIndexes must not be empty");
        }
        TreeSet<Integer> sorted = new TreeSet<>();
        for (Integer i : pieceIndexes) {
            Objects.requireNonNull(i, "pieceIndex");
            if (i < 0) {
                throw new IllegalArgumentException("piece index must be non-negative: " + i);
            }
            sorted.add(i);
        }
        pieceIndexes = List.copyOf(new ArrayList<>(sorted));
    }

    /**
     * Convenience factory for a single-piece request.
     *
     * @param manifestRoot the blob's manifest root.
     * @param index        the wanted piece index.
     * @return the request.
     * @Thread-context any thread.
     */
    public static ContentRequest of(Bytes manifestRoot, int index) {
        return new ContentRequest(manifestRoot, List.of(index));
    }

    @Override
    public String toString() {
        return "ContentRequest[" + manifestRoot.toShortHex(6) + " x" + pieceIndexes.size() + "]";
    }
}
