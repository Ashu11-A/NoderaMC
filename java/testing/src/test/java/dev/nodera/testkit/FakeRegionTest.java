package dev.nodera.testkit;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.protocol.simulationmsg.ActionBatchMsg;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.simulation.rules.FlatWorldRules;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FakeRegion} fixture-builder checks: geometry, palette contents, and codec round-trips for
 * the assembled envelopes / batch.
 *
 * <p>Thread-context: single test thread.
 */
final class FakeRegionTest {

    private static final int EXPECTED_CHUNKS =
            NoderaConstants.REGION_SIZE_CHUNKS * NoderaConstants.REGION_SIZE_CHUNKS;
    private static final int EXPECTED_SECTIONS =
            (FlatWorldRules.MAX_Y - FlatWorldRules.MIN_Y + 1) / 16;

    @Test
    void emptyFlatSnapshotIsAnEightByEightAllAirRegion() {
        RegionId region = FakeRegion.overworldRegion(3, -2);
        SnapshotVersion version = new SnapshotVersion(7L);
        RegionSnapshot snapshot = FakeRegion.emptyFlatSnapshot(region, version, 100L);

        assertThat(snapshot.region()).isEqualTo(region);
        assertThat(snapshot.version()).isEqualTo(version);
        assertThat(snapshot.tick()).isEqualTo(100L);
        assertThat(snapshot.chunks()).hasSize(EXPECTED_CHUNKS);

        int originX = region.originChunkX();
        int originZ = region.originChunkZ();
        int index = 0;
        for (int dx = 0; dx < NoderaConstants.REGION_SIZE_CHUNKS; dx++) {
            for (int dz = 0; dz < NoderaConstants.REGION_SIZE_CHUNKS; dz++) {
                ChunkColumnState chunk = snapshot.chunks().get(index++);
                assertThat(chunk.chunkX()).isEqualTo(originX + dx);
                assertThat(chunk.chunkZ()).isEqualTo(originZ + dz);
                assertThat(chunk.minY()).isEqualTo(FlatWorldRules.MIN_Y);
                assertThat(chunk.sectionCount()).isEqualTo(EXPECTED_SECTIONS);
                int[] palette = chunk.paletteStateIdsPerSection();
                assertThat(palette).hasSize(EXPECTED_SECTIONS);
                for (int id : palette) {
                    assertThat(id).isEqualTo(FlatWorldRules.AIR);
                }
            }
        }
    }

    @Test
    void stoneLayerSnapshotPlacesStoneInTheContainingSectionOnly() {
        RegionId region = FakeRegion.overworldRegion(0, 0);
        int y = 0;
        int expectedSection = (y - FlatWorldRules.MIN_Y) / 16;

        RegionSnapshot snapshot =
                FakeRegion.stoneLayerSnapshot(region, SnapshotVersion.INITIAL, 0L, y);

        assertThat(snapshot.chunks()).hasSize(EXPECTED_CHUNKS);
        for (ChunkColumnState chunk : snapshot.chunks()) {
            int[] palette = chunk.paletteStateIdsPerSection();
            for (int s = 0; s < palette.length; s++) {
                if (s == expectedSection) {
                    assertThat(palette[s]).isEqualTo(FlatWorldRules.STONE);
                } else {
                    assertThat(palette[s]).isEqualTo(FlatWorldRules.AIR);
                }
            }
        }
    }

    @Test
    void stoneLayerRejectsHeightsOutsideTheBuildableEnvelope() {
        RegionId region = FakeRegion.overworldRegion(0, 0);
        assertThatThrownBy(() ->
                FakeRegion.stoneLayerSnapshot(region, SnapshotVersion.INITIAL, 0L, FlatWorldRules.MIN_Y - 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                FakeRegion.stoneLayerSnapshot(region, SnapshotVersion.INITIAL, 0L, FlatWorldRules.MAX_Y + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void placeBreakAndBatchRoundTripThroughMessageCodec() {
        RegionId region = FakeRegion.overworldRegion(1, 1);
        RegionEpoch epoch = RegionEpoch.INITIAL;
        NodeId actor = NodeId.random();
        SnapshotVersion base = new SnapshotVersion(3L);

        ActionEnvelope place = FakeRegion.place(
                region, actor, 1L, 50L, new NBlockPos(16, 64, 16), FlatWorldRules.STONE);
        ActionEnvelope brk = FakeRegion.breakBlock(
                region, actor, 2L, 51L, new NBlockPos(16, 64, 16));

        assertThat(place.signature().length()).isEqualTo(FakeRegion.SIGNATURE_BYTES);
        assertThat(brk.signature().length()).isEqualTo(FakeRegion.SIGNATURE_BYTES);

        ActionBatch batch = FakeRegion.batch(
                region, epoch, base, 50L, 51L, place, brk);
        assertThat(batch.actions()).containsExactly(place, brk);

        ActionBatchMsg wire = new ActionBatchMsg(batch);
        byte[] frame = MessageCodec.encode(wire);

        ActionBatchMsg decoded = (ActionBatchMsg) MessageCodec.decode(frame);
        assertThat(decoded.batch()).isEqualTo(batch);
    }

    @Test
    void emptyFlatSnapshotEncodesStablyAcrossCalls() {
        RegionId region = FakeRegion.overworldRegion(0, 0);
        SnapshotVersion version = new SnapshotVersion(1L);
        RegionSnapshot s1 = FakeRegion.emptyFlatSnapshot(region, version, 99L);
        RegionSnapshot s2 = FakeRegion.emptyFlatSnapshot(region, version, 99L);
        assertThat(s1).isEqualTo(s2);
        assertThat(encode(s1)).isEqualTo(encode(s2));
    }

    private static byte[] encode(RegionSnapshot snapshot) {
        dev.nodera.core.crypto.CanonicalWriter w = new dev.nodera.core.crypto.CanonicalWriter();
        snapshot.encode(w);
        return w.toByteArray();
    }
}
