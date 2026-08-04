package dev.nodera.distribution;

import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionChunkIndex;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim this whole lane rests on: <b>placing one block costs one block's worth of transfer</b>.
 *
 * <p>It did not. A region's manifest was a flat hash over a piece list, so any edit produced an
 * entirely new set of piece hashes and a peer holding the previous version reused none of them.
 * Combined with a host that repacked the world every two minutes, that is the whole explanation for
 * peers moving megabytes per second through an idle world.
 */
final class ChunkDeltaTransferTest {

    private static final RegionId REGION =
            new RegionId(DimensionKey.of("minecraft", "overworld"), 0, 0);

    /** A column big enough that a region of them spans many 24 KiB pieces. */
    private static ChunkColumnState column(int x, int z, int block) {
        int[] palette = new int[1024];
        java.util.Arrays.fill(palette, block);
        return new ChunkColumnState(x, z, palette, -64, 1024, List.of());
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

    @Test
    void aRegionNobodyTouchedTransfersNothing() {
        RegionSnapshotSplitter.Layout before = RegionSnapshotSplitter.split(snapshot(1, -1, 0));
        RegionSnapshotSplitter.Layout after = RegionSnapshotSplitter.split(snapshot(2, -1, 0));

        // The version moved; the content did not. Under the old design this was a full re-fetch,
        // because a new version meant a new manifestRoot and nothing looked below it.
        assertThat(after.piecesChangedSince(before.chunkIndex())).isEmpty();
    }

    @Test
    void oneChangedColumnMovesOnlyThePieceCarryingIt() {
        RegionSnapshotSplitter.Layout before = RegionSnapshotSplitter.split(snapshot(1, -1, 0));
        RegionSnapshotSplitter.Layout after = RegionSnapshotSplitter.split(snapshot(2, 3, 999));

        SortedSet<Integer> needed = after.piecesChangedSince(before.chunkIndex());

        assertThat(needed).hasSize(1);
        assertThat(needed.first()).isEqualTo(after.pieceForChunk(indexOfColumn(after, 3, 0)));
        assertThat(needed.size())
                .as("a one-column edit must not cost a whole region")
                .isLessThan(after.manifest().pieceCount());
    }

    @Test
    void aPeerWithNothingFetchesEverything() {
        RegionSnapshotSplitter.Layout after = RegionSnapshotSplitter.split(snapshot(1, -1, 0));

        assertThat(after.piecesChangedSince(null))
                .hasSize(after.manifest().pieceCount());
    }

    @Test
    void theManifestCarriesTheIndexAcrossTheWire() {
        RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(snapshot(4, -1, 0));

        dev.nodera.core.crypto.CanonicalWriter w = new dev.nodera.core.crypto.CanonicalWriter();
        layout.manifest().encode(w);
        PieceManifest decoded = PieceManifest.decode(
                new dev.nodera.core.crypto.CanonicalReader(w.toBytes()));

        assertThat(decoded.hasChunkIndex()).isTrue();
        assertThat(decoded.chunkIndex().root()).isEqualTo(layout.chunkIndex().root());
        assertThat(decoded.frameVersion()).isEqualTo(PieceManifest.V2_CHUNK_INDEX);
    }

    @Test
    void aManifestWithoutAnIndexStillEncodesAsV1() {
        // The compatibility promise: every root ever computed keeps its exact bytes.
        RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(snapshot(4, -1, 0));
        PieceManifest v1 = PieceManifest.of(REGION, new SnapshotVersion(4), 4,
                layout.manifest().regionRoot(), layout.manifest().blob(),
                layout.manifest().totalLength(), layout.manifest().pieces());

        dev.nodera.core.crypto.CanonicalWriter w = new dev.nodera.core.crypto.CanonicalWriter();
        v1.encode(w);
        PieceManifest decoded = PieceManifest.decode(
                new dev.nodera.core.crypto.CanonicalReader(w.toBytes()));

        assertThat(v1.frameVersion()).isEqualTo(PieceManifest.V1);
        assertThat(decoded.hasChunkIndex()).isFalse();
        assertThat(decoded.manifestRoot()).isEqualTo(v1.manifestRoot());
    }

    @Test
    void aPeerHoldingAnIndexlessManifestFallsBackToFetchingEverything() {
        RegionSnapshotSplitter.Layout after = RegionSnapshotSplitter.split(snapshot(2, 3, 999));
        RegionChunkIndex nothingKnown = null;

        assertThat(after.piecesChangedSince(nothingKnown))
                .as("no index means no basis for a delta, and guessing would lose data")
                .hasSize(after.manifest().pieceCount());
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
