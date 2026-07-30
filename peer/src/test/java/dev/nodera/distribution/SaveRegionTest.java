package dev.nodera.distribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every byte a peer stores or sends has to name the region it belongs to, and the name has to be
 * the game's own: {@code r.X.Z.mca}, 32×32 chunks, 512×512 blocks.
 */
final class SaveRegionTest {

    @Test
    @DisplayName("the grid is Minecraft's: 32 chunks and 512 blocks to a region")
    void theGridMatchesTheGame() {
        assertThat(SaveRegion.CHUNKS_PER_REGION).isEqualTo(32);
        assertThat(SaveRegion.BLOCKS_PER_REGION).isEqualTo(512);

        assertThat(SaveRegion.ofChunk("", 0, 0)).isEqualTo(new SaveRegion("", 0, 0));
        assertThat(SaveRegion.ofChunk("", 31, 31)).isEqualTo(new SaveRegion("", 0, 0));
        assertThat(SaveRegion.ofChunk("", 32, 0)).isEqualTo(new SaveRegion("", 1, 0));
        // Negative coordinates floor, they do not truncate: chunk -1 is in region -1, not region 0.
        assertThat(SaveRegion.ofChunk("", -1, -1)).isEqualTo(new SaveRegion("", -1, -1));
        assertThat(SaveRegion.ofChunk("", -32, -33)).isEqualTo(new SaveRegion("", -1, -2));

        assertThat(SaveRegion.ofBlock("", 511, 511)).isEqualTo(new SaveRegion("", 0, 0));
        assertThat(SaveRegion.ofBlock("", 512, 0)).isEqualTo(new SaveRegion("", 1, 0));
        assertThat(SaveRegion.ofBlock("", -1, 0)).isEqualTo(new SaveRegion("", -1, 0));
    }

    @Test
    @DisplayName("a region names itself the way the save does")
    void fileNamesRoundTrip() {
        assertThat(new SaveRegion("", 0, 0).fileName()).isEqualTo("r.0.0.mca");
        assertThat(new SaveRegion("", -1, 2).fileName()).isEqualTo("r.-1.2.mca");
        assertThatThrownBy(SaveRegion.WORLD::fileName).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("every save tree of every dimension resolves to its region")
    void pathsResolve() {
        assertThat(SaveRegion.of("region/r.0.0.mca")).isEqualTo(new SaveRegion("", 0, 0));
        assertThat(SaveRegion.of("entities/r.0.0.mca")).isEqualTo(new SaveRegion("", 0, 0));
        assertThat(SaveRegion.of("poi/r.-2.3.mca")).isEqualTo(new SaveRegion("", -2, 3));
        assertThat(SaveRegion.of("DIM-1/region/r.1.1.mca")).isEqualTo(new SaveRegion("DIM-1", 1, 1));
        assertThat(SaveRegion.of("DIM1/entities/r.0.-1.mca"))
                .isEqualTo(new SaveRegion("DIM1", 0, -1));
        assertThat(SaveRegion.of("dimensions/mymod/cavern/region/r.4.4.mca"))
                .isEqualTo(new SaveRegion("dimensions/mymod/cavern", 4, 4));

        // The three trees of one dimension are ONE region: blocks, entities and points of interest
        // for the same 512×512 belong together, and a peer holding the ground must hold what is
        // standing on it.
        assertThat(SaveRegion.of("region/r.5.5.mca")).isEqualTo(SaveRegion.of("entities/r.5.5.mca"))
                .isEqualTo(SaveRegion.of("poi/r.5.5.mca"));
    }

    @Test
    @DisplayName("what is not region data is named as world data, not left unaddressed")
    void everythingElseIsTheWorldBucket() {
        for (String path : new String[] {
                "level.dat", "nodera-world.dat", "playerdata/uuid.dat", "data/raids.dat",
                "advancements/uuid.json", "stats/uuid.json", "serverconfig/x.toml"}) {
            assertThat(SaveRegion.of(path)).as(path).isEqualTo(SaveRegion.WORLD);
        }
        // A region-shaped name somewhere the game never puts one is world data: filing it by
        // coordinate would hand it to whichever peer serves that coordinate, from a save that has
        // no such file.
        assertThat(SaveRegion.of("data/r.0.0.mca")).isEqualTo(SaveRegion.WORLD);
        assertThat(SaveRegion.of("region/r.0.mca")).isEqualTo(SaveRegion.WORLD);
        assertThat(SaveRegion.of("region/r.x.0.mca")).isEqualTo(SaveRegion.WORLD);
        assertThat(SaveRegion.of("region/r.0.0.mcc")).isEqualTo(SaveRegion.WORLD);
    }

    @Test
    @DisplayName("a save region can never be mistaken for a simulated region")
    void thePieceIdIsDistinctFromTheSimulationGrid() {
        // Nodera simulates 8-chunk regions; the save stores 32-chunk ones. Region (0,0) exists in
        // both grids and means different areas, so the piece plane has to keep them apart.
        var simulated = new dev.nodera.core.region.RegionId(
                dev.nodera.core.region.DimensionKey.overworld(), 0, 0);
        assertThat(new SaveRegion("", 0, 0).toRegionId()).isNotEqualTo(simulated);
        assertThat(new SaveRegion("", 0, 0).toRegionId())
                .isNotEqualTo(SaveRegion.WORLD.toRegionId());
        // Distinct dimensions stay distinct, and the id is stable for the same region.
        assertThat(new SaveRegion("DIM-1", 0, 0).toRegionId())
                .isNotEqualTo(new SaveRegion("", 0, 0).toRegionId())
                .isEqualTo(new SaveRegion("DIM-1", 0, 0).toRegionId());
    }
}
