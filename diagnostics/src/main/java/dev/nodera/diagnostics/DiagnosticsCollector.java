package dev.nodera.diagnostics;

import dev.nodera.core.identity.NodeId;
import dev.nodera.diagnostics.metric.MessageCounters;
import dev.nodera.diagnostics.metric.RateWindow;
import dev.nodera.diagnostics.metric.TrafficMeter;
import dev.nodera.diagnostics.model.HealthStat;
import dev.nodera.diagnostics.model.NetStats;
import dev.nodera.diagnostics.model.SessionInfo;
import dev.nodera.diagnostics.model.TelemetrySnapshot;
import dev.nodera.diagnostics.source.DiagnosticsSource;
import dev.nodera.diagnostics.source.SnapshotBuilder;
import dev.nodera.diagnostics.state.Health;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Aggregates the per-source capture into one immutable {@link TelemetrySnapshot} per sample tick
 * (Task 18).
 *
 * <p>Owns the {@link TrafficMeter} + {@link MessageCounters} (filled by
 * {@code MeteredPeerTransport} and {@code PeerRuntime}) and the two {@link RateWindow}s (one per
 * direction). On each {@link #sample} it: asks every registered {@link DiagnosticsSource} to
 * contribute session/regions/entities; reads the cumulative counters, differences them against the
 * last sample, feeds the deltas to the rate windows; derives session {@link Health}; and freezes a
 * {@link TelemetrySnapshot}.
 *
 * <p>Thread-context: {@link #sample} is driven from the single server-tick thread. The meter and
 * counters are concurrently written by the transport/runtime threads ({@link java.util.concurrent.atomic.LongAdder}).
 */
public final class DiagnosticsCollector {

    /** Default rate-window capacity (samples) — at a 1 s cadence this is a 20 s window. */
    public static final int DEFAULT_RATE_SAMPLES = 20;
    /** Default rate-window span (nanos) passed to the {@link RateWindow} rate queries. */
    public static final long DEFAULT_RATE_WINDOW_NANOS = 20_000_000_000L; // 20 s

    private final TrafficMeter meter;
    private final MessageCounters counters;
    private final RateWindow txWindow;
    private final RateWindow rxWindow;
    private final long rateWindowNanos;
    private final List<DiagnosticsSource> sources = new CopyOnWriteArrayList<>();

    private TrafficMeter.Snapshot lastMeter = new TrafficMeter.Snapshot(0, 0, 0, 0);
    private volatile TelemetrySnapshot latest;

    /**
     * Full constructor.
     *
     * @param meter           the shared TX/RX byte+frame meter.
     * @param counters        the shared per-type frame counters.
     * @param rateSamples     rate-window capacity (samples).
     * @param rateWindowNanos rate-window span (nanos) for the rate queries.
     */
    public DiagnosticsCollector(TrafficMeter meter, MessageCounters counters,
                                int rateSamples, long rateWindowNanos) {
        this.meter = meter;
        this.counters = counters;
        this.txWindow = new RateWindow(rateSamples);
        this.rxWindow = new RateWindow(rateSamples);
        this.rateWindowNanos = rateWindowNanos;
    }

    /** Convenience constructor with default rate-window sizing. */
    public DiagnosticsCollector(TrafficMeter meter, MessageCounters counters) {
        this(meter, counters, DEFAULT_RATE_SAMPLES, DEFAULT_RATE_WINDOW_NANOS);
    }

    /** @return the shared traffic meter (for {@code MeteredPeerTransport}). */
    public TrafficMeter trafficMeter() {
        return meter;
    }

    /** @return the shared per-type counters (for {@code PeerRuntime}). */
    public MessageCounters messageCounters() {
        return counters;
    }

    /** Register a capture source (session, regions, entities). */
    public DiagnosticsCollector register(DiagnosticsSource source) {
        if (source != null) {
            sources.add(source);
        }
        return this;
    }

    /** @return the most recent snapshot, or {@code null} before the first sample. */
    public TelemetrySnapshot latest() {
        return latest;
    }

    /**
     * Take one sample.
     *
     * @param tick      the server tick.
     * @param nowNanos  the sample timestamp (nanos); supplied by the caller so the math is testable.
     * @param self      this peer's id.
     * @param bootstrap {@code true} if this peer is the bootstrap peer.
     * @return the immutable snapshot.
     */
    public TelemetrySnapshot sample(long tick, long nowNanos, NodeId self, boolean bootstrap) {
        SnapshotBuilder b = new SnapshotBuilder();
        b.tick(tick);
        b.self(self);
        b.bootstrap(bootstrap);
        for (DiagnosticsSource src : sources) {
            src.contribute(b);
        }

        TrafficMeter.Snapshot cur = meter.snapshot();
        long dTxBytes = Math.max(0L, cur.bytesTx() - lastMeter.bytesTx());
        long dTxFrames = Math.max(0L, cur.framesTx() - lastMeter.framesTx());
        long dRxBytes = Math.max(0L, cur.bytesRx() - lastMeter.bytesRx());
        long dRxFrames = Math.max(0L, cur.framesRx() - lastMeter.framesRx());
        if (lastMeter.bytesTx() != 0 || lastMeter.bytesRx() != 0
                || lastMeter.framesTx() != 0 || lastMeter.framesRx() != 0
                || cur.bytesTx() != 0 || cur.bytesRx() != 0
                || cur.framesTx() != 0 || cur.framesRx() != 0) {
            txWindow.record(nowNanos, dTxBytes, dTxFrames);
            rxWindow.record(nowNanos, dRxBytes, dRxFrames);
        }
        lastMeter = cur;

        Map<String, long[]> byType = counters.snapshot();
        NetStats net = new NetStats(
                cur.bytesTx(), cur.bytesRx(), cur.framesTx(), cur.framesRx(),
                txWindow.bytesPerSec(nowNanos, rateWindowNanos),
                rxWindow.bytesPerSec(nowNanos, rateWindowNanos),
                txWindow.framesPerSec(nowNanos, rateWindowNanos),
                rxWindow.framesPerSec(nowNanos, rateWindowNanos),
                byType);
        b.net(net);

        b.health(deriveHealth(b.session()));
        TelemetrySnapshot snap = b.build();
        latest = snap;
        return snap;
    }

    /** Derive coarse session health from the session view (quorum-aware for the 3-peer beta). */
    static HealthStat deriveHealth(SessionInfo ses) {
        if (ses == null) {
            return new HealthStat(Health.CRITICAL, "no session");
        }
        if (ses.gatewayId() == null) {
            return new HealthStat(Health.CRITICAL, "no gateway");
        }
        int n = ses.memberCount();
        if (n < 2) {
            return new HealthStat(Health.CRITICAL, "isolated");
        }
        if (n < 3) {
            return new HealthStat(Health.DEGRADED, "below quorum (3)");
        }
        return HealthStat.healthy();
    }
}
