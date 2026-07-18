package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.region.RegionId;
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
 * <p>Wire form: {@code [u16 PIECE_MANIFEST][u16 ENCODING_VERSION][RegionId][SnapshotVersion]
 * [u64 tick][StateRoot regionRoot][bytes blobHash][u64 blobSize][u8 compressionOrdinal]
 * [u64 totalLength][u8 encrypted][u8 keyMaterialPresent]([WorldKeyMaterial])[list Piece]
 * [bytes manifestRoot]}.
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
        Bytes manifestRoot
) implements Encodable {

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
        return new PieceManifest(region, version, tick, regionRoot, blob, totalLength,
                false, null, pieces, computeRoot(pieces));
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
        return new PieceManifest(region, version, tick, regionRoot, blob, totalLength,
                true, keyMaterial, pieces, computeRoot(pieces));
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
        w.writeU16(TypeTags.PIECE_MANIFEST).writeU16(ENCODING_VERSION);
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
        r.readVersion(ENCODING_VERSION);
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
        return new PieceManifest(region, version, tick, regionRoot, blob, totalLength,
                encrypted, keyMaterial, pieces, manifestRoot);
    }

    @Override
    public String toString() {
        return "PieceManifest[" + region + " v" + version.value() + " pieces=" + pieces.size()
                + " " + totalLength + "B root=" + manifestRoot.toShortHex(6)
                + (encrypted ? " encrypted" : "") + "]";
    }
}
