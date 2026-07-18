package dev.nodera.protocol.content;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * One piece's bytes, in answer to a {@link ContentRequest} (Task 19).
 *
 * <p><b>There is deliberately no {@code pieceHash} field.</b> The receiver verifies {@code payload}
 * against the hash the <i>manifest</i> pins for {@code index} — a hash travelling next to
 * attacker-supplied bytes proves nothing, since an attacker who forges the payload forges the
 * accompanying hash too. The manifest itself is trusted only once its root is referenced by a
 * certified checkpoint/commit (Task 9), so the trust chain is
 * {@code quorum certificate → manifestRoot → pieceHash → these bytes}.
 *
 * <p>{@code payload} is opaque. Under per-world encryption (Task 23) it is ciphertext and the
 * piece hash is over the ciphertext, so a seeder verifies and serves content it cannot read.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param manifestRoot the blob's manifest root — identifies which content this piece belongs to.
 * @param index        the piece index within that manifest.
 * @param payload      the piece bytes (ciphertext when the world is encrypted).
 */
public record ContentChunk(Bytes manifestRoot, int index, Bytes payload) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if an argument is null or {@code index} is negative.
     */
    public ContentChunk {
        Objects.requireNonNull(manifestRoot, "manifestRoot");
        Objects.requireNonNull(payload, "payload");
        if (index < 0) {
            throw new IllegalArgumentException("piece index must be non-negative: " + index);
        }
    }

    @Override
    public String toString() {
        return "ContentChunk[" + manifestRoot.toShortHex(6) + " #" + index
                + " " + payload.length() + "B]";
    }
}
