package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionChunkIndex;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.storage.ContentId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The piece layer beneath a region's content blob (Task 19): a hash list that binds every
 * addressable {@link Piece} to the region's committed {@link StateRoot}.
 *
 * <p>Tasks 2 and 9 are untouched by this type. {@code RegionSnapshot}/{@code StateRoot} stay frozen
 * and still commit the whole region; {@code ContentId}/{@code ContentStore} still address the whole
 * blob. The manifest sits <i>under</i> them and says how that one blob decomposes into pieces the
 * swarm can move independently.
 *
 * <h2>The trust chain</h2>
 *
 * <pre>
 *   quorum certificate (Task 9)  →  manifestRoot  →  pieceHash[i]  →  the bytes of piece i
 * </pre>
 *
 * <p>{@code manifestRoot} is SHA-256 over the index-ordered piece list — {@code (index, length,
 * pieceHash)} per piece, so <b>position and layout are part of what the root commits</b>: pieces
 * cannot be silently reordered, resized, or re-offset. (Offsets are not hashed separately because
 * pieces are contiguous from 0, so the length sequence determines every offset.)
 *
 * <p><b>Freshness (rule 10) is never taken on the manifest's own word.</b> The manifest carries the
 * region's {@link SnapshotVersion} and tick, and a higher version supersedes a lower one — but a
 * manifest is authoritative only once its {@code manifestRoot} is referenced by a certified
 * checkpoint/commit. A seeder therefore cannot forge freshness by inventing version numbers; the
 * worst it can do is offer stale-but-certified content, which the receiver detects by version.
 *
 * <h2>Deliberately-absent fields</h2>
 *
 * <ul>
 *   <li>There is no separate {@code compression} field: compression is already part of {@code blob}
 *       ({@code ContentId = hash + size + compression}). A second copy could only ever disagree
 *       with the first.</li>
 *   <li>{@code totalLength} <i>is</i> kept even though it must equal {@code blob.size()}, because
 *       it is the quantity the reassembler checks against, and the constructor enforces the
 *       equality so the pair can never drift.</li>
 * </ul>
 *
 * <p>Task 19 reserved the {@code encrypted}/{@code keyMaterial} slots; Task 23 now fills them without
 * an encoding-version bump. When encrypted, piece hashes and {@code blob} cover <b>ciphertext</b>,
 * while {@code regionRoot} continues to commit the decrypted canonical region state. A seeder
 * verifies and serves content it cannot read.
 *
 * <h2>The chunk index (v2)</h2>
 *
 * <p>{@code manifestRoot} answers "are your bytes my bytes?" and nothing else, so the only follow-up
 * it supports is "send me all of them". That is what made a peer re-fetch an entire world because
 * one block moved. A v2 manifest additionally carries a {@link RegionChunkIndex}: a stamp per chunk
 * column under a merkle root, which turns the follow-up into "send me these eleven columns".
 *
 * <p>Appended rather than versioned across the board, in the {@code ChunkColumnState} idiom: a
 * manifest with no index <b>always</b> encodes as v1, so every root ever computed keeps its exact
 * bytes and an older peer reading a v1 frame is unaffected. A v2 frame reaching an older peer fails
 * its version check loudly, which is the correct outcome — it cannot honour what the frame says.
 *
 * <p>An index alone still leaves the fetcher one step short: it names the <i>columns</i> that differ,
 * and a request is for <i>pieces</i>. Deriving one from the other requires knowing how the seeder cut
 * the blob, which the fetcher cannot see. So v2 also carries {@code pieceOfChunk} — the seeder states
 * the mapping rather than the fetcher guessing at it, and a byte-target split and a per-column split
 * are both fetchable without the reader knowing which it is looking at.
 *
 * <p>Wire form v1: {@code [u16 PIECE_MANIFEST][u16 1][RegionId][SnapshotVersion]
 * [u64 tick][StateRoot regionRoot][bytes blobHash][u64 blobSize][u8 compressionOrdinal]
 * [u64 totalLength][u8 encrypted][u8 keyMaterialPresent]([WorldKeyMaterial])[list Piece]
 * [bytes manifestRoot]}. Wire form v2 appends {@code [RegionChunkIndex][list u32 pieceOfChunk]}.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param region       the region this content describes.
 * @param version      the region snapshot version (freshness ordering; see above).
 * @param tick         the tick the snapshot was taken at.
 * @param regionRoot   the region's committed {@link StateRoot} — the manifest is self-checking
 *                     against committed truth.
 * @param blob         the parent blob's content id (hash + size + compression).
 * @param totalLength  the reassembled byte length; equals {@code blob.size()}.
 * @param encrypted    whether piece payloads are ciphertext (Task 23).
 * @param keyMaterial  KDF parameters when {@code encrypted}; {@code null} otherwise.
 * @param pieces       the pieces, index-ordered, contiguous from offset 0.
 * @param manifestRoot SHA-256 over the index-ordered piece list; a derived field, re-verified on
 *                     construction and on decode.
 * @param chunkIndex   the region's per-chunk stamps under a merkle root, or {@code null} for a v1
 *                     manifest. What makes a transfer proportional to what changed.
 * @param pieceOfChunk {@code pieceOfChunk.get(i)} = the piece carrying the {@code i}-th chunk column
 *                     in the index's canonical order; {@code null} exactly when {@code chunkIndex}
 *                     is. Turns "these columns changed" into "request these pieces".
 */
public record PieceManifest(
        RegionId region,
        SnapshotVersion version,
        long tick,
        StateRoot regionRoot,
        ContentId blob,
        long totalLength,
        boolean encrypted,
        WorldKeyMaterial keyMaterial,
        List<Piece> pieces,
        Bytes manifestRoot,
        RegionChunkIndex chunkIndex,
        List<Integer> pieceOfChunk
) implements Encodable {

    /** The original frame: pieces and a flat root, and no way to ask what changed. */
    public static final int V1 = 1;

    /** Appends the region's chunk index. */
    public static final int V2_CHUNK_INDEX = 2;

    /**
     * Shared hasher. {@link HashService} confines its {@link java.security.MessageDigest} to a
     * {@link ThreadLocal}, so one static instance is safe for every thread and avoids allocating a
     * hasher per manifest on the decode path.
     */
    private static final HashService HASHES = new HashService();

    private static final Comparator<Piece> BY_INDEX = Comparator.comparingInt(Piece::index);

    /**
     * Compact constructor. Canonicalises the piece order and enforces every structural invariant
     * the reassembler and the swarm rely on.
     *
     * @throws IllegalArgumentException if a required argument is null, the piece list is empty,
     *                                  the pieces are not contiguous {@code 0..n-1} from offset 0,
     *                                  the lengths do not sum to {@code totalLength},
     *                                  {@code totalLength != blob.size()}, the
     *                                  {@code encrypted}/{@code keyMaterial} pair is inconsistent,
     *                                  or {@code manifestRoot} does not match the recomputed root.
     */
    public PieceManifest {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(regionRoot, "regionRoot");
        Objects.requireNonNull(blob, "blob");
        Objects.requireNonNull(pieces, "pieces");
        Objects.requireNonNull(manifestRoot, "manifestRoot");
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be non-negative: " + tick);
        }
        if (pieces.isEmpty()) {
            throw new IllegalArgumentException("a manifest must describe at least one piece");
        }
        // encrypted <=> keyMaterial present. An "encrypted" manifest with no KDF parameters is
        // undecryptable; key material on a plaintext manifest is a lie about the payload.
        if (encrypted && keyMaterial == null) {
            throw new IllegalArgumentException("encrypted manifest requires keyMaterial");
        }
        if (!encrypted && keyMaterial != null) {
            throw new IllegalArgumentException("plaintext manifest must not carry keyMaterial");
        }

        List<Piece> sorted = new ArrayList<>(pieces);
        sorted.sort(BY_INDEX);
        long expectedOffset = 0;
        for (int i = 0; i < sorted.size(); i++) {
            Piece p = sorted.get(i);
            if (p.index() != i) {
                throw new IllegalArgumentException(
                        "piece indexes must be contiguous 0..n-1; got " + p.index() + " at position " + i);
            }
            if (p.offset() != expectedOffset) {
                throw new IllegalArgumentException(
                        "piece " + i + " offset " + p.offset() + " leaves a gap/overlap; expected "
                                + expectedOffset);
            }
            expectedOffset = p.endOffset();
        }
        if (expectedOffset != totalLength) {
            throw new IllegalArgumentException(
                    "piece lengths sum to " + expectedOffset + " but totalLength is " + totalLength);
        }
        if (totalLength != blob.size()) {
            throw new IllegalArgumentException(
                    "totalLength " + totalLength + " must equal blob size " + blob.size());
        }
        pieces = List.copyOf(sorted);

        Bytes recomputed = computeRoot(pieces);
        if (!recomputed.equals(manifestRoot)) {
            throw new IllegalArgumentException(
                    "manifestRoot " + manifestRoot.toShortHex(6)
                            + " does not match the recomputed root " + recomputed.toShortHex(6));
        }
        // An index for a different region would let a peer diff this region's content against
        // somebody else's stamps and conclude nothing had changed.
        if (chunkIndex != null && !chunkIndex.region().equals(region)) {
            throw new IllegalArgumentException("chunk index describes region "
                    + chunkIndex.region() + ", not " + region);
        }
        if ((chunkIndex == null) != (pieceOfChunk == null)) {
            throw new IllegalArgumentException(
                    "a chunk index and its piece mapping travel together or not at all");
        }
        if (pieceOfChunk != null) {
            if (pieceOfChunk.size() != chunkIndex.stamps().size()) {
                throw new IllegalArgumentException("piece mapping covers " + pieceOfChunk.size()
                        + " columns but the index describes " + chunkIndex.stamps().size());
            }
            // A mapping pointing outside the piece list would make a fetch ask for a piece that does
            // not exist, which the downloader would treat as a peer failing to answer rather than as
            // the malformed manifest it is.
            for (int piece : pieceOfChunk) {
                if (piece < 0 || piece >= sorted.size()) {
                    throw new IllegalArgumentException("piece mapping names piece " + piece
                            + ", outside 0.." + (sorted.size() - 1));
                }
            }
            pieceOfChunk = List.copyOf(pieceOfChunk);
        }
    }

    /** @return whether this manifest can say which columns changed. */
    public boolean hasChunkIndex() {
        return chunkIndex != null;
    }

    /**
     * The pieces that must arrive for a peer holding {@code held} to reach this manifest's state.
     *
     * <p>The whole purpose of the index, in one call: diff the stamps, map each changed column to the
     * piece carrying it, and request that set instead of the region. A manifest with no index cannot
     * answer and says so by asking for everything — the honest degradation, and what a v1 seeder
     * forces.
     *
     * @param held what the asking peer already has, or {@code null} for nothing.
     * @return the piece indices to fetch, ascending and duplicate-free.
     * @Thread-context any thread.
     */
    public java.util.SortedSet<Integer> piecesChangedSince(RegionChunkIndex held) {
        java.util.SortedSet<Integer> needed = new java.util.TreeSet<>();
        if (chunkIndex == null || held == null) {
            // No index to diff against, or nothing held at all. Either way the honest answer is the
            // whole thing; guessing at a subset here loses data silently.
            for (int i = 0; i < pieces.size(); i++) {
                needed.add(i);
            }
            return needed;
        }
        java.util.Set<Bytes> changed = new java.util.HashSet<>();
        for (dev.nodera.core.state.ChunkStamp stamp : chunkIndex.changedSince(held)) {
            changed.add(stamp.contentHash());
        }
        List<dev.nodera.core.state.ChunkStamp> ordered = chunkIndex.stamps();
        for (int ordinal = 0; ordinal < ordered.size(); ordinal++) {
            if (changed.contains(ordered.get(ordinal).contentHash())) {
                needed.add(pieceOfChunk.get(ordinal));
            }
        }
        if (!needed.isEmpty()) {
            // Not every piece belongs to a column. The frame header commits the region, version and
            // tick, and the entity list is its own record — the index describes neither, so neither
            // can be proven unchanged. They are a few hundred bytes against a region's megabytes, so
            // they ride along whenever anything else does.
            //
            // Deliberately NOT when nothing changed: a re-seed of untouched terrain must cost zero
            // bytes, and that property is worth more than keeping an entity list fresh in a region
            // where no block moved.
            needed.addAll(structuralPieces());
        }
        return needed;
    }

    /**
     * The pieces {@code held} does not already have the bytes of.
     *
     * <p>Exact, and index-free: pieces are content-addressed, so a piece whose hash the asking peer
     * already holds needs no transfer whatever the two manifests say about versions, layouts or
     * ordering. This is what a fetch should use when it holds the previous manifest — it subsumes the
     * column diff, covers the header and entity records the index cannot describe, and gives
     * relocation for free when a column's growth shifts every later piece along the blob.
     *
     * @param held the manifest for what the asking peer already has, or {@code null} for nothing.
     * @return the piece indices to fetch, ascending.
     * @Thread-context any thread.
     */
    public java.util.SortedSet<Integer> piecesNotHeldIn(PieceManifest held) {
        java.util.SortedSet<Integer> needed = new java.util.TreeSet<>();
        java.util.Set<Bytes> have = new java.util.HashSet<>();
        if (held != null) {
            for (Piece p : held.pieces) {
                have.add(p.pieceHash());
            }
        }
        for (int i = 0; i < pieces.size(); i++) {
            if (!have.contains(pieces.get(i).pieceHash())) {
                needed.add(i);
            }
        }
        return needed;
    }

    /** @return the piece indices no chunk column claims — the frame header and the entity record. */
    private java.util.SortedSet<Integer> structuralPieces() {
        java.util.Set<Integer> claimed = new java.util.HashSet<>(pieceOfChunk);
        java.util.SortedSet<Integer> structural = new java.util.TreeSet<>();
        for (int i = 0; i < pieces.size(); i++) {
            if (!claimed.contains(i)) {
                structural.add(i);
            }
        }
        return structural;
    }

    /**
     * The frame version this manifest encodes as.
     *
     * @return {@link #V2_CHUNK_INDEX} when it carries an index, {@link #V1} otherwise — so a
     *         manifest without one is byte-identical to what this type has always produced.
     */
    public int frameVersion() {
        return chunkIndex == null ? V1 : V2_CHUNK_INDEX;
    }

    /**
     * Build a manifest over an already-computed piece list, deriving {@code manifestRoot}.
     *
     * @param region      the region the content describes.
     * @param version     the region snapshot version.
     * @param tick        the tick the snapshot was taken at.
     * @param regionRoot  the region's committed state root.
     * @param blob        the parent blob's content id.
     * @param totalLength the reassembled byte length.
     * @param pieces      the pieces, contiguous from offset 0.
     * @return the manifest, with {@code encrypted = false}.
     * @Thread-context any thread.
     */
    public static PieceManifest of(
            RegionId region,
            SnapshotVersion version,
            long tick,
            StateRoot regionRoot,
            ContentId blob,
            long totalLength,
            List<Piece> pieces) {
        return of(region, version, tick, regionRoot, blob, totalLength, pieces, null, null);
    }

    /**
     * As {@link #of(RegionId, SnapshotVersion, long, StateRoot, ContentId, long, List)}, carrying
     * the region's chunk index so a peer holding an earlier version can be told what changed.
     *
     * @param region      the region the content describes.
     * @param version     the region snapshot version.
     * @param tick        the tick the snapshot was taken at.
     * @param regionRoot  the region's committed state root.
     * @param blob        the parent blob's content id.
     * @param totalLength the reassembled byte length.
     * @param pieces       the pieces, contiguous from offset 0.
     * @param chunkIndex   the region's chunk index, or {@code null} for a v1 manifest.
     * @param pieceOfChunk which piece carries each indexed column; {@code null} iff
     *                     {@code chunkIndex} is.
     * @return the manifest, with {@code encrypted = false}.
     * @Thread-context any thread.
     */
    public static PieceManifest of(
            RegionId region,
            SnapshotVersion version,
            long tick,
            StateRoot regionRoot,
            ContentId blob,
            long totalLength,
            List<Piece> pieces,
            RegionChunkIndex chunkIndex,
            List<Integer> pieceOfChunk) {
        return new PieceManifest(region, version, tick, regionRoot, blob, totalLength,
                false, null, pieces, computeRoot(pieces), chunkIndex, pieceOfChunk);
    }

    /**
     * Build an <b>encrypted</b> manifest (Task 23): pieces carry ciphertext hashes, {@code blob} is
     * the ciphertext content id, and {@code keyMaterial} carries the KDF params. {@code regionRoot}
     * stays the plaintext StateRoot (canonical truth); {@code manifestRoot} is derived from the
     * ciphertext-piece list.
     *
     * @param region      the region.
     * @param version     the snapshot version.
     * @param tick        the tick.
     * @param regionRoot  the plaintext region state root.
     * @param blob        the ciphertext blob's content id.
     * @param totalLength the ciphertext total length.
     * @param keyMaterial the KDF params (never null for an encrypted manifest).
     * @param pieces      the ciphertext pieces (hashes over ciphertext), contiguous from offset 0.
     * @return the encrypted manifest.
     * @throws IllegalArgumentException if {@code keyMaterial} is null.
     * @Thread-context any thread.
     */
    public static PieceManifest encrypted(
            RegionId region,
            SnapshotVersion version,
            long tick,
            StateRoot regionRoot,
            ContentId blob,
            long totalLength,
            WorldKeyMaterial keyMaterial,
            List<Piece> pieces) {
        if (keyMaterial == null) {
            throw new IllegalArgumentException("keyMaterial must not be null for an encrypted manifest");
        }
        // No chunk index on an encrypted manifest: the stamps commit plaintext column hashes, and
        // publishing those beside ciphertext would let anyone holding a candidate world confirm it
        // column by column without ever holding the key.
        return new PieceManifest(region, version, tick, regionRoot, blob, totalLength,
                true, keyMaterial, pieces, computeRoot(pieces), null, null);
    }

    /**
     * The canonical manifest root: SHA-256 over {@code (count, then (index, length, pieceHash) per
     * piece in index order)}. Committing index and length — not only the hash — is what makes
     * reordering and re-layout detectable.
     *
     * @param pieces the pieces, in any order (the root is computed over index order).
     * @return the 32-byte root.
     * @Thread-context any thread.
     */
    public static Bytes computeRoot(List<Piece> pieces) {
        Objects.requireNonNull(pieces, "pieces");
        List<Piece> ordered = new ArrayList<>(pieces);
        ordered.sort(BY_INDEX);
        CanonicalWriter w = new CanonicalWriter(8 + ordered.size() * 48);
        w.writeU32(ordered.size());
        for (Piece p : ordered) {
            w.writeU32(Integer.toUnsignedLong(p.index()));
            w.writeU64(p.length());
            w.writeBytes(p.pieceHash());
        }
        return HASHES.sha256(w.toByteArray());
    }

    /** @return the number of pieces this manifest describes. */
    public int pieceCount() {
        return pieces.size();
    }

    /**
     * @param index the piece index.
     * @return the piece at {@code index}.
     * @throws IndexOutOfBoundsException if {@code index} is outside {@code 0..pieceCount()-1}.
     * @Thread-context any thread.
     */
    public Piece piece(int index) {
        return pieces.get(index);
    }

    /**
     * Whether {@code candidate} is the correct payload for piece {@code index} — the single
     * hash-validate-before-accept check the whole data plane rests on (rule 10).
     *
     * @param index     the piece index.
     * @param candidate the received bytes.
     * @return {@code true} if the bytes match the pinned length AND hash for that index.
     * @Thread-context any thread.
     */
    public boolean verifyPiece(int index, Bytes candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (index < 0 || index >= pieces.size()) {
            return false;
        }
        Piece p = pieces.get(index);
        // Check length first: it is O(1) and rejects a truncated/padded payload before spending a
        // SHA-256 on attacker-supplied bytes.
        if (candidate.length() != p.length()) {
            return false;
        }
        return HASHES.sha256(candidate).equals(p.pieceHash());
    }

    /**
     * Whether {@code other} describes a strictly fresher state of the same region. Version alone is
     * NOT authority (see the class Javadoc): callers must additionally require that the fresher
     * manifest's root is certified before acting on it.
     *
     * @param other the candidate manifest.
     * @return {@code true} if {@code other} covers the same region at a higher snapshot version.
     * @Thread-context any thread.
     */
    public boolean isSupersededBy(PieceManifest other) {
        Objects.requireNonNull(other, "other");
        return region.equals(other.region) && other.version.compareTo(version) > 0;
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.PIECE_MANIFEST).writeU16(frameVersion());
        region.encode(w);
        version.encode(w);
        w.writeU64(tick);
        regionRoot.encode(w);
        // ContentId is a storage-api value type and not itself Encodable (storage-api must not own
        // a core type tag), so its three fields are written inline here.
        w.writeBytes(blob.hash());
        w.writeU64(blob.size());
        w.writeU8(blob.compression().ordinal());
        w.writeU64(totalLength);
        w.writeBoolean(encrypted);
        w.writeOptional(keyMaterial);
        if (keyMaterial != null) {
            keyMaterial.encode(w);
        }
        w.writeList(pieces, CanonicalWriter::writeEncodable);
        w.writeBytes(manifestRoot);
        if (chunkIndex != null) {
            chunkIndex.encode(w);
            w.writeList(pieceOfChunk, (ww, piece) -> ww.writeU32(Integer.toUnsignedLong(piece)));
        }
    }

    /**
     * Full-frame decode. The compact constructor re-verifies {@code manifestRoot} against the
     * decoded pieces, so a tampered manifest fails here rather than silently downstream.
     *
     * @param r the reader positioned at this manifest's tag.
     * @return the decoded manifest.
     * @throws IllegalStateException if the next tag is not {@code PIECE_MANIFEST} or the
     *                               compression ordinal is unknown.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static PieceManifest decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.PIECE_MANIFEST) {
            throw new IllegalStateException("expected PIECE_MANIFEST tag, got " + tag);
        }
        int frame = r.readU16();
        if (frame != V1 && frame != V2_CHUNK_INDEX) {
            throw new IllegalStateException("unsupported PIECE_MANIFEST version " + frame);
        }
        RegionId region = RegionId.decode(r);
        SnapshotVersion version = SnapshotVersion.decode(r);
        long tick = r.readU64();
        StateRoot regionRoot = StateRoot.decode(r);
        Bytes blobHash = r.readBytesValue();
        long blobSize = r.readU64();
        int compressionOrdinal = r.readU8();
        dev.nodera.storage.Compression[] compressions = dev.nodera.storage.Compression.values();
        if (compressionOrdinal < 0 || compressionOrdinal >= compressions.length) {
            throw new IllegalStateException("unknown compression ordinal " + compressionOrdinal);
        }
        ContentId blob = new ContentId(blobHash, blobSize, compressions[compressionOrdinal]);
        long totalLength = r.readU64();
        boolean encrypted = r.readBoolean();
        WorldKeyMaterial keyMaterial = r.readOptional() ? WorldKeyMaterial.decode(r) : null;
        List<Piece> pieces = r.readList(Piece::decode);
        Bytes manifestRoot = r.readBytesValue();
        RegionChunkIndex chunkIndex = frame >= V2_CHUNK_INDEX ? RegionChunkIndex.decode(r) : null;
        List<Integer> pieceOfChunk = frame >= V2_CHUNK_INDEX
                ? r.readList(rr -> (int) rr.readU32()) : null;
        return new PieceManifest(region, version, tick, regionRoot, blob, totalLength,
                encrypted, keyMaterial, pieces, manifestRoot, chunkIndex, pieceOfChunk);
    }

    @Override
    public String toString() {
        return "PieceManifest[" + region + " v" + version.value() + " pieces=" + pieces.size()
                + " " + totalLength + "B root=" + manifestRoot.toShortHex(6)
                + (encrypted ? " encrypted" : "") + "]";
    }
}
