package dev.nodera.diagnostics.classify;

import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionBounds;
import dev.nodera.core.region.RegionId;
import dev.nodera.diagnostics.model.RegionOwnership;
import dev.nodera.diagnostics.state.OwnershipState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ZoneClassifier} geometry + ownership lookup, incl. negative coordinates (Task 18
 * acceptance #1). Region grid is 8 chunks × 8 chunks, 16 blocks per chunk.
 */
final class ZoneClassifierTest {

    private static final DimensionKey OW = DimensionKey.overworld();
    private static final RegionId ORIGIN = new RegionId(OW, 0, 0);
    private static final RegionId EAST = new RegionId(OW, 1, 0);
    private static final RegionId NORTH_WEST = new RegionId(OW, -1, -1);

    @Test
    void mapsBlockToRegionWithFloorDiv() {
        // Block (0,0) → chunk 0 → region (0,0).
        assertThat(ZoneClassifier.regionAt(OW, 0, 0)).isEqualTo(ORIGIN);
        // Block 127 → chunk 7 → still region (0,0) (8 chunks per region).
        assertThat(ZoneClassifier.regionAt(OW, 127, 127)).isEqualTo(ORIGIN);
        // Block 128 → chunk 8 → region (1,0).
        assertThat(ZoneClassifier.regionAt(OW, 128, 0)).isEqualTo(EAST);
    }

    @Test
    void negativeCoordinatesMapCorrectly() {
        // Block -1 → chunk -1 → region floorDiv(-1,8) = -1.
        assertThat(ZoneClassifier.regionAt(OW, -1, -1)).isEqualTo(NORTH_WEST);
        // Block -128 → chunk -8 → region -1; block -129 → chunk -9 → region -2.
        assertThat(ZoneClassifier.regionAt(OW, -128, -128).regionX()).isEqualTo(-1);
        assertThat(ZoneClassifier.regionAt(OW, -129, -129).regionX()).isEqualTo(-2);
        RegionId r2 = new RegionId(OW, -2, -2);
        assertThat(ZoneClassifier.regionAt(OW, -129, -129)).isEqualTo(r2);
    }

    @Test
    void emptyOwnershipIsUnassignedEverywhere() {
        RegionOwnership empty = RegionOwnership.empty();
        assertThat(ZoneClassifier.classify(OW, 0, 0, empty)).isEqualTo(OwnershipState.UNASSIGNED);
        assertThat(ZoneClassifier.classify(OW, -1000, 500, empty)).isEqualTo(OwnershipState.UNASSIGNED);
    }

    @Test
    void classifiesOwnedValidatorReplica() {
        RegionOwnership own = new RegionOwnership(
                List.of(ORIGIN), List.of(EAST), List.of(NORTH_WEST), 64, Map.of());

        assertThat(ZoneClassifier.classify(OW, 0, 0, own)).isEqualTo(OwnershipState.OWNED);
        assertThat(ZoneClassifier.classify(OW, 200, 0, own)).isEqualTo(OwnershipState.VALIDATING);
        assertThat(ZoneClassifier.classify(OW, -1, -1, own)).isEqualTo(OwnershipState.REPLICA);
    }

    @Test
    void unlistedRegionIsForeignWhenSomethingIsDelegated() {
        RegionOwnership own = new RegionOwnership(List.of(ORIGIN), List.of(), List.of(), 64, Map.of());
        // Region (1,0) is not in any set, but the peer owns (0,0) → foreign.
        assertThat(ZoneClassifier.classify(OW, 128, 0, own)).isEqualTo(OwnershipState.FOREIGN);
    }

    @Test
    void classifyIsRegionGranularAndConsistentWithRegionBounds() {
        // Classification is by owning RegionId only (not halo-aware) — pin the geometric contract
        // Task 6 will hand off against RegionBounds.ownsBlock / isHaloBlock.
        RegionOwnership own = new RegionOwnership(List.of(ORIGIN), List.of(), List.of(), 64, Map.of());
        RegionBounds bounds = RegionBounds.of(ORIGIN);

        // A strictly-interior owned block: owned by region (0,0) → OWNED, and RegionBounds agrees.
        assertThat(ZoneClassifier.classify(OW, 8, 8, own)).isEqualTo(OwnershipState.OWNED);
        assertThat(bounds.ownsBlock(8, 8)).isTrue();

        // A block in region (0,0)'s halo ring: it belongs to the ADJACENT region (-1,0) (chunk -1),
        // which is not delegated → FOREIGN, even though RegionBounds calls it a halo block of (0,0).
        assertThat(bounds.isHaloBlock(-1, 0)).isTrue();
        assertThat(ZoneClassifier.regionAt(OW, -1, 0)).isEqualTo(new RegionId(OW, -1, 0));
        assertThat(ZoneClassifier.classify(OW, -1, 0, own)).isEqualTo(OwnershipState.FOREIGN);
    }
}
