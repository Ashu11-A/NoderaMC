package dev.nodera.simulation;

import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.state.SnapshotVersion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class RegionExecutionContextTest {

    @Test
    void inclusiveSingleTickRangeIsValid() {
        RegionExecutionContext context = new RegionExecutionContext(
                TestFixtures.region(0, 0), RegionEpoch.INITIAL, SnapshotVersion.INITIAL,
                5, 5, 1, 2, 3);
        assertThat(context.tickFrom()).isEqualTo(context.tickTo());
    }

    @Test
    void reversedTickRangeIsRejectedBeforeEngineLoop() {
        assertThatThrownBy(() -> new RegionExecutionContext(
                TestFixtures.region(0, 0), RegionEpoch.INITIAL, SnapshotVersion.INITIAL,
                6, 5, 1, 2, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tickTo must be >= tickFrom");
    }

    @Test
    void nullDeterminismKeysAreRejected() {
        assertThatThrownBy(() -> new RegionExecutionContext(
                null, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 0, 0, 1, 2, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegionExecutionContext(
                TestFixtures.region(0, 0), null, SnapshotVersion.INITIAL, 0, 0, 1, 2, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegionExecutionContext(
                TestFixtures.region(0, 0), RegionEpoch.INITIAL, null, 0, 0, 1, 2, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void committedWorldTimeIsCarriedAndDefaultsToZero() {
        // L-6: world-time enters the validated context as a committed, member-agreed input —
        // never a local clock. The compat constructor pins 0 for time-free rule sets.
        RegionExecutionContext legacy = new RegionExecutionContext(
                TestFixtures.region(0, 0), RegionEpoch.INITIAL, SnapshotVersion.INITIAL,
                0L, 1L, 42L, 1, 99L);
        RegionExecutionContext timed = new RegionExecutionContext(
                TestFixtures.region(0, 0), RegionEpoch.INITIAL, SnapshotVersion.INITIAL,
                0L, 1L, 42L, 1, 99L, 6_000L);
        org.assertj.core.api.Assertions.assertThat(legacy.committedWorldTime()).isZero();
        org.assertj.core.api.Assertions.assertThat(timed.committedWorldTime()).isEqualTo(6_000L);
    }
}
