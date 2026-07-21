package dev.nodera.diagnostics.metric;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A fixed-capacity sliding window of timestamped traffic samples → bytes/sec + frames/sec (Task 18).
 *
 * <p>{@link dev.nodera.diagnostics.DiagnosticsCollector} feeds it the per-sample byte/frame deltas
 * (derived from {@link TrafficMeter}'s cumulative counters) together with a caller-supplied
 * {@code nowNanos}; {@link #bytesPerSec(long, long)} / {@link #framesPerSec(long, long)} sum the
 * samples that fall inside the requested window and divide by the elapsed span.
 *
 * <p>Pure with respect to time: it never reads a wall clock itself (the caller injects
 * {@code nowNanos}), so the rate math is unit-testable with synthetic clocks. Not used inside
 * {@code simulation} — determinism rules there do not apply.
 *
 * <p>Thread-context: <b>not</b> thread-safe; intended to be driven from the single collector sample
 * thread.
 */
public final class RateWindow {

    /** One timestamped sample. */
    private static final class Sample {
        final long nanos;
        final long bytes;
        final long frames;

        Sample(long nanos, long bytes, long frames) {
            this.nanos = nanos;
            this.bytes = bytes;
            this.frames = frames;
        }
    }

    private final int capacity;
    private final Deque<Sample> samples = new ArrayDeque<>();

    /** @param capacity max samples retained (the window length in samples). */
    public RateWindow(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
    }

    /**
     * Add a sample.
     *
     * @param nowNanos the sample timestamp (nanos), supplied by the caller.
     * @param bytes    bytes transferred in this sample (≥ 0).
     * @param frames   frames transferred in this sample (≥ 0).
     * @Thread-context collector sample thread.
     */
    public void record(long nowNanos, long bytes, long frames) {
        samples.addLast(new Sample(nowNanos, Math.max(0L, bytes), Math.max(0L, frames)));
        while (samples.size() > capacity) {
            samples.removeFirst();
        }
    }

    /** @return the number of samples currently retained. */
    public int size() {
        return samples.size();
    }

    /**
     * Bytes-per-second over the samples within {@code windowNanos} of {@code nowNanos}.
     *
     * <p>The denominator is {@code max(nowNanos - oldestInWindowNanos, 1ns)} converted to seconds,
     * so a window with a single sample spanning one sample period yields a finite (not infinite)
     * rate.
     *
     * @param nowNanos    the current timestamp (nanos).
     * @param windowNanos the window length (nanos); samples older than {@code nowNanos - windowNanos}
     *                    are excluded.
     * @return bytes per second, ≥ 0.
     * @Thread-context collector sample thread.
     */
    public double bytesPerSec(long nowNanos, long windowNanos) {
        return perSec(nowNanos, windowNanos, true);
    }

    /** Frames-per-second over the window — see {@link #bytesPerSec(long, long)}. */
    public double framesPerSec(long nowNanos, long windowNanos) {
        return perSec(nowNanos, windowNanos, false);
    }

    private double perSec(long nowNanos, long windowNanos, boolean bytes) {
        if (samples.isEmpty() || windowNanos <= 0L) {
            return 0.0;
        }
        long cutoff = nowNanos - windowNanos;
        long total = 0L;
        long oldest = Long.MAX_VALUE;
        boolean any = false;
        for (Sample s : samples) {
            if (s.nanos < cutoff) {
                continue;
            }
            total += bytes ? s.bytes : s.frames;
            if (s.nanos < oldest) {
                oldest = s.nanos;
            }
            any = true;
        }
        if (!any) {
            return 0.0;
        }
        // Floor the denominator to one nominal slot (windowNanos / capacity) so a lone sample —
        // whose delta spans exactly one (unknown) sample period — yields a finite, sane rate
        // instead of dividing by ~0 ns. Assumes roughly uniform sampling at that cadence.
        long minElapsed = Math.max(1L, windowNanos / capacity);
        long elapsed = Math.max(nowNanos - oldest, minElapsed);
        return total * 1_000_000_000.0 / elapsed;
    }
}
