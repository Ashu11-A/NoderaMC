package dev.nodera.coordinator;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegionChunkHoldsTest {

    private static final DimensionKey OVERWORLD = DimensionKey.of("minecraft", "overworld");

    private static RegionId region(int x, int z) {
        return new RegionId(OVERWORLD, x, z);
    }

    @Test
    @DisplayName("a region covers exactly its own chunk square, once")
    void chunksOfCoversTheRegion() {
        var chunks = RegionChunkHolds.chunksOf(region(1, -2));
        int expected = NoderaConstants.REGION_SIZE_CHUNKS * NoderaConstants.REGION_SIZE_CHUNKS;
        assertThat(chunks).hasSize(expected).doesNotHaveDuplicates();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.chunkX()).isBetween(
                    NoderaConstants.REGION_SIZE_CHUNKS, 2 * NoderaConstants.REGION_SIZE_CHUNKS - 1);
            assertThat(chunk.chunkZ()).isBetween(
                    -2 * NoderaConstants.REGION_SIZE_CHUNKS,
                    -NoderaConstants.REGION_SIZE_CHUNKS - 1);
        });
        assertThatThrownBy(() -> RegionChunkHolds.chunksOf(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the first hold is the only one the game hears about")
    void repeatedHoldsAreIdempotent() {
        RegionChunkHolds holds = new RegionChunkHolds();
        int square = NoderaConstants.REGION_SIZE_CHUNKS * NoderaConstants.REGION_SIZE_CHUNKS;

        assertThat(holds.hold(region(0, 0)).newlyHeld()).hasSize(square);
        assertThat(holds.hold(region(0, 0)).isEmpty()).isTrue();
        assertThat(holds.heldChunks()).isEqualTo(square);
    }

    @Test
    @DisplayName("releasing one region never unloads a chunk another region still holds")
    void overlappingHoldsAreRefCounted() {
        RegionChunkHolds holds = new RegionChunkHolds();
        // Two regions do not share chunks by construction, so the overlap that matters is the same
        // region held twice — two lanes on one node, which is exactly the entity/block pair.
        RegionId shared = region(3, 3);
        holds.hold(shared);
        holds.hold(region(4, 3));

        var released = holds.release(region(4, 3)).released();
        assertThat(released).hasSize(
                NoderaConstants.REGION_SIZE_CHUNKS * NoderaConstants.REGION_SIZE_CHUNKS);
        // The other region's chunks survived its neighbour's release.
        assertThat(holds.isHeld(shared.originChunkX(), shared.originChunkZ())).isTrue();
        assertThat(holds.isHeld(region(4, 3).originChunkX(), region(4, 3).originChunkZ())).isFalse();
    }

    @Test
    @DisplayName("releasing something never held is a no-op, not a phantom release")
    void releasingAnUnheldRegionReportsNothing() {
        RegionChunkHolds holds = new RegionChunkHolds();
        assertThat(holds.release(region(9, 9)).isEmpty()).isTrue();
        assertThat(holds.heldChunks()).isZero();
    }

    @Test
    @DisplayName("shutdown releases every chunk exactly once")
    void releaseAllDrainsTheTable() {
        RegionChunkHolds holds = new RegionChunkHolds();
        holds.hold(region(0, 0));
        holds.hold(region(0, 1));
        int square = NoderaConstants.REGION_SIZE_CHUNKS * NoderaConstants.REGION_SIZE_CHUNKS;

        var released = holds.releaseAll().released();

        assertThat(released).hasSize(2 * square).doesNotHaveDuplicates();
        assertThat(holds.heldChunks()).isZero();
        assertThat(holds.releaseAll().isEmpty()).isTrue();
    }
}
