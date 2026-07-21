package dev.nodera.diagnostics.metric;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RateWindow} sliding-window rate math with a synthetic clock (Task 18 acceptance #1).
 */
final class RateWindowTest {

    private static final long SEC = 1_000_000_000L;

    @Test
    void rejectsZeroCapacity() {
        assertThatThrownBy(() -> new RateWindow(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyWindowReportsZero() {
        RateWindow w = new RateWindow(5);
        assertThat(w.bytesPerSec(123L, SEC)).isZero();
        assertThat(w.framesPerSec(123L, SEC)).isZero();
    }

    @Test
    void averagesOverTheSamplesInWindow() {
        RateWindow w = new RateWindow(5);
        w.record(0L, 1000L, 1L);
        w.record(SEC, 2000L, 2L);
        // Both samples in a 2 s window; total 3000 B over 1 s elapsed (oldest=0, now=1s).
        assertThat(w.bytesPerSec(SEC, 2 * SEC)).isEqualTo(3000.0);
        assertThat(w.framesPerSec(SEC, 2 * SEC)).isEqualTo(3.0);
    }

    @Test
    void excludesSamplesOlderThanTheWindow() {
        RateWindow w = new RateWindow(5);
        w.record(0L, 1000L, 1L);
        w.record(10 * SEC, 5000L, 5L);
        // now=10s, window=2s → only the last sample counts; elapsed = 10s-10s = 0 → clamped to 1ns.
        double rate = w.bytesPerSec(10 * SEC, 2 * SEC);
        assertThat(rate).isPositive().isGreaterThanOrEqualTo(5000.0);
    }

    @Test
    void dropsSamplesBeyondCapacity() {
        RateWindow w = new RateWindow(2);
        w.record(0L, 1L, 1L);
        w.record(SEC, 2L, 1L);
        w.record(2 * SEC, 3L, 1L);
        assertThat(w.size()).isEqualTo(2);
    }
}
