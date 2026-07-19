package dev.nodera.coordinator.interference;

import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InterferenceStats} rolling rates: tick-driven (no wall clock), window expiry, per-source
 * lifetime totals for the diagnostics command.
 */
class InterferenceStatsTest {

    private final RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);

    @Test
    void countsEventsInsideTheWindow() {
        InterferenceStats stats = new InterferenceStats(10);
        stats.record(region, MutationSource.UNKNOWN);
        stats.record(region, MutationSource.ENTITY);
        stats.advanceTick();
        stats.record(region, MutationSource.ENTITY);
        assertThat(stats.ratePerWindow(region)).isEqualTo(3);
    }

    @Test
    void eventsExpireOnceTheWindowPasses() {
        InterferenceStats stats = new InterferenceStats(10);
        stats.record(region, MutationSource.UNKNOWN);
        for (int i = 0; i < 9; i++) {
            stats.advanceTick();
        }
        assertThat(stats.ratePerWindow(region)).isEqualTo(1); // tick 0 event, window [.. 9]
        stats.advanceTick();
        assertThat(stats.ratePerWindow(region)).isZero();
    }

    @Test
    void perSourceTotalsAreLifetime() {
        InterferenceStats stats = new InterferenceStats(2);
        stats.record(region, MutationSource.SCHEDULED);
        stats.record(region, MutationSource.SCHEDULED);
        stats.record(region, MutationSource.NEIGHBOR);
        for (int i = 0; i < 5; i++) {
            stats.advanceTick();
        }
        assertThat(stats.ratePerWindow(region)).isZero();
        assertThat(stats.totalFor(region, MutationSource.SCHEDULED)).isEqualTo(2);
        assertThat(stats.totalFor(region, MutationSource.NEIGHBOR)).isEqualTo(1);
    }
}
