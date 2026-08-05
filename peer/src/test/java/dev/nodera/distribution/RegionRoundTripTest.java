package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transfer loop, closed: a region that is split, addressed and reassembled has to come back as
 * the region that went in.
 *
 * <p>Every step of this existed before and none of them were ever joined up. A region could be
 * extracted, split, hashed, announced, requested and verified — and then nothing anywhere could turn
 * the bytes back into state, let alone put that state into a world. These tests pin the two
 * properties the fetch path depends on: that reassembly is exact, and that a peer holding the
 * previous version pays for what changed rather than for the region.
 */
final class RegionRoundTripTest {

    private static final RegionId REGION =
            new RegionId(DimensionKey.of("minecraft", "overworld"), 0, 0);
    private static final HashService HASHES = new HashService();

    private static ChunkColumnState column(int x, int z, int block) {
        int[] palette = new int[64];
        java.util.Arrays.fill(palette, block);
        return new ChunkColumnState(x, z, palette, -64, 64, List.of());
    }

    private static RegionSnapshot snapshot(long version, int changedX, int changedBlock) {
        List<ChunkColumnState> columns = new ArrayList<>();
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                int block = x == changedX && z == 0 ? changedBlock : (x * 8 + z + 1);
                columns.add(column(x, z, block));
            }
        }
        return new RegionSnapshot(REGION, new SnapshotVersion(version), version, columns);
    }

    /** Reassemble a layout's blob from its pieces, the way a downloader does. */
    private static byte[] reassemble(RegionSnapshotSplitter.Layout layout) {
        byte[] blob = new byte[(int) layout.manifest().totalLength()];
        byte[] source = layout.blob().toArray();
        for (Piece piece : layout.manifest().pieces()) {
            System.arraycopy(source, (int) piece.offset(), blob, (int) piece.offset(),
                    (int) piece.length());
        }
        return blob;
    }

    @Test
    void aRegionSurvivesBeingSplitAndPutBackTogether() {
        RegionSnapshot original = snapshot(3, 2, 77);
        RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(original);

        byte[] reassembled = reassemble(layout);

        // The certified root is over these bytes, so this is also the check the fetch path makes
        // before it will decode anything: pieces are verified individually on arrival, and this is
        // what proves they were also the RIGHT pieces in the right order.
        assertThat(HASHES.sha256(reassembled))
                .isEqualTo(layout.manifest().regionRoot().hash());

        RegionSnapshot decoded = RegionSnapshot.decode(
                new CanonicalReader(Bytes.unsafeWrap(reassembled)));
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void everyPieceCoversExactlyOneChunkColumn() {
        RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(snapshot(1, -1, 0));

        // 64 columns, plus the frame header and the entity record — the two pieces no column claims.
        assertThat(layout.manifest().pieceCount()).isEqualTo(66);
        assertThat(layout.manifest().pieceOfChunk())
                .as("a column's ordinal maps to its own piece, in order")
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, 64).boxed().toList());
    }

    @Test
    void aPeerHoldingThePreviousVersionFetchesOnlyWhatChanged() {
        RegionSnapshotSplitter.Layout held = RegionSnapshotSplitter.split(snapshot(1, -1, 0));
        RegionSnapshotSplitter.Layout fresh = RegionSnapshotSplitter.split(snapshot(2, 5, 4242));

        SortedSet<Integer> wanted = fresh.manifest().piecesNotHeldIn(held.manifest());

        // The edited column's bytes, and the header — which commits the version and tick, so it
        // genuinely differs. Nothing else moves, because a column nobody touched is a byte-identical
        // piece under the new root and is found by hash in local storage.
        assertThat(wanted).hasSizeLessThanOrEqualTo(2);
        assertThat(wanted).contains(fresh.pieceForChunk(indexOfColumn(fresh, 5, 0)));
    }

    @Test
    void aReSeedOfUntouchedTerrainTransfersNothingAtAll() {
        RegionSnapshotSplitter.Layout held = RegionSnapshotSplitter.split(snapshot(1, -1, 0));
        // Same content, same version — the honest no-op. The version-counter design re-fetched the
        // world here, which is the whole of the observed idle traffic.
        RegionSnapshotSplitter.Layout again = RegionSnapshotSplitter.split(snapshot(1, -1, 0));

        assertThat(again.manifest().piecesNotHeldIn(held.manifest())).isEmpty();
        assertThat(again.manifest().manifestRoot())
                .as("identical content is the identical manifest, so nothing is even offered")
                .isEqualTo(held.manifest().manifestRoot());
    }

    @Test
    void twoPeersThatHoldTheSameTerrainAgreeWithoutTransferringIt() {
        // The chunk-compatibility claim, asserted directly. One of these peers wrote the terrain and
        // the other received it, at different versions and different ticks; the index root depends
        // on content alone, so they establish "identical" in thirty-two bytes.
        RegionSnapshotSplitter.Layout mine = RegionSnapshotSplitter.split(snapshot(9, 3, 55));
        RegionSnapshotSplitter.Layout theirs = RegionSnapshotSplitter.split(snapshot(2, 3, 55));

        assertThat(mine.chunkIndex().root()).isEqualTo(theirs.chunkIndex().root());
        assertThat(mine.piecesChangedSince(theirs.chunkIndex())).isEmpty();
    }

    private static int indexOfColumn(RegionSnapshotSplitter.Layout layout, int x, int z) {
        List<ChunkColumnState> columns = layout.snapshot().chunks();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).chunkX() == x && columns.get(i).chunkZ() == z) {
                return i;
            }
        }
        throw new IllegalStateException("no such column");
    }
}
