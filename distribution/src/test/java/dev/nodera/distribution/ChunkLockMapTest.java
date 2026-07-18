package dev.nodera.distribution;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lock-until-arrived (Task 19 / L-33). The defaults are the interesting part: an untracked region
 * and an unverified piece must both read as <b>locked</b>, because the failure mode of a
 * fail-open lock map is a player editing state that has not arrived and having those edits silently
 * overwritten.
 *
 * <p>Thread-context: single test thread.
 */
final class ChunkLockMapTest {

    private static RegionSnapshotSplitter.Layout layout(RegionId region) {
        RegionSnapshot snapshot = DistFixtures.variedSnapshot(region, SnapshotVersion.INITIAL, 0L);
        return RegionSnapshotSplitter.split(snapshot, 512);
    }

    @Test
    void everyChunkStartsLockedAndUnlocksExactlyWhenItsPieceVerifies() {
        RegionId region = DistFixtures.region(1, 2);
        RegionSnapshotSplitter.Layout layout = layout(region);
        ChunkLockMap locks = new ChunkLockMap();
        locks.track(layout.manifest(), layout.pieceOfChunk());

        for (int chunk = 0; chunk < layout.pieceOfChunk().size(); chunk++) {
            assertThat(locks.isChunkEditable(region, chunk)).isFalse();
        }

        int piece = layout.pieceForChunk(0);
        locks.unlockPiece(region, piece);

        // Exactly the chunks backed by that piece unlock — not one more.
        for (int chunk = 0; chunk < layout.pieceOfChunk().size(); chunk++) {
            assertThat(locks.isChunkEditable(region, chunk))
                    .as("chunk %d (piece %d)", chunk, layout.pieceForChunk(chunk))
                    .isEqualTo(layout.pieceForChunk(chunk) == piece);
        }
        assertThat(locks.isRegionComplete(region)).isFalse();
    }

    @Test
    void anUntrackedRegionIsLockedNotOpen() {
        ChunkLockMap locks = new ChunkLockMap();
        RegionId unknown = DistFixtures.region(9, 9);

        assertThat(locks.isChunkEditable(unknown, 0)).isFalse();
        assertThat(locks.isPieceAvailable(unknown, 0)).isFalse();
        assertThat(locks.isRegionComplete(unknown)).isFalse();
        assertThat(locks.trackedRoot(unknown)).isNull();
        assertThatThrownBy(() -> locks.unlockPiece(unknown, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not tracked");
    }

    @Test
    void unlockingEveryPieceCompletesTheRegion() {
        RegionId region = DistFixtures.region(0, 0);
        RegionSnapshotSplitter.Layout layout = layout(region);
        ChunkLockMap locks = new ChunkLockMap();
        locks.track(layout.manifest(), layout.pieceOfChunk());

        for (int p = 0; p < layout.manifest().pieceCount(); p++) {
            locks.unlockPiece(region, p);
        }

        assertThat(locks.isRegionComplete(region)).isTrue();
        for (int chunk = 0; chunk < layout.pieceOfChunk().size(); chunk++) {
            assertThat(locks.isChunkEditable(region, chunk)).isTrue();
        }
    }

    @Test
    void aSupersedingManifestReLocksTheRegionRatherThanShowingStaleSections() {
        RegionId region = DistFixtures.region(3, 3);
        RegionSnapshotSplitter.Layout v0 = layout(region);
        ChunkLockMap locks = new ChunkLockMap();
        locks.track(v0.manifest(), v0.pieceOfChunk());
        for (int p = 0; p < v0.manifest().pieceCount(); p++) {
            locks.unlockPiece(region, p);
        }
        assertThat(locks.isRegionComplete(region)).isTrue();

        RegionSnapshot newer = DistFixtures.variedSnapshot(region, new SnapshotVersion(1L), 50L);
        RegionSnapshotSplitter.Layout v1 = RegionSnapshotSplitter.split(newer, 512);
        locks.track(v1.manifest(), v1.pieceOfChunk());

        assertThat(locks.isRegionComplete(region)).isFalse();
        assertThat(locks.isChunkEditable(region, 0)).isFalse();
        assertThat(locks.trackedRoot(region)).isEqualTo(v1.manifest().manifestRoot());
    }

    @Test
    void anEvictedPieceReLocksItsChunks() {
        RegionId region = DistFixtures.region(5, 5);
        RegionSnapshotSplitter.Layout layout = layout(region);
        ChunkLockMap locks = new ChunkLockMap();
        locks.track(layout.manifest(), layout.pieceOfChunk());

        locks.unlockPiece(region, 0);
        assertThat(locks.isPieceAvailable(region, 0)).isTrue();
        locks.lockPiece(region, 0);
        assertThat(locks.isPieceAvailable(region, 0)).isFalse();
        assertThat(locks.isChunkEditable(region, 0)).isFalse();
    }

    @Test
    void forgettingARegionStopsTrackingIt() {
        RegionId region = DistFixtures.region(7, 7);
        RegionSnapshotSplitter.Layout layout = layout(region);
        ChunkLockMap locks = new ChunkLockMap();
        locks.track(layout.manifest(), layout.pieceOfChunk());
        assertThat(locks.trackedRegions()).isEqualTo(1);

        locks.forget(region);

        assertThat(locks.trackedRegions()).isZero();
        assertThat(locks.isChunkEditable(region, 0)).isFalse();
    }

    @Test
    void rejectsAChunkMappingThatPointsOutsideTheManifest() {
        RegionId region = DistFixtures.region(6, 6);
        RegionSnapshotSplitter.Layout layout = layout(region);
        ChunkLockMap locks = new ChunkLockMap();

        assertThatThrownBy(() -> locks.track(layout.manifest(),
                java.util.List.of(layout.manifest().pieceCount())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
    }
}
