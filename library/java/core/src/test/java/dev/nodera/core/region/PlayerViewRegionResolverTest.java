package dev.nodera.core.region;

import dev.nodera.core.NoderaConstants;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Geometry of decentralized field-of-view region ownership: a player's render-distance disc → the set
 * of static grid regions they activate. Asserts the activated set is a growing <em>circle</em> (not a
 * square), sign-correct, and deterministic.
 */
final class PlayerViewRegionResolverTest {

    private static final DimensionKey OW = DimensionKey.overworld();

    private PlayerView viewAtChunk(int cx, int cz, int renderDist) {
        // Place the player at the centre of the given chunk (block = chunk*16 + 8).
        return PlayerView.fromBlock(OW, cx * 16 + 8, cz * 16 + 8, renderDist);
    }

    @Test
    void alwaysActivatesTheCenterRegion() {
        PlayerView view = viewAtChunk(0, 0, NoderaConstants.MIN_RENDER_DISTANCE_CHUNKS);
        assertThat(PlayerViewRegionResolver.activeRegions(view)).contains(view.centerRegion());
        assertThat(PlayerViewRegionResolver.covers(view, view.centerRegion())).isTrue();
    }

    @Test
    void largerRenderDistanceActivatesMoreRegions() {
        PlayerView near = viewAtChunk(100, 100, 4);
        PlayerView far = viewAtChunk(100, 100, 24);
        int nearCount = PlayerViewRegionResolver.activeRegions(near).size();
        int farCount = PlayerViewRegionResolver.activeRegions(far).size();
        assertThat(farCount).isGreaterThan(nearCount);
    }

    @Test
    void activatedSetIsACircleNotASquare() {
        // Big disc centred on a region origin so the bounding box has clear corners.
        PlayerView view = viewAtChunk(0, 0, 20);
        Set<RegionId> active = PlayerViewRegionResolver.activeRegions(view);

        // The extreme corner region of the bounding box must NOT be activated (a square would include
        // it) — proving the disc is round. Bounding box spans regions roughly [-3..3] on each axis for
        // r=20 chunks; the far corner region is well outside the Euclidean radius.
        int size = NoderaConstants.REGION_SIZE_CHUNKS;
        int cornerRegion = Math.floorDiv(20, size) + 1; // just past the +x/+z reach of the disc
        RegionId farCorner = new RegionId(OW, cornerRegion, cornerRegion);
        assertThat(active).doesNotContain(farCorner);

        // A region straight along the +x axis at a similar radius IS reachable (axis reaches farther
        // than the diagonal in a circle) — the hallmark of a disc.
        RegionId axis = new RegionId(OW, Math.floorDiv(18, size), 0);
        assertThat(active).contains(axis);
    }

    @Test
    void handlesNegativeCoordinates() {
        PlayerView view = viewAtChunk(-1, -9, 6);
        Set<RegionId> active = PlayerViewRegionResolver.activeRegions(view);
        // Center chunk (-1,-9) → region (-1,-2) per floorDiv on the 8-grid.
        assertThat(active).contains(new RegionId(OW, -1, -2));
        // Every activated region really does intersect the disc.
        assertThat(active).allSatisfy(r -> assertThat(PlayerViewRegionResolver.covers(view, r)).isTrue());
    }

    @Test
    void neverCoversAnotherDimension() {
        PlayerView view = viewAtChunk(0, 0, 16);
        RegionId nether = new RegionId(DimensionKey.of("minecraft", "the_nether"), 0, 0);
        assertThat(PlayerViewRegionResolver.covers(view, nether)).isFalse();
        assertThat(PlayerViewRegionResolver.activeRegions(view))
                .allSatisfy(r -> assertThat(r.dimension()).isEqualTo(OW));
    }

    @Test
    void isDeterministic() {
        PlayerView view = viewAtChunk(37, -52, 14);
        assertThat(PlayerViewRegionResolver.activeRegions(view))
                .isEqualTo(PlayerViewRegionResolver.activeRegions(view));
    }

    @Test
    void renderDistanceIsClampedToBounds() {
        PlayerView tiny = PlayerView.fromBlock(OW, 0, 0, 0);
        PlayerView huge = PlayerView.fromBlock(OW, 0, 0, 9999);
        assertThat(tiny.renderDistanceChunks()).isEqualTo(NoderaConstants.MIN_RENDER_DISTANCE_CHUNKS);
        assertThat(huge.renderDistanceChunks()).isEqualTo(NoderaConstants.MAX_RENDER_DISTANCE_CHUNKS);
    }
}
