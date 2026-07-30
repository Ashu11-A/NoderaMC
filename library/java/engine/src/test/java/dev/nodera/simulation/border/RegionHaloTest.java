package dev.nodera.simulation.border;

import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.rules.FlatWorldRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The halo stopped being a stub (engine L-2). These tests pin the two things that make it safe to
 * read: a neighbour may only describe cells this region does not own, and two replicas handed the
 * same slices in different orders must build the same halo.
 */
final class RegionHaloTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 1, 1);
    private static final RegionId WEST = new RegionId(DimensionKey.overworld(), 0, 1);
    private static final int MIN_Y = -64;
    private static final int SECTIONS = 24;

    private static ChunkColumnState column(int chunkX, int chunkZ, int section, int id) {
        int[] sections = new int[SECTIONS];
        sections[section] = id;
        return new ChunkColumnState(chunkX, chunkZ, sections, MIN_Y, SECTIONS);
    }

    private static RegionHalo.Slice slice(RegionId source, long version, ChunkColumnState... cols) {
        return new RegionHalo.Slice(source, new SnapshotVersion(version), List.of(cols));
    }

    @Test
    @DisplayName("an empty halo reads AIR everywhere — the behaviour every caller already had")
    void theDefaultIsUnchanged() {
        RegionHalo halo = new RegionHalo(REGION);
        assertThat(halo.isEmpty()).isTrue();
        assertThat(halo.getBlock(new NBlockPos(0, 70, 0))).isEqualTo(RegionHalo.AIR);
        assertThat(halo.backedColumns()).isZero();
    }

    @Test
    @DisplayName("a neighbour's edge column answers reads in the halo ring")
    void aBackedColumnIsRead() {
        // Region (1,1) owns chunks 8..15; chunk 7 is its western halo ring.
        RegionHalo halo = new RegionHalo(REGION,
                List.of(slice(WEST, 4, column(7, 8, 8, FlatWorldRules.WATER_SOURCE))));

        assertThat(halo.backedColumns()).isEqualTo(1);
        assertThat(halo.getBlock(new NBlockPos(7 * 16 + 3, MIN_Y + 8 * 16 + 2, 8 * 16 + 1)))
                .isEqualTo(FlatWorldRules.WATER_SOURCE);
        // A section the slice says nothing about is still air.
        assertThat(halo.getBlock(new NBlockPos(7 * 16, MIN_Y, 8 * 16))).isEqualTo(RegionHalo.AIR);
        assertThat(halo.versionOf(WEST)).contains(new SnapshotVersion(4));
    }

    @Test
    @DisplayName("a column inside this region's OWNED area is dropped, not believed")
    void aNeighbourCannotDescribeOwnedGround() {
        // Chunk (8,8) is owned by region (1,1). A neighbour claiming it would be defining state
        // inside this region's own area — the one thing a halo must never let through.
        RegionHalo halo = new RegionHalo(REGION,
                List.of(slice(WEST, 1, column(8, 8, 8, FlatWorldRules.LAVA_SOURCE))));

        assertThat(halo.isEmpty()).isTrue();
        assertThat(halo.getBlock(new NBlockPos(8 * 16, MIN_Y + 8 * 16, 8 * 16)))
                .isEqualTo(RegionHalo.AIR);
    }

    @Test
    @DisplayName("a column outside the footprint entirely is dropped too")
    void farAwayColumnsAreDropped() {
        RegionHalo halo = new RegionHalo(REGION,
                List.of(slice(WEST, 1, column(-40, -40, 8, FlatWorldRules.WATER_SOURCE))));
        assertThat(halo.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("delivery order cannot change the halo two replicas build")
    void assemblyIsOrderIndependent() {
        RegionId north = new RegionId(DimensionKey.overworld(), 1, 0);
        RegionHalo.Slice a = slice(WEST, 2, column(7, 8, 8, FlatWorldRules.WATER_SOURCE));
        RegionHalo.Slice b = slice(north, 3, column(8, 7, 8, FlatWorldRules.LAVA_SOURCE));

        RegionHalo one = new RegionHalo(REGION, List.of(a, b));
        RegionHalo other = new RegionHalo(REGION, List.of(b, a));

        // Two replicas that received the same slices in different orders must execute identically,
        // or they diverge with neither of them wrong.
        assertThat(one.slices()).isEqualTo(other.slices());
        assertThat(one.backing()).isEqualTo(other.backing());
        assertThat(one.getBlock(new NBlockPos(7 * 16, MIN_Y + 8 * 16, 8 * 16)))
                .isEqualTo(other.getBlock(new NBlockPos(7 * 16, MIN_Y + 8 * 16, 8 * 16)));
    }

    @Test
    @DisplayName("a slice's columns are canonically ordered whatever order they arrive in")
    void sliceColumnsAreCanonical() {
        List<ChunkColumnState> shuffled = new ArrayList<>(List.of(
                column(7, 9, 8, 1), column(7, 7, 8, 1), column(7, 8, 8, 1)));
        RegionHalo.Slice s = new RegionHalo.Slice(WEST, SnapshotVersion.INITIAL, shuffled);

        assertThat(s.columns()).extracting(ChunkColumnState::chunkZ).containsExactly(7, 8, 9);
    }

    @Test
    @DisplayName("a missing source reads as absent, which a freshness check must treat as stale")
    void anAbsentSourceHasNoVersion() {
        RegionHalo halo = new RegionHalo(REGION,
                List.of(slice(WEST, 9, column(7, 8, 8, FlatWorldRules.WATER_SOURCE))));
        assertThat(halo.versionOf(new RegionId(DimensionKey.overworld(), 2, 1))).isEmpty();
        assertThat(halo.versionOf(null)).isEmpty();
    }

    @Test
    @DisplayName("nulls are refused at construction and ignored at the read")
    void argumentsAreChecked() {
        assertThatThrownBy(() -> new RegionHalo(null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegionHalo(REGION, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new RegionHalo(REGION).getBlock(null)).isEqualTo(RegionHalo.AIR);
    }
}
