package dev.nodera.simulation.border;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.rules.FlatWorldRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two halves that were missing (engine L-2): cutting a slice and holding one.
 *
 * <p>{@link RegionHalo} could always answer a border read from neighbour columns — but no
 * production code ever produced a slice, so every halo on the network was empty and the fluid
 * automaton correctly concluded there was nothing beyond the boundary. These tests pin the cut and
 * the assembly; {@code HaloExchangeIT} in the peer module pins the wire between them.
 */
final class HaloExchangeTest {

    private static final int MIN_Y = -64;
    private static final int SECTIONS = 24;
    private static final int AIR_SECTION = 8;
    private static final int Y = MIN_Y + AIR_SECTION * 16;

    private static final RegionId ORIGIN = TestFixtures.region(0, 0);
    private static final RegionId EAST = TestFixtures.region(1, 0);
    private static final RegionId FAR = TestFixtures.region(4, 0);

    /** ORIGIN with a stone floor and water filling its EASTERN-most chunk columns. */
    private static RegionSnapshot originWithEasternWater(SnapshotVersion version) {
        List<ChunkColumnState> chunks = new ArrayList<>();
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                int[] sections = new int[SECTIONS];
                sections[AIR_SECTION - 1] = FlatWorldRules.STONE;
                if (dx == 7) {
                    sections[AIR_SECTION] = FlatWorldRules.WATER_SOURCE;
                }
                chunks.add(new ChunkColumnState(ORIGIN.originChunkX() + dx,
                        ORIGIN.originChunkZ() + dz, sections, MIN_Y, SECTIONS));
            }
        }
        return new RegionSnapshot(ORIGIN, version, 0, chunks);
    }

    @Test
    @DisplayName("a region has exactly its eight grid neighbours, in a deterministic order")
    void neighboursAreTheEightAdjacentRegions() {
        List<RegionId> neighbours = HaloSlicer.neighboursOf(ORIGIN);

        assertThat(neighbours).hasSize(8).doesNotContain(ORIGIN).contains(EAST);
        assertThat(neighbours)
                .as("same input, same order — two nodes cut the same commit identically")
                .isEqualTo(HaloSlicer.neighboursOf(ORIGIN));
    }

    @Test
    @DisplayName("the cut is edge columns only, chosen by what the RECEIVER reads")
    void sliceCarriesOnlyTheReceiversHaloRing() {
        RegionSnapshot snapshot = originWithEasternWater(SnapshotVersion.INITIAL);

        RegionHalo.Slice slice = HaloSlicer.sliceFor(snapshot, EAST);

        assertThat(slice).isNotNull();
        assertThat(slice.source()).isEqualTo(ORIGIN);
        assertThat(slice.columns())
                .as("8 columns of a 64-column region: the shared edge, never the region")
                .hasSize(8)
                .allMatch(column -> column.chunkX() == EAST.originChunkX() - 1);
    }

    @Test
    @DisplayName("regions that share no ring produce no slice at all")
    void distantRegionsShareNothing() {
        assertThat(HaloSlicer.sliceFor(originWithEasternWater(SnapshotVersion.INITIAL), FAR))
                .isNull();
    }

    @Test
    @DisplayName("a stored slice becomes a halo the border cell can actually read")
    void storedSliceBecomesAReadableHalo() {
        HaloStore store = new HaloStore();

        assertThat(store.haloFor(EAST).isEmpty())
                .as("before any delivery the halo is empty — the pre-exchange behaviour")
                .isTrue();
        assertThat(store.accept(EAST,
                HaloSlicer.sliceFor(originWithEasternWater(SnapshotVersion.INITIAL), EAST)))
                .isTrue();

        RegionHalo halo = store.haloFor(EAST);
        assertThat(halo.isEmpty()).isFalse();
        assertThat(halo.getBlock(new NBlockPos(EAST.originChunkX() * 16 - 1, Y, 0)))
                .as("the neighbour's water, readable from the receiving region's own border cell")
                .isEqualTo(FlatWorldRules.WATER_SOURCE);
    }

    @Test
    @DisplayName("newest wins and a late older slice never rewinds the border")
    void staleSlicesAreDropped() {
        HaloStore store = new HaloStore();
        RegionHalo.Slice newer =
                HaloSlicer.sliceFor(originWithEasternWater(new SnapshotVersion(7)), EAST);
        RegionHalo.Slice older =
                HaloSlicer.sliceFor(originWithEasternWater(new SnapshotVersion(3)), EAST);

        assertThat(store.accept(EAST, newer)).isTrue();
        assertThat(store.accept(EAST, older))
                .as("delivery order must not decide what a region reads across its border")
                .isFalse();
        assertThat(store.versionOf(EAST, ORIGIN)).contains(new SnapshotVersion(7));
    }

    @Test
    @DisplayName("a region cannot halo itself, and a forgotten region reads nothing")
    void selfSlicesAreRefusedAndForgettingClears() {
        HaloStore store = new HaloStore();
        RegionHalo.Slice self = new RegionHalo.Slice(EAST, SnapshotVersion.INITIAL,
                HaloSlicer.edgeColumnsFor(originWithEasternWater(SnapshotVersion.INITIAL), EAST));

        assertThat(store.accept(EAST, self))
                .as("owned state comes from the snapshot; the border door is not a way in")
                .isFalse();

        store.accept(EAST, HaloSlicer.sliceFor(originWithEasternWater(SnapshotVersion.INITIAL), EAST));
        assertThat(store.targets()).contains(EAST);
        store.forget(EAST);
        assertThat(store.haloFor(EAST).isEmpty()).isTrue();
        assertThat(store.versionOf(EAST, ORIGIN)).isEmpty();
    }
}
