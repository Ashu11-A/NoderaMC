package dev.nodera.distribution;

import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.SnapshotVersion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** L-33 adapter: block position → canonical chunk ordinal → {@link ChunkLockMap} verdict. */
final class ChunkLockEditabilityTest {

    private final RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);

    private RegionSnapshotSplitter.Layout layout() {
        return RegionSnapshotSplitter.split(
                DistFixtures.variedSnapshot(region, SnapshotVersion.INITIAL, 0L), 512);
    }

    @Test
    void unregisteredRegionFailsClosedAndUnlockedPiecesOpenTheirChunks() {
        ChunkLockMap locks = new ChunkLockMap();
        ChunkLockEditability editability = new ChunkLockEditability(locks);

        // Nothing tracked: every chunk is locked (fail closed) — same rule as the map itself.
        assertThat(editability.editable(region, new NBlockPos(5, 70, 5))).isFalse();

        RegionSnapshotSplitter.Layout layout = layout();
        locks.track(layout.manifest(), layout.pieceOfChunk());
        assertThat(editability.editable(region, new NBlockPos(5, 70, 5))).isFalse();
        for (int p = 0; p < layout.manifest().pieceCount(); p++) {
            locks.unlockPiece(region, p);
        }
        assertThat(editability.editable(region, new NBlockPos(5, 70, 5))).isTrue();
        assertThat(editability.editable(region, new NBlockPos(120, 70, 120))).isTrue();
    }

    @Test
    void positionsOutsideTheRegionFootprintFailClosed() {
        ChunkLockMap locks = new ChunkLockMap();
        ChunkLockEditability editability = new ChunkLockEditability(locks);
        RegionSnapshotSplitter.Layout layout = layout();
        locks.track(layout.manifest(), layout.pieceOfChunk());
        for (int p = 0; p < layout.manifest().pieceCount(); p++) {
            locks.unlockPiece(region, p);
        }

        // x=130 lies in the neighbouring region: a halo position never opens for writing.
        assertThat(editability.editable(region, new NBlockPos(130, 70, 5))).isFalse();
        assertThat(editability.editable(region, new NBlockPos(-1, 70, 5))).isFalse();
    }
}
