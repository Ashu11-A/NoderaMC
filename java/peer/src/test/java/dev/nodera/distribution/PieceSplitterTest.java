package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Splitting is where "addressable pieces" is either true or a lie: a cut in the wrong place
 * produces pieces that verify individually yet cannot be used individually. These tests pin the
 * record-boundary rule, the over-target record rule, and the invariant that the piece plane
 * addresses exactly the frozen {@code RegionSnapshot} bytes.
 *
 * <p>Thread-context: single test thread.
 */
final class PieceSplitterTest {

    private static byte[] blob(int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (i * 13 + 1);
        }
        return out;
    }

    @Test
    void cutsOnlyAtRecordBoundariesAndPacksTowardTheTarget() {
        byte[] data = blob(400);
        // Records of 100 bytes each at 0, 100, 200, 300.
        int[] recordStarts = {0, 100, 200, 300};

        List<Piece> pieces = PieceSplitter.split(data, recordStarts, 150);

        // 150-byte target over 100-byte records: two records per piece.
        assertThat(pieces).hasSize(2);
        assertThat(pieces.get(0).offset()).isZero();
        assertThat(pieces.get(0).length()).isEqualTo(200);
        assertThat(pieces.get(1).offset()).isEqualTo(200);
        assertThat(pieces.get(1).length()).isEqualTo(200);
        // Every cut lands on a declared record start.
        for (Piece p : pieces) {
            assertThat(recordStarts).contains((int) p.offset());
        }
    }

    @Test
    void anOverTargetRecordBecomesItsOwnOverTargetPieceRatherThanBeingCutMidRecord() {
        byte[] data = blob(500);
        // One 400-byte record followed by a 100-byte record.
        int[] recordStarts = {0, 400};

        List<Piece> pieces = PieceSplitter.split(data, recordStarts, 50);

        assertThat(pieces).hasSize(2);
        // The target is a packing goal, not a cap: the big record survives whole.
        assertThat(pieces.get(0).length()).isEqualTo(400);
        assertThat(pieces.get(1).length()).isEqualTo(100);
    }

    @Test
    void piecesAreContiguousCoverTheWholeBlobAndHashTheirOwnBytes() {
        byte[] data = blob(1000);
        List<Piece> pieces = PieceSplitter.splitFixed(data, 128);

        long covered = 0;
        for (int i = 0; i < pieces.size(); i++) {
            Piece p = pieces.get(i);
            assertThat(p.index()).isEqualTo(i);
            assertThat(p.offset()).isEqualTo(covered);
            covered = p.endOffset();
            Bytes slice = new Bytes(data, (int) p.offset(), (int) p.length());
            assertThat(DistFixtures.hashes().sha256(slice)).isEqualTo(p.pieceHash());
        }
        assertThat(covered).isEqualTo(data.length);
    }

    @Test
    void rejectsMalformedInputs() {
        byte[] data = blob(100);
        assertThatThrownBy(() -> PieceSplitter.split(new byte[0], new int[]{0}, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty blob");
        assertThatThrownBy(() -> PieceSplitter.split(data, new int[]{5}, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start at 0");
        assertThatThrownBy(() -> PieceSplitter.split(data, new int[]{0, 20, 20}, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly ascending");
        assertThatThrownBy(() -> PieceSplitter.split(data, new int[]{0, 100}, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inside the blob");
        assertThatThrownBy(() -> PieceSplitter.splitFixed(data, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetBytes must be positive");
    }

    @Test
    void mapsEveryRecordToTheContainingPiece() {
        byte[] data = blob(400);
        int[] recordStarts = {0, 100, 200, 300};
        List<Piece> pieces = PieceSplitter.split(data, recordStarts, 150);

        assertThat(PieceSplitter.pieceOfRecord(recordStarts, pieces))
                .containsExactly(0, 0, 1, 1);
    }

    @Test
    void snapshotBlobIsByteForByteTheFrozenRegionSnapshotEncoding() {
        RegionSnapshot snapshot = DistFixtures.variedSnapshot(
                DistFixtures.region(1, 1), new SnapshotVersion(3L), 99L);

        RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(snapshot, 4096);

        CanonicalWriter w = new CanonicalWriter();
        snapshot.encode(w);
        assertThat(layout.blob().toArray()).isEqualTo(w.toByteArray());

        // ...which is exactly why the manifest's regionRoot equals the committee's StateRoot with
        // no extra agreement: both are SHA-256 over the same canonical bytes.
        assertThat(layout.manifest().regionRoot())
                .isEqualTo(StateRoot.of(DistFixtures.hashes().hash(snapshot)));
        assertThat(layout.manifest().blob().hash())
                .isEqualTo(layout.manifest().regionRoot().hash());
    }

    @Test
    void snapshotSplitsIntoManyPiecesAndIndexesEveryChunkColumn() {
        RegionSnapshot snapshot = DistFixtures.variedSnapshot(
                DistFixtures.region(0, 0), SnapshotVersion.INITIAL, 0L);

        RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(snapshot, 512);

        // Task 19 acceptance #2 works with a region split into >= 8 addressable pieces.
        assertThat(layout.manifest().pieceCount()).isGreaterThanOrEqualTo(8);
        assertThat(layout.pieceOfChunk()).hasSize(snapshot.chunks().size());
        // The chunk -> piece index is monotonic: chunks are encoded in canonical order, so a later
        // chunk can never land in an earlier piece.
        for (int i = 1; i < layout.pieceOfChunk().size(); i++) {
            assertThat(layout.pieceForChunk(i)).isGreaterThanOrEqualTo(layout.pieceForChunk(i - 1));
        }
        assertThat(layout.pieceForChunk(layout.pieceOfChunk().size() - 1))
                .isEqualTo(layout.manifest().pieceCount() - 1);
    }

    @Test
    void splittingIsDeterministicAcrossRuns() {
        RegionSnapshot snapshot = DistFixtures.variedSnapshot(
                DistFixtures.region(-2, 5), new SnapshotVersion(11L), 1234L);

        RegionSnapshotSplitter.Layout a = RegionSnapshotSplitter.split(snapshot, 700);
        RegionSnapshotSplitter.Layout b = RegionSnapshotSplitter.split(snapshot, 700);

        assertThat(a.manifest()).isEqualTo(b.manifest());
        assertThat(a.manifest().manifestRoot()).isEqualTo(b.manifest().manifestRoot());
        assertThat(a.pieceOfChunk()).isEqualTo(b.pieceOfChunk());
    }
}
