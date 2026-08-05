package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.ChunkStamp;
import dev.nodera.core.state.ChunkStampBook;
import dev.nodera.core.state.Hlc;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionChunkIndex;
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

    /**
     * Piece target meaning "cut at every boundary" — one piece per chunk column.
     *
     * <h2>Why a region is cut per column rather than to a byte target</h2>
     *
     * <p>A byte target packs several columns into one piece, and where the cuts land depends on how
     * big the columns happen to be. A column that flips from sparse to dense — one player building —
     * moves every later cut point, so pieces the receiver already holds get new hashes and the reuse
     * this whole index exists to enable collapses. That is the same defect the whole-save archive has
     * at file granularity, re-created at region granularity.
     *
     * <p>Cutting per column pins the boundaries to the structure instead of to the sizes. An
     * unchanged column is a byte-identical piece however much its neighbours grew — it merely moves,
     * and the piece plane addresses pieces by hash, so moving is free. It also makes
     * {@code pieceOfChunk} the identity map, which turns {@link Layout#piecesChangedSince} into
     * exactly {@link RegionChunkIndex#changedSince} and makes
     * {@link ChunkLockMap#isChunkEditable} exact rather than conservative.
     *
     * <p>Cost: 64 pieces per region instead of a handful, and a sparse column is only a few hundred
     * bytes. That is 64 piece hashes in the manifest — cheap against re-sending columns nobody
     * touched.
     */
    public static final int PIECE_PER_COLUMN = 1;

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
         * @return the region's chunk index, or {@code null} for a manifest built before indexes
         *         existed. Never absent for a layout this class produced.
         */
        public dev.nodera.core.state.RegionChunkIndex chunkIndex() {
            return manifest.chunkIndex();
        }

        /**
         * The pieces that have to arrive for this layout to be reachable from {@code held}.
         *
         * <p>The whole point of the chunk index: a peer holding an earlier version of this region
         * asks which columns differ, maps each to the piece carrying it, and fetches that set
         * instead of the region. Pieces cut only at chunk boundaries, so this is exact — a piece is
         * either entirely made of unchanged columns or it is needed.
         *
         * @param held what the asking peer already has, or {@code null} for nothing.
         * @return the piece indices to fetch, ascending and duplicate-free.
         * @Thread-context any thread.
         */
        public java.util.SortedSet<Integer> piecesChangedSince(
                dev.nodera.core.state.RegionChunkIndex held) {
            // Delegated: the manifest carries both the index and the mapping, so a peer that only
            // ever receives a manifest computes the identical fetch set. Keeping a second copy of
            // this logic here is how a seeder and a fetcher come to disagree about what "changed"
            // means.
            return manifest.piecesChangedSince(held);
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
     * Split {@code snapshot} one piece per chunk column ({@link #PIECE_PER_COLUMN}).
     *
     * @param snapshot the snapshot to split.
     * @return the layout.
     * @Thread-context any thread.
     */
    public static Layout split(RegionSnapshot snapshot) {
        return split(snapshot, PIECE_PER_COLUMN, null);
    }

    /**
     * Split one piece per chunk column, stamping columns from {@code book}.
     *
     * @param snapshot the snapshot to split.
     * @param book     where this node records when each column was last written; {@code null} to
     *                 stamp every column from the snapshot alone.
     * @return the layout.
     * @Thread-context any thread.
     */
    public static Layout split(RegionSnapshot snapshot, ChunkStampBook book) {
        return split(snapshot, PIECE_PER_COLUMN, book);
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
        return split(snapshot, pieceTargetBytes, null);
    }

    /**
     * Split {@code snapshot} into pieces of roughly {@code pieceTargetBytes}, cutting only at
     * chunk-column record boundaries, and index every column.
     *
     * @param snapshot         the snapshot to split.
     * @param pieceTargetBytes the packing goal per piece.
     * @param book             where this node records when each column was last written;
     *                         {@code null} to stamp every column from the snapshot alone, which is
     *                         deterministic and therefore identical on every peer that packs the
     *                         same snapshot.
     * @return the layout: blob, manifest (carrying the chunk index), and chunk→piece index.
     * @throws IllegalArgumentException if {@code snapshot} is null or {@code pieceTargetBytes} is
     *                                  not positive.
     * @throws IllegalStateException if the incremental re-encoding does not reproduce the frozen
     *                               {@code RegionSnapshot} encoding.
     * @Thread-context any thread.
     */
    public static Layout split(RegionSnapshot snapshot, int pieceTargetBytes, ChunkStampBook book) {
        Objects.requireNonNull(snapshot, "snapshot");

        List<ChunkColumnState> chunks = snapshot.chunks();
        List<PersistedEntityState> entities = snapshot.entities();
        // Mirror RegionSnapshot.encode field for field, recording the offset at which each chunk
        // record starts. Record 0 is the frame header (tag, version, region, version, tick, list
        // count); it is not a chunk, so it is not in pieceOfChunk.
        CanonicalWriter w = new CanonicalWriter(1024);
        w.writeU16(TypeTags.REGION_SNAPSHOT).writeU16(snapshot.bodyVersion());
        snapshot.region().encode(w);
        snapshot.version().encode(w);
        w.writeU64(snapshot.tick());
        w.writeU32(chunks.size());

        boolean perColumn = pieceTargetBytes == PIECE_PER_COLUMN;
        // Per-column mode declares the entity list as ONE record rather than one per entity. Cutting
        // per entity would give a manifest with a piece hash per mob, and — worse — would make the
        // last column's piece move whenever an entity was added, which is the boundary drift the
        // per-column cut exists to avoid.
        int entityRecordCount = snapshot.bodyVersion() >= 2 ? (perColumn ? 1 : entities.size()) : 0;
        int[] recordStarts = new int[chunks.size() + entityRecordCount + 1];
        recordStarts[0] = 0;
        for (int i = 0; i < chunks.size(); i++) {
            recordStarts[i + 1] = w.size();
            chunks.get(i).encode(w);
        }
        if (snapshot.bodyVersion() >= 2) {
            if (perColumn) {
                recordStarts[chunks.size() + 1] = w.size();
            }
            w.writeU32(entities.size());
            for (int i = 0; i < entities.size(); i++) {
                if (!perColumn) {
                    recordStarts[chunks.size() + i + 1] = w.size();
                }
                entities.get(i).encode(w);
            }
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
        List<Integer> pieceOfChunk = List.copyOf(
                pieceOfRecord.subList(1, chunks.size() + 1));

        Bytes blobHash = HASHES.sha256(blob);
        ContentId contentId = new ContentId(blobHash, blob.length, dev.nodera.storage.Compression.NONE);
        StateRoot regionRoot = StateRoot.of(blobHash);

        // Stamp every column. A book supplies real provenance for anything this node wrote; the
        // fallback is derived from the snapshot, so a peer with no book still produces an index
        // byte-identical to every other peer's for the same snapshot — which is what lets two
        // nodes establish "we hold the same region" by comparing 32 bytes.
        Hlc fallback = ChunkStampBook.derivedFrom(snapshot.tick());
        List<ChunkStamp> stamps = new java.util.ArrayList<>(chunks.size());
        for (ChunkColumnState column : chunks) {
            Hlc stamp = book == null ? fallback
                    : book.stampFor(column.chunkX(), column.chunkZ(), fallback);
            stamps.add(ChunkStamp.of(column, stamp));
        }
        RegionChunkIndex chunkIndex = RegionChunkIndex.of(snapshot.region(), stamps);

        // The index canonicalises its stamps by (chunkX, chunkZ) and RegionSnapshot canonicalises its
        // chunks the same way, so a stamp's position in the index IS its chunk ordinal — which is
        // what makes pieceOfChunk, built here in chunk order, line up with the index the fetcher
        // diffs against.
        PieceManifest manifest = PieceManifest.of(
                snapshot.region(),
                snapshot.version(),
                snapshot.tick(),
                regionRoot,
                contentId,
                blob.length,
                pieces,
                chunkIndex,
                pieceOfChunk);

        return new Layout(snapshot, Bytes.unsafeWrap(blob), manifest, pieceOfChunk);
    }
}
