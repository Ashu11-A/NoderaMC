package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * One chunk of a chunked+compressed logical stream (Task 4 §"ChunkedStreams").
 *
 * <p>A stream is identified by its {@code streamId} (unique per sender); {@code index} ranges
 * {@code 0..total-1} contiguously. The {@code payload} is at most
 * {@link NoderaConstants#MAX_STREAM_CHUNK} bytes so the encoded {@code StreamChunk} frame fits
 * comfortably under NeoForge's &lt;32 KiB serverbound cap with frame overhead.
 *
 * <p>{@link Bytes} is immutable; the constructor null-checks and adopts it as-is.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param streamId logical stream identifier (unique per sender).
 * @param index    zero-based chunk index.
 * @param total    total chunk count for the stream.
 * @param payload  raw chunk bytes; length ≤ {@link NoderaConstants#MAX_STREAM_CHUNK}.
 */
public record StreamChunk(long streamId, int index, int total, Bytes payload)
        implements NoderaMessage {

    /**
     * Compact constructor. Enforces the documented invariants: non-null payload within the
     * serverbound-chunk cap, and a sane {@code index}/{@code total} ({@code total >= 1},
     * {@code 0 <= index < total}).
     *
     * @throws IllegalArgumentException if {@code payload} is null, the payload exceeds
     *                                  {@link NoderaConstants#MAX_STREAM_CHUNK}, or the
     *                                  index/total are inconsistent.
     */
    public StreamChunk {
        Objects.requireNonNull(payload, "payload");
        if (payload.length() > NoderaConstants.MAX_STREAM_CHUNK) {
            throw new IllegalArgumentException(
                    "StreamChunk payload " + payload.length()
                            + " exceeds MAX_STREAM_CHUNK " + NoderaConstants.MAX_STREAM_CHUNK);
        }
        if (total < 1) {
            throw new IllegalArgumentException("StreamChunk total must be >= 1: " + total);
        }
        if (index < 0 || index >= total) {
            throw new IllegalArgumentException(
                    "StreamChunk index " + index + " out of range for total " + total);
        }
    }
}
