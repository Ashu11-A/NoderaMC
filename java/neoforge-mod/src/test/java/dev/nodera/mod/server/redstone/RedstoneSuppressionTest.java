package dev.nodera.mod.server.redstone;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 13 increment 10 (L-26): the live suppression registry — in delegated redstone regions
 * the engine is THE scheduler, and vanilla scheduled ticks are cancelled at the source. The
 * registry is Minecraft-type-free so these semantics pin headless.
 */
final class RedstoneSuppressionTest {

    @AfterEach
    void reset() {
        RedstoneSuppression.reset();
    }

    @Test
    void suppressionCoversExactlyTheActivatedRegionsBlocks() {
        RedstoneSuppression.activate(0, 0); // blocks x,z in [0,128)
        assertThat(RedstoneSuppression.shouldSuppress(0, 0)).isTrue();
        assertThat(RedstoneSuppression.shouldSuppress(127, 127)).isTrue();
        assertThat(RedstoneSuppression.shouldSuppress(128, 0)).isFalse();
        assertThat(RedstoneSuppression.shouldSuppress(0, 128)).isFalse();
        assertThat(RedstoneSuppression.shouldSuppress(-1, 0)).isFalse();
    }

    @Test
    void negativeRegionCoordinatesResolveWithFloorSemantics() {
        RedstoneSuppression.activate(-1, -1); // blocks x,z in [-128,0)
        assertThat(RedstoneSuppression.shouldSuppress(-1, -1)).isTrue();
        assertThat(RedstoneSuppression.shouldSuppress(-128, -128)).isTrue();
        assertThat(RedstoneSuppression.shouldSuppress(0, 0)).isFalse();
        assertThat(RedstoneSuppression.shouldSuppress(-129, -1)).isFalse();
    }

    @Test
    void deactivationRestoresVanillaScheduling() {
        RedstoneSuppression.activate(2, 3);
        assertThat(RedstoneSuppression.shouldSuppress(2 * 128 + 5, 3 * 128 + 5)).isTrue();
        RedstoneSuppression.deactivate(2, 3);
        assertThat(RedstoneSuppression.shouldSuppress(2 * 128 + 5, 3 * 128 + 5)).isFalse();
        assertThat(RedstoneSuppression.activeRegions()).isZero();
    }

    @Test
    void suppressedTicksAreCountedForTheMetricsLane() {
        assertThat(RedstoneSuppression.suppressedCount()).isZero();
        RedstoneSuppression.recordSuppressed();
        RedstoneSuppression.recordSuppressed();
        assertThat(RedstoneSuppression.suppressedCount()).isEqualTo(2);
    }

    @Test
    void emptyRegistryFastPathNeverSuppresses() {
        assertThat(RedstoneSuppression.shouldSuppress(0, 0)).isFalse();
        assertThat(RedstoneSuppression.shouldSuppress(1 << 20, -(1 << 20))).isFalse();
    }
}
