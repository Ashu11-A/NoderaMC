package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.StateRoot;
import dev.nodera.storage.ContentId;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Turns a frozen {@link RegionSnapshot} into a swarm-ready {@link Layout}: the canonical blob, the
 * piece plane over it, and the chunk→piece index (Task 19).
 *
 * <h2>The blob is exactly the frozen encoding</h2>
 *
 * <p>The bytes the pieces address are byte-for-byte {@code RegionSnapshot.encode(...)} — this class
 * re-walks that encoding incrementally only to learn <i>where</i> the chunk-column records begin,
 * and asserts the reconstruction equals the frozen output before returning. That is what makes the
 * two roots line up for free:
 *
 * <pre>
 *   regionRoot   = SHA-256(blob)                      // the Task 2 StateRoot, unchanged
 *   blob         = ContentId(SHA-256(blob), len, …)   // the Task 9 content address, unchanged
 *   manifestRoot = SHA-256(piece list)                // the Task 19 piece plane, new
 * </pre>
 *
 * <p>So a reassembled blob that hashes to {@code regionRoot} is provably the same region state the
 * committee committed — no committee-layer change was needed to gain a piece plane.
 *
 * <p>Thread-context: stateless static helpers; safe for any thread.
 */
public final class RegionSnapshotSplitter {

    /** Shared hasher — {@link HashService} is thread-safe by {@link ThreadLocal} confinement. */
    private static final HashService HASHES = new HashService();

    private RegionSnapshotSplitter() {}

    /**
     * The result of splitting a snapshot.
     *
     * @param snapshot     the source snapshot.
     * @param blob         its canonical encoding — the bytes the pieces slice.
     * @param manifest     the piece plane over {@code blob}.
     * @param pieceOfChunk {@code pieceOfChunk.get(i)} = index of the piece holding the
     *                     {@code i}-th chunk column (in the snapshot's canonical chunk order);
     *                     drives {@link ChunkLockMap} lookups by chunk.
     * @Thread-context immutable record, safe for any thread.
     */
    public record Layout(
            RegionSnapshot snapshot,
            Bytes blob,
            PieceManifest manifest,
            List<Integer> pieceOfChunk
    ) {
        /**
         * Compact constructor.
         *
         * @throws IllegalArgumentException if an argument is null.
         */
        public Layout {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(blob, "blob");
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(pieceOfChunk, "pieceOfChunk");
            pieceOfChunk = List.copyOf(pieceOfChunk);
        }

        /**
         * @param chunkOrdinal the chunk's position in the snapshot's canonical chunk order.
         * @return the index of the piece that must arrive before this chunk is usable.
         * @throws IndexOutOfBoundsException if {@code chunkOrdinal} is out of range.
         * @Thread-context any thread.
         */
        public int pieceForChunk(int chunkOrdinal) {
            return pieceOfChunk.get(chunkOrdinal);
        }
    }

    /**
     * Split {@code snapshot} at {@link PieceSplitter#DEFAULT_PIECE_TARGET_BYTES}.
     *
     * @param snapshot the snapshot to split.
     * @return the layout.
     * @Thread-context any thread.
     */
    public static Layout split(RegionSnapshot snapshot) {
        return split(snapshot, PieceSplitter.DEFAULT_PIECE_TARGET_BYTES);
    }

    /**
     * Split {@code snapshot} into pieces of roughly {@code pieceTargetBytes}, cutting only at
     * chunk-column record boundaries.
     *
     * @param snapshot         the snapshot to split.
     * @param pieceTargetBytes the packing goal per piece.
     * @return the layout: blob, manifest, and chunk→piece index.
     * @throws IllegalArgumentException if {@code snapshot} is null or {@code pieceTargetBytes} is
     *                                  not positive.
     * @throws IllegalStateException if the incremental re-encoding does not reproduce the frozen
     *                               {@code RegionSnapshot} encoding (a core encoding change that
     *                               this splitter has not been updated for).
     * @Thread-context any thread.
     */
    public static Layout split(RegionSnapshot snapshot, int pieceTargetBytes) {
        Objects.requireNonNull(snapshot, "snapshot");

        List<ChunkColumnState> chunks = snapshot.chunks();
        // Mirror RegionSnapshot.encode field for field, recording the offset at which each chunk
        // record starts. Record 0 is the frame header (tag, version, region, version, tick, list
        // count); it is not a chunk, so it is not in pieceOfChunk.
        CanonicalWriter w = new CanonicalWriter(1024);
        w.writeU16(TypeTags.REGION_SNAPSHOT).writeU16(Encodable.ENCODING_VERSION);
        snapshot.region().encode(w);
        snapshot.version().encode(w);
        w.writeU64(snapshot.tick());
        w.writeU32(chunks.size());

        int[] recordStarts = new int[chunks.size() + 1];
        recordStarts[0] = 0;
        for (int i = 0; i < chunks.size(); i++) {
            recordStarts[i + 1] = w.size();
            chunks.get(i).encode(w);
        }
        byte[] blob = w.toByteArray();

        // The piece plane must address the FROZEN bytes, not a look-alike. If core ever changes
        // RegionSnapshot's encoding, fail loudly here rather than shipping a manifest whose
        // regionRoot silently stops matching the committee's.
        CanonicalWriter frozenWriter = new CanonicalWriter(blob.length);
        snapshot.encode(frozenWriter);
        if (!Arrays.equals(blob, frozenWriter.toByteArray())) {
            throw new IllegalStateException(
                    "incremental snapshot encoding diverged from RegionSnapshot.encode — "
                            + "the frozen core encoding changed and RegionSnapshotSplitter was not updated");
        }

        List<Piece> pieces = PieceSplitter.split(blob, recordStarts, pieceTargetBytes);
        // recordStarts[0] is the header; chunk i corresponds to recordStarts[i + 1].
        List<Integer> pieceOfRecord = PieceSplitter.pieceOfRecord(recordStarts, pieces);
        List<Integer> pieceOfChunk = List.copyOf(pieceOfRecord.subList(1, pieceOfRecord.size()));

        Bytes blobHash = HASHES.sha256(blob);
        ContentId contentId = new ContentId(blobHash, blob.length, dev.nodera.storage.Compression.NONE);
        StateRoot regionRoot = StateRoot.of(blobHash);

        PieceManifest manifest = PieceManifest.of(
                snapshot.region(),
                snapshot.version(),
                snapshot.tick(),
                regionRoot,
                contentId,
                blob.length,
                pieces);

        return new Layout(snapshot, Bytes.unsafeWrap(blob), manifest, pieceOfChunk);
    }
}
