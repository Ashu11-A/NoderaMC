package dev.nodera.protocol.codec;

import com.github.luben.zstd.Zstd;
import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;
import dev.nodera.protocol.simulationmsg.StreamChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compress-then-split streaming helper for large logical payloads (Task 4 §"ChunkedStreams").
 *
 * <p>The NeoForge transport caps a single serverbound payload at &lt;32 KiB; snapshots and deltas
 * are far larger. {@code ChunkedStreams} turns an arbitrary {@code byte[]} into a list of
 * {@link StreamChunk}s whose payload is at most {@link NoderaConstants#MAX_STREAM_CHUNK} bytes
 * (24 KiB, leaving comfortable headroom under the cap for frame overhead), and inverts the
 * transform on the receiver.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li><b>Compress-then-split, not per-chunk.</b> The whole logical payload is zstd-compressed
 *       <i>before</i> splitting, maximising the compression ratio (per-chunk compression would
 *       starve the dictionary of cross-window context and inflate total bytes roughly
 *       proportionally to {@code chunkCount}).</li>
 *   <li><b>Original length is out-of-band.</b> {@link StreamChunk} does not carry the original
 *       uncompressed length; the caller (typically {@code SnapshotAnnounce#contentLength()})
 *       supplies it to {@link #join(List, int)} so zstd can allocate the exact output buffer.
 *       This keeps the per-chunk frame minimal.</li>
 *   <li><b>Validation on join.</b> {@link #join(List, int)} checks that chunks are contiguous
 *       {@code 0..total-1}, share a stream id, and that {@code total} matches the supplied list
 *       size — a missing or duplicated chunk fails loudly rather than producing silently
 *       corrupted output.</li>
 * </ul>
 *
 * <p>Thread-context: pure functions, safe to call from any thread. No shared state.
 */
public final class ChunkedStreams {

    private ChunkedStreams() {}

    /**
     * Number of {@link StreamChunk}s needed to carry {@code compressedLength} bytes of
     * compressed payload at the {@link NoderaConstants#MAX_STREAM_CHUNK} chunk size. Rounds up;
     * always returns at least {@code 1} for non-negative input.
     *
     * @param compressedLength the post-zstd compressed byte length.
     * @return chunk count; {@code 1} if {@code compressedLength <= MAX_STREAM_CHUNK} (incl. 0).
     * @throws IllegalArgumentException if {@code compressedLength < 0}.
     * @Thread-context any thread.
     */
    public static int chunkCountFor(int compressedLength) {
        if (compressedLength < 0) {
            throw new IllegalArgumentException("compressedLength must be non-negative");
        }
        int max = NoderaConstants.MAX_STREAM_CHUNK;
        if (compressedLength == 0) {
            return 1;
        }
        return (compressedLength + max - 1) / max;
    }

    /**
     * Split a logical payload into a list of {@link StreamChunk}s for transport.
     *
     * <p>Steps: (1) zstd-compress the whole payload; (2) cut the compressed bytes into
     * {@link NoderaConstants#MAX_STREAM_CHUNK}-sized pieces; (3) wrap each piece in a
     * {@link StreamChunk} tagged with {@code streamId}, its 0-based {@code index}, and the total
     * count. The original (uncompressed) length is NOT stored on the chunks — pass it to
     * {@link #join(List, int)} on the receiver.
     *
     * @param streamId logical stream identifier (unique per sender).
     * @param payload  the uncompressed logical payload.
     * @return a non-empty list of chunks, each with {@code payload.length() ≤ MAX_STREAM_CHUNK}.
     * @throws IllegalArgumentException if {@code payload} is null.
     * @Thread-context any thread.
     */
    public static List<StreamChunk> split(long streamId, byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        byte[] compressed = Zstd.compress(payload);
        int total = chunkCountFor(compressed.length);
        int max = NoderaConstants.MAX_STREAM_CHUNK;
        List<StreamChunk> chunks = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            int from = index * max;
            int to = Math.min(from + max, compressed.length);
            int len = to - from;
            byte[] piece = new byte[len];
            System.arraycopy(compressed, from, piece, 0, len);
            assert len <= NoderaConstants.MAX_STREAM_CHUNK
                    : "chunk payload exceeds MAX_STREAM_CHUNK";
            chunks.add(new StreamChunk(streamId, index, total, Bytes.unsafeWrap(piece)));
        }
        return chunks;
    }

    /**
     * Reassemble a logical payload from its {@link StreamChunk} list.
     *
     * <p>Steps: (1) validate the chunks are contiguous {@code 0..total-1}, all share the same
     * stream id, and {@code total} matches the list size; (2) concatenate payloads in index
     * order to recover the compressed bytes; (3) zstd-decompress with {@code originalLength} as
     * the output buffer size.
     *
     * @param chunks         the chunks, in any order; indices must cover {@code 0..total-1}.
     * @param originalLength the original (uncompressed) byte length, as supplied to / inferred
     *                       by the sender (typically carried in a {@code SnapshotAnnounce}).
     * @return the original uncompressed payload.
     * @throws IllegalArgumentException if {@code chunks} is null/empty, or any validation fails.
     * @Thread-context any thread.
     */
    public static byte[] join(List<StreamChunk> chunks, int originalLength) {
        Objects.requireNonNull(chunks, "chunks");
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be empty");
        }
        if (originalLength < 0) {
            throw new IllegalArgumentException("originalLength must be non-negative");
        }
        int total = chunks.get(0).total();
        if (total != chunks.size()) {
            throw new IllegalArgumentException(
                    "chunk total " + total + " does not match list size " + chunks.size());
        }
        long streamId = chunks.get(0).streamId();

        StreamChunk[] ordered = new StreamChunk[total];
        for (StreamChunk c : chunks) {
            if (c.streamId() != streamId) {
                throw new IllegalArgumentException(
                        "mixed stream ids in join: " + streamId + " vs " + c.streamId());
            }
            if (c.total() != total) {
                throw new IllegalArgumentException(
                        "inconsistent chunk total: " + c.total() + " vs " + total);
            }
            if (c.index() < 0 || c.index() >= total) {
                throw new IllegalArgumentException("chunk index out of range: " + c.index());
            }
            if (ordered[c.index()] != null) {
                throw new IllegalArgumentException("duplicate chunk index: " + c.index());
            }
            ordered[c.index()] = c;
        }
        int compressedLength = 0;
        for (StreamChunk c : ordered) {
            compressedLength += c.payload().length();
        }
        byte[] compressed = new byte[compressedLength];
        int off = 0;
        for (StreamChunk c : ordered) {
            c.payload().copyInto(compressed, off);
            off += c.payload().length();
        }
        return Zstd.decompress(compressed, originalLength);
    }
}
