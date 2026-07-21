package dev.nodera.diagnostics.metric;

import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded, allocation-free byte + frame counters for one peer's traffic, per direction (Task 18).
 *
 * <p>Four {@link LongAdder}s — {@code bytesTx}/{@code bytesRx}/{@code framesTx}/{@code framesRx} —
 * incremented by {@code MeteredPeerTransport} on every send (TX) and every delivered inbound frame
 * (RX). Cumulative (monotonic); {@link dev.nodera.diagnostics.DiagnosticsCollector} derives rolling
 * rates by differencing successive {@link #snapshot()} reads.
 *
 * <p>Thread-context: safe from any thread (all counters are {@link LongAdder}); reads via the
 * accessors are weakly consistent.
 */
public final class TrafficMeter {

    private final LongAdder bytesTx = new LongAdder();
    private final LongAdder bytesRx = new LongAdder();
    private final LongAdder framesTx = new LongAdder();
    private final LongAdder framesRx = new LongAdder();

    /**
     * Record one outbound frame of {@code byteCount} payload bytes (TX).
     *
     * @param byteCount the frame's byte length (never negative).
     * @Thread-context any thread.
     */
    public void recordTx(int byteCount) {
        bytesTx.add(byteCount);
        framesTx.increment();
    }

    /**
     * Record one inbound frame of {@code byteCount} payload bytes (RX).
     *
     * @param byteCount the frame's byte length (never negative).
     * @Thread-context any thread.
     */
    public void recordRx(int byteCount) {
        bytesRx.add(byteCount);
        framesRx.increment();
    }

    /**
     * Record one frame of {@code byteCount} payload bytes in {@code direction} — the
     * {@link Direction}-keyed form of {@link #recordTx}/{@link #recordRx}.
     *
     * @Thread-context any thread.
     */
    public void record(Direction direction, int byteCount) {
        switch (direction) {
            case TX -> recordTx(byteCount);
            case RX -> recordRx(byteCount);
        }
    }

    /** @return cumulative bytes in {@code direction}. @Thread-context any thread. */
    public long bytes(Direction direction) {
        return direction == Direction.TX ? bytesTx() : bytesRx();
    }

    /** @return cumulative frame count in {@code direction}. @Thread-context any thread. */
    public long frames(Direction direction) {
        return direction == Direction.TX ? framesTx() : framesRx();
    }

    /** @return cumulative TX bytes. @Thread-context any thread. */
    public long bytesTx() {
        return bytesTx.sum();
    }

    /** @return cumulative RX bytes. @Thread-context any thread. */
    public long bytesRx() {
        return bytesRx.sum();
    }

    /** @return cumulative TX frame count. @Thread-context any thread. */
    public long framesTx() {
        return framesTx.sum();
    }

    /** @return cumulative RX frame count. @Thread-context any thread. */
    public long framesRx() {
        return framesRx.sum();
    }

    /**
     * A consistent point-in-time read of all four counters (each {@code sum()} is atomic; the four
     * are not mutually consistent across concurrent writers, which is acceptable for telemetry).
     *
     * @return the snapshot.
     * @Thread-context any thread.
     */
    public Snapshot snapshot() {
        return new Snapshot(bytesTx.sum(), bytesRx.sum(), framesTx.sum(), framesRx.sum());
    }

    /** Immutable point-in-time read of the four cumulative counters. */
    public record Snapshot(long bytesTx, long bytesRx, long framesTx, long framesRx) {}
}
