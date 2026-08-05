package dev.nodera.distribution;

import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.ChunkStamp;
import dev.nodera.core.state.Hlc;
import dev.nodera.core.state.RegionChunkIndex;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two peers that both advanced a region while they could not see each other.
 *
 * <p>This used to be resolved by comparing counters that were incremented independently on two
 * machines: the higher number won, and every edit in the other copy was discarded with no record
 * that anything had been thrown away. Since ownership follows the players and players walk apart,
 * that is not an edge case — it is what happens whenever anyone is out of sight for long enough.
 */
final class RegionMergerTest {

    private static final RegionId REGION =
            new RegionId(DimensionKey.of("minecraft", "overworld"), 0, 0);
    private static final int SECTIONS = 2;

    private static ChunkColumnState blank(int x, int z) {
        return new ChunkColumnState(x, z, new int[SECTIONS], -64, SECTIONS, List.of());
    }

    /** A four-column region of air. */
    private static RegionSnapshot region(long version, List<ChunkColumnState> columns) {
        return new RegionSnapshot(REGION, new SnapshotVersion(version), version, columns);
    }

    private static List<ChunkColumnState> columns() {
        List<ChunkColumnState> columns = new ArrayList<>();
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                columns.add(blank(x, z));
            }
        }
        return columns;
    }

    private static List<ChunkColumnState> edited(int atX, int atZ, int x, int y, int z, int id) {
        List<ChunkColumnState> columns = columns();
        for (int i = 0; i < columns.size(); i++) {
            ChunkColumnState column = columns.get(i);
            if (column.chunkX() == atX && column.chunkZ() == atZ) {
                columns.set(i, column.withBlock(0, x, y, z, id));
            }
        }
        return columns;
    }

    private static RegionChunkIndex indexOf(RegionSnapshot snapshot, long millis) {
        List<ChunkStamp> stamps = new ArrayList<>();
        Hlc at = new Hlc(millis, 0, new UUID(0, 1));
        for (ChunkColumnState column : snapshot.chunks()) {
            stamps.add(ChunkStamp.of(column, at));
        }
        return RegionChunkIndex.of(snapshot.region(), stamps);
    }

    @Test
    void twoPeersWhoBuiltInDifferentChunksBothKeepTheirWork() {
        RegionSnapshot ancestor = region(1, columns());
        RegionSnapshot mine = region(2, edited(0, 0, 1, 1, 1, 7));
        RegionSnapshot theirs = region(2, edited(1, 1, 2, 2, 2, 9));

        RegionMerger.Outcome outcome = RegionMerger.reconcile(
                ancestor, mine, indexOf(mine, 5_000), theirs, indexOf(theirs, 6_000));

        assertThat(blockAt(outcome.snapshot(), 0, 0, 1, 1, 1))
                .as("my build survived even though their clock reads later")
                .isEqualTo(7);
        assertThat(blockAt(outcome.snapshot(), 1, 1, 2, 2, 2))
                .as("and so did theirs")
                .isEqualTo(9);
        assertThat(outcome.contested()).isZero();
    }

    @Test
    void twoPeersWhoBuiltInTheSameChunkStillBothKeepTheirWork() {
        // The case per-column recency gets wrong: same chunk, different blocks. A column-level
        // winner-takes-all discards up to ninety-eight thousand blocks of somebody's build for
        // having stopped a second earlier.
        List<ChunkColumnState> mineColumns = edited(0, 0, 1, 1, 1, 7);
        List<ChunkColumnState> theirColumns = edited(0, 0, 2, 2, 2, 9);
        RegionSnapshot ancestor = region(1, columns());
        RegionSnapshot mine = region(2, mineColumns);
        RegionSnapshot theirs = region(2, theirColumns);

        RegionMerger.Outcome outcome = RegionMerger.reconcile(
                ancestor, mine, indexOf(mine, 5_000), theirs, indexOf(theirs, 9_000));

        assertThat(blockAt(outcome.snapshot(), 0, 0, 1, 1, 1)).isEqualTo(7);
        assertThat(blockAt(outcome.snapshot(), 0, 0, 2, 2, 2)).isEqualTo(9);
        assertThat(outcome.merged()).isEqualTo(1);
        assertThat(outcome.contested()).isZero();
    }

    @Test
    void onlyAPositionBothChangedDifferentlyIsEverOverwritten() {
        RegionSnapshot ancestor = region(1, columns());
        RegionSnapshot mine = region(2, edited(0, 0, 3, 3, 3, 21));
        RegionSnapshot theirs = region(2, edited(0, 0, 3, 3, 3, 22));

        RegionMerger.Outcome outcome = RegionMerger.reconcile(
                ancestor, mine, indexOf(mine, 9_000), theirs, indexOf(theirs, 5_000));

        assertThat(blockAt(outcome.snapshot(), 0, 0, 3, 3, 3))
                .as("mine is newer, so mine wins the one contested position")
                .isEqualTo(21);
        assertThat(outcome.contested()).isEqualTo(1);
    }

    @Test
    void bothPeersRunningTheMergeLandInTheSamePlace() {
        RegionSnapshot ancestor = region(1, columns());
        RegionSnapshot a = region(2, edited(0, 0, 1, 1, 1, 7));
        RegionSnapshot b = region(2, edited(0, 0, 2, 2, 2, 9));
        RegionChunkIndex aIndex = indexOf(a, 9_000);
        RegionChunkIndex bIndex = indexOf(b, 5_000);

        RegionMerger.Outcome onA = RegionMerger.reconcile(ancestor, a, aIndex, b, bIndex);
        RegionMerger.Outcome onB = RegionMerger.reconcile(ancestor, b, bIndex, a, aIndex);

        assertThat(onA.index().root())
                .as("a merge both peers perform must leave both holding the same region")
                .isEqualTo(onB.index().root());
    }

    @Test
    void aColumnOnlyOneSideHasIsAdoptedWhole() {
        RegionSnapshot mine = region(2, columns());
        List<ChunkColumnState> extra = columns();
        extra.add(blank(5, 5).withBlock(0, 1, 1, 1, 33));
        RegionSnapshot theirs = region(2, extra);

        RegionMerger.Outcome outcome = RegionMerger.reconcile(
                null, mine, indexOf(mine, 5_000), theirs, indexOf(theirs, 6_000));

        assertThat(outcome.snapshot().chunks()).hasSize(5);
        assertThat(blockAt(outcome.snapshot(), 5, 5, 1, 1, 1)).isEqualTo(33);
        assertThat(outcome.tookTheirs()).isEqualTo(1);
    }

    @Test
    void mergingTwoIdenticalCopiesChangesNothing() {
        RegionSnapshot mine = region(2, edited(0, 0, 1, 1, 1, 7));
        RegionSnapshot theirs = region(3, edited(0, 0, 1, 1, 1, 7));

        RegionMerger.Outcome outcome = RegionMerger.reconcile(
                region(1, columns()), mine, indexOf(mine, 5_000), theirs, indexOf(theirs, 9_000));

        assertThat(outcome.merged()).isZero();
        assertThat(outcome.contested()).isZero();
        assertThat(outcome.index().root())
                .as("identical content, whatever the counters said")
                .isEqualTo(indexOf(mine, 5_000).root());
    }

    private static int blockAt(RegionSnapshot snapshot, int chunkX, int chunkZ,
                               int x, int y, int z) {
        for (ChunkColumnState column : snapshot.chunks()) {
            if (column.chunkX() == chunkX && column.chunkZ() == chunkZ) {
                return column.blockAt(0, x, y, z);
            }
        }
        throw new IllegalStateException("no column " + chunkX + "," + chunkZ);
    }
}
