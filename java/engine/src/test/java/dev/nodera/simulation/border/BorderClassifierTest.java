package dev.nodera.simulation.border;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.simulation.TestFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BorderClassifier}: region-edge classification including negative coordinates.
 *
 * <p>Thread-context: single test thread.
 */
final class BorderClassifierTest {

    @Test
    void blockInsideOwnedRegionIsNotCrossRegion() {
        RegionId region = TestFixtures.region(0, 0);
        var place = TestFixtures.place(new NBlockPos(0, 64, 0), 1);
        var brk = TestFixtures.brk(new NBlockPos(127, 64, 127));

        assertThat(BorderClassifier.isCrossRegion(place, region)).isFalse();
        assertThat(BorderClassifier.isCrossRegion(brk, region)).isFalse();
    }

    @Test
    void blockJustPastEasternAndSouthernEdgeIsCrossRegion() {
        RegionId region = TestFixtures.region(0, 0);
        var east = TestFixtures.place(new NBlockPos(128, 64, 0), 1);
        var south = TestFixtures.place(new NBlockPos(0, 64, 128), 1);

        assertThat(BorderClassifier.isCrossRegion(east, region)).isTrue();
        assertThat(BorderClassifier.isCrossRegion(south, region)).isTrue();
    }

    @Test
    void negativeRegionEdgeClassifiesCorrectly() {
        RegionId negRegion = TestFixtures.region(-1, -1);

        var inside = TestFixtures.place(new NBlockPos(-1, 64, -1), 1);
        var insideCorner = TestFixtures.place(new NBlockPos(-128, 64, -128), 1);
        var justOutside = TestFixtures.place(new NBlockPos(0, 64, 0), 1);
        var farOutside = TestFixtures.place(new NBlockPos(-129, 64, -129), 1);

        assertThat(BorderClassifier.isCrossRegion(inside, negRegion)).isFalse();
        assertThat(BorderClassifier.isCrossRegion(insideCorner, negRegion)).isFalse();
        assertThat(BorderClassifier.isCrossRegion(justOutside, negRegion)).isTrue();
        assertThat(BorderClassifier.isCrossRegion(farOutside, negRegion)).isTrue();
    }

    @Test
    void envelopeOverloadDelegatesToItsOwnRegion() {
        var insideEnv = TestFixtures.envelope(
                TestFixtures.region(-1, -1), 0L, 1L, TestFixtures.place(new NBlockPos(-5, 64, -5), 1));
        var outsideEnv = TestFixtures.envelope(
                TestFixtures.region(-1, -1), 0L, 2L, TestFixtures.place(new NBlockPos(5, 64, 5), 1));

        assertThat(BorderClassifier.isCrossRegion(insideEnv)).isFalse();
        assertThat(BorderClassifier.isCrossRegion(outsideEnv)).isTrue();
    }
}
