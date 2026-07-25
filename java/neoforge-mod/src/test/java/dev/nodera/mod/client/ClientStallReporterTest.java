package dev.nodera.mod.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cadence contract of the stall reporter.
 *
 * <p>The Minecraft-touching half (reading {@code client.screen}) cannot run headlessly, so what is
 * pinned here is the part that decides whether a normal session pays anything for this: the first
 * report only after ten seconds outside a world, repeats only every thirty. A reporter that logged
 * per tick would drown the very logs it exists to make readable.
 */
final class ClientStallReporterTest {

    @Test
    void aClientThatReachesAWorldQuicklyNeverReports() {
        // 200 ticks is 10 s at 20 TPS: a normal join is far inside that, so the common path logs
        // nothing at all.
        assertThat(ClientStallReporter.FIRST_REPORT_TICKS).isEqualTo(200);
        assertThat(ClientStallReporter.FIRST_REPORT_TICKS / 20).isEqualTo(10);
    }

    @Test
    void repeatsAreMinutesNotSeconds() {
        assertThat(ClientStallReporter.REPEAT_TICKS)
                .as("a per-tick reporter would drown the logs it exists to make readable")
                .isGreaterThanOrEqualTo(ClientStallReporter.FIRST_REPORT_TICKS);
        assertThat(ClientStallReporter.REPEAT_TICKS / 20).isEqualTo(30);
    }
}
