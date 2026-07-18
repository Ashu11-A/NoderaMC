package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.content.ContentChunk;

import java.util.BitSet;
import java.util.Objects;

/**
 * Collects verified pieces into the original blob (Task 19) — the enforcement point for rule 10's
 * "hash-validate before use".
 *
 * <h2>What is checked, and when</h2>
 *
 * <ol>
 *   <li><b>Per piece, on arrival.</b> {@link #accept(ContentChunk)} rejects any payload that does
 *       not match the <i>manifest's</i> pinned length and hash for that index. The chunk's own
 *       claim about itself is never consulted — {@link ContentChunk} deliberately carries no hash
 *       field, because a hash shipped alongside attacker-supplied bytes proves nothing.</li>
 *   <li><b>On completion.</b> {@link #assemble()} re-checks that every index is present, that the
 *       assembled length equals {@code manifest.totalLength()}, and that the blob hashes to the
 *       manifest's {@code ContentId}. The last check is what ties the piece plane back to the
 *       committee's committed state: the same hash is the region's {@link StateRoot}.</li>
 * </ol>
 *
 * <p>A rejected piece leaves the reassembler completely unchanged, so a peer that feeds corrupt
 * bytes can waste bandwidth but can never poison state or advance progress. The caller
 * (typically {@link PieceDownloader}) retries that index from a different holder.
 *
 * <p><b>Resumability.</b> A reassembler can be seeded with pieces already on disk via
 * {@link #restore(int, Bytes)}, so an interrupted download resumes at piece granularity rather than
 * restarting the region.
 *
 * <p>Thread-context: NOT thread-safe. One reassembler per in-flight download; the owning
 * {@link PieceDownloader} serialises access.
 */
public final class PieceReassembler {

    private final PieceManifest manifest;
    private final HashService hashes;
    private final byte[] buffer;
    private final BitSet verified;

    /**
     * @param manifest the manifest whose pieces are being collected.
     * @throws IllegalArgumentException if {@code manifest} is null or its total length exceeds
     *                                  {@link Integer#MAX_VALUE} (a single blob that large is a
     *                                  layering error — regions are split before this point).
     * @Thread-context any thread (construction only).
     */
    public PieceReassembler(PieceManifest manifest) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        if (manifest.totalLength() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "blob too large to reassemble in memory: " + manifest.totalLength());
        }
        this.hashes = new HashService();
        this.buffer = new byte[(int) manifest.totalLength()];
        this.verified = new BitSet(manifest.pieceCount());
    }

    /** @return the manifest being reassembled. */
    public PieceManifest manifest() {
        return manifest;
    }

    /**
     * Offer a received chunk.
     *
     * @param chunk the received chunk.
     * @return {@code true} if the chunk verified and was stored; {@code false} if it was rejected
     *         (wrong manifest, unknown index, or failed hash/length check).
     * @throws IllegalArgumentException if {@code chunk} is null.
     * @Thread-context single-threaded per reassembler.
     */
    public boolean accept(ContentChunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        if (!chunk.manifestRoot().equals(manifest.manifestRoot())) {
            return false;
        }
        return restore(chunk.index(), chunk.payload());
    }

    /**
     * Offer bytes for one piece directly — used to seed a resumed download from local storage, and
     * by {@link #accept(ContentChunk)}. Verification is identical either way: locally cached bytes
     * are not trusted more than network bytes (a corrupted cache must not become a corrupted world).
     *
     * @param index   the piece index.
     * @param payload the piece bytes.
     * @return {@code true} if the bytes verified and were stored.
     * @throws IllegalArgumentException if {@code payload} is null.
     * @Thread-context single-threaded per reassembler.
     */
    public boolean restore(int index, Bytes payload) {
        Objects.requireNonNull(payload, "payload");
        if (index < 0 || index >= manifest.pieceCount()) {
            return false;
        }
        if (!manifest.verifyPiece(index, payload)) {
            return false;
        }
        Piece p = manifest.piece(index);
        payload.copyInto(buffer, (int) p.offset());
        verified.set(index);
        return true;
    }

    /**
     * @param index the piece index.
     * @return {@code true} if that piece has been received and verified.
     * @Thread-context single-threaded per reassembler.
     */
    public boolean hasPiece(int index) {
        return index >= 0 && index < manifest.pieceCount() && verified.get(index);
    }

    /** @return how many pieces are verified so far. */
    public int verifiedCount() {
        return verified.cardinality();
    }

    /** @return {@code true} once every piece is verified. */
    public boolean isComplete() {
        return verified.cardinality() == manifest.pieceCount();
    }

    /**
     * @return the piece indexes not yet verified, ascending.
     * @Thread-context single-threaded per reassembler.
     */
    public java.util.List<Integer> missing() {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < manifest.pieceCount(); i++) {
            if (!verified.get(i)) {
                out.add(i);
            }
        }
        return java.util.List.copyOf(out);
    }

    /**
     * Assemble and validate the complete blob.
     *
     * @return the reassembled bytes, proven to hash to the manifest's content id.
     * @throws IllegalStateException if pieces are still missing, or if the assembled blob does not
     *                               match the manifest's content hash (which cannot happen while
     *                               every piece verified individually, and is therefore treated as
     *                               a manifest-integrity failure rather than a transfer failure).
     * @Thread-context single-threaded per reassembler.
     */
    public Bytes assemble() {
        if (!isComplete()) {
            throw new IllegalStateException(
                    "cannot assemble: " + (manifest.pieceCount() - verified.cardinality())
                            + " of " + manifest.pieceCount() + " pieces still missing");
        }
        Bytes blob = Bytes.unsafeWrap(buffer.clone());
        if (blob.length() != manifest.totalLength()) {
            throw new IllegalStateException(
                    "assembled length " + blob.length() + " != manifest totalLength "
                            + manifest.totalLength());
        }
        Bytes actual = hashes.sha256(blob);
        if (!actual.equals(manifest.blob().hash())) {
            throw new IllegalStateException(
                    "assembled blob hashes to " + actual.toShortHex(6)
                            + " but the manifest's content id is "
                            + manifest.blob().hash().toShortHex(6));
        }
        return blob;
    }

    /**
     * The region state root implied by the assembled blob. Equal to {@code manifest.regionRoot()}
     * for a well-formed manifest — the check that the swarm delivered the state the committee
     * actually committed.
     *
     * @return the assembled blob's state root.
     * @throws IllegalStateException if the blob is incomplete or fails validation.
     * @Thread-context single-threaded per reassembler.
     */
    public StateRoot assembledRoot() {
        return StateRoot.of(hashes.sha256(assemble()));
    }
}
