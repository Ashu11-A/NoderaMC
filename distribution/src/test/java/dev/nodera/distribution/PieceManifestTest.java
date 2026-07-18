package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.storage.Compression;
import dev.nodera.storage.ContentId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The manifest is the whole data plane's trust anchor (Task 19 acceptance #1), so these tests pin
 * its canonical encoding, its derived root, and every structural invariant a tampered manifest
 * would have to break.
 *
 * <p>Thread-context: single test thread.
 */
final class PieceManifestTest {

    private static final RegionId REGION = DistFixtures.region(2, -3);

    private static PieceManifest manifestOf(byte[] blob, int targetBytes) {
        List<Piece> pieces = PieceSplitter.splitFixed(blob, targetBytes);
        Bytes blobHash = DistFixtures.hashes().sha256(blob);
        return PieceManifest.of(
                REGION,
                new SnapshotVersion(7L),
                42L,
                StateRoot.of(blobHash),
                new ContentId(blobHash, blob.length, Compression.NONE),
                blob.length,
                pieces);
    }

    private static byte[] blob(int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (i * 7 + 3);
        }
        return out;
    }

    @Test
    void roundTripsCanonicallyAndPinsItsTypeTag() {
        PieceManifest original = manifestOf(blob(500), 100);

        CanonicalWriter w = new CanonicalWriter();
        original.encode(w);
        byte[] frame = w.toByteArray();

        // The frame is self-describing: tag first, then version — the frozen Encodable contract.
        CanonicalReader peek = new CanonicalReader(frame);
        assertThat(peek.readU16()).isEqualTo(TypeTags.PIECE_MANIFEST);
        assertThat(peek.readU16()).isEqualTo(dev.nodera.core.crypto.Encodable.ENCODING_VERSION);

        PieceManifest decoded = PieceManifest.decode(new CanonicalReader(frame));
        assertThat(decoded).isEqualTo(original);

        // Byte-stability: re-encoding the decoded value reproduces the identical frame, which is
        // what lets a manifest root be quoted by a certificate.
        CanonicalWriter again = new CanonicalWriter();
        decoded.encode(again);
        assertThat(again.toByteArray()).isEqualTo(frame);
    }

    @Test
    void manifestRootIsDeterministicAndDependsOnPieceOrderAndLength() {
        byte[] data = blob(500);
        PieceManifest a = manifestOf(data, 100);
        PieceManifest b = manifestOf(data, 100);
        assertThat(a.manifestRoot()).isEqualTo(b.manifestRoot());

        // A different piece layout over the SAME bytes must produce a different root: the root
        // commits index and length, not just the hash multiset.
        PieceManifest coarser = manifestOf(data, 250);
        assertThat(coarser.manifestRoot()).isNotEqualTo(a.manifestRoot());
        assertThat(coarser.blob()).isEqualTo(a.blob());
    }

    @Test
    void rootCommitsPiecePositionSoPiecesCannotBeReordered() {
        byte[] data = blob(300);
        List<Piece> pieces = PieceSplitter.splitFixed(data, 100);
        assertThat(pieces).hasSize(3);

        // Swap two pieces' hashes while keeping the index/offset/length layout — the exact attack
        // "a hash list without positions" would permit.
        List<Piece> swapped = new ArrayList<>(pieces);
        swapped.set(0, new Piece(0, pieces.get(0).offset(), pieces.get(0).length(),
                pieces.get(1).pieceHash()));
        swapped.set(1, new Piece(1, pieces.get(1).offset(), pieces.get(1).length(),
                pieces.get(0).pieceHash()));

        assertThat(PieceManifest.computeRoot(swapped))
                .isNotEqualTo(PieceManifest.computeRoot(pieces));
    }

    @Test
    void rejectsAManifestWhoseStoredRootDoesNotMatchItsPieces() {
        PieceManifest good = manifestOf(blob(300), 100);
        Bytes tampered = DistFixtures.corrupt(good.manifestRoot());

        assertThatThrownBy(() -> new PieceManifest(
                good.region(), good.version(), good.tick(), good.regionRoot(), good.blob(),
                good.totalLength(), false, null, good.pieces(), tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match the recomputed root");
    }

    @Test
    void rejectsGappedOverlappingOrNonContiguousPieceLayouts() {
        byte[] data = blob(300);
        List<Piece> pieces = PieceSplitter.splitFixed(data, 100);

        List<Piece> gapped = new ArrayList<>(pieces);
        gapped.set(1, new Piece(1, 150, 100, pieces.get(1).pieceHash()));
        assertThatThrownBy(() -> PieceManifest.of(
                REGION, SnapshotVersion.INITIAL, 0L, StateRoot.zero(),
                new ContentId(Bytes.fromHex("00".repeat(32)), 300, Compression.NONE), 300, gapped))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gap/overlap");

        List<Piece> holed = new ArrayList<>(pieces);
        holed.remove(1);
        assertThatThrownBy(() -> PieceManifest.of(
                REGION, SnapshotVersion.INITIAL, 0L, StateRoot.zero(),
                new ContentId(Bytes.fromHex("00".repeat(32)), 300, Compression.NONE), 300, holed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contiguous");
    }

    @Test
    void rejectsALengthThatDisagreesWithTheContentId() {
        byte[] data = blob(300);
        List<Piece> pieces = PieceSplitter.splitFixed(data, 100);
        Bytes hash = DistFixtures.hashes().sha256(data);
        assertThatThrownBy(() -> PieceManifest.of(
                REGION, SnapshotVersion.INITIAL, 0L, StateRoot.of(hash),
                new ContentId(hash, 999, Compression.NONE), 300, pieces))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must equal blob size");
    }

    @Test
    void encryptionSlotsAreReservedAndMustAgreeWithEachOther() {
        PieceManifest plain = manifestOf(blob(300), 100);
        WorldKeyMaterial key = new WorldKeyMaterial("argon2id", Bytes.fromHex("0011223344556677"),
                65536L, 3, 1);

        // Reserved NOW (Task 23 fills the key path) so shipping encryption needs no version bump:
        // the slot round-trips today.
        PieceManifest encrypted = new PieceManifest(
                plain.region(), plain.version(), plain.tick(), plain.regionRoot(), plain.blob(),
                plain.totalLength(), true, key, plain.pieces(), plain.manifestRoot());
        CanonicalWriter w = new CanonicalWriter();
        encrypted.encode(w);
        assertThat(PieceManifest.decode(new CanonicalReader(w.toByteArray()))).isEqualTo(encrypted);

        assertThatThrownBy(() -> new PieceManifest(
                plain.region(), plain.version(), plain.tick(), plain.regionRoot(), plain.blob(),
                plain.totalLength(), true, null, plain.pieces(), plain.manifestRoot()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires keyMaterial");

        assertThatThrownBy(() -> new PieceManifest(
                plain.region(), plain.version(), plain.tick(), plain.regionRoot(), plain.blob(),
                plain.totalLength(), false, key, plain.pieces(), plain.manifestRoot()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not carry keyMaterial");
    }

    @Test
    void verifyPieceRejectsWrongLengthAndWrongBytesForTheIndex() {
        byte[] data = blob(300);
        PieceManifest manifest = manifestOf(data, 100);

        Bytes piece0 = new Bytes(data, 0, 100);
        Bytes piece1 = new Bytes(data, 100, 100);

        assertThat(manifest.verifyPiece(0, piece0)).isTrue();
        // Right bytes, wrong index — the manifest pins hash PER index, so this must fail.
        assertThat(manifest.verifyPiece(1, piece0)).isFalse();
        assertThat(manifest.verifyPiece(1, piece1)).isTrue();
        assertThat(manifest.verifyPiece(0, DistFixtures.corrupt(piece0))).isFalse();
        assertThat(manifest.verifyPiece(0, new Bytes(data, 0, 99))).isFalse();
        assertThat(manifest.verifyPiece(99, piece0)).isFalse();
    }

    @Test
    void freshnessOrdersByVersionButOnlyWithinTheSameRegion() {
        byte[] data = blob(300);
        List<Piece> pieces = PieceSplitter.splitFixed(data, 100);
        Bytes hash = DistFixtures.hashes().sha256(data);
        ContentId id = new ContentId(hash, data.length, Compression.NONE);

        PieceManifest v1 = PieceManifest.of(REGION, new SnapshotVersion(1L), 10L,
                StateRoot.of(hash), id, data.length, pieces);
        PieceManifest v2 = PieceManifest.of(REGION, new SnapshotVersion(2L), 20L,
                StateRoot.of(hash), id, data.length, pieces);
        PieceManifest otherRegion = PieceManifest.of(DistFixtures.region(9, 9),
                new SnapshotVersion(5L), 50L, StateRoot.of(hash), id, data.length, pieces);

        assertThat(v1.isSupersededBy(v2)).isTrue();
        assertThat(v2.isSupersededBy(v1)).isFalse();
        assertThat(v1.isSupersededBy(v1)).isFalse();
        // A higher version for a DIFFERENT region is not freshness — it is a different world slice.
        assertThat(v1.isSupersededBy(otherRegion)).isFalse();
    }
}
