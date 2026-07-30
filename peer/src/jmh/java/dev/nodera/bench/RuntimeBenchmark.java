package dev.nodera.bench;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.diagnostics.DiagnosticsCollector;
import dev.nodera.diagnostics.metric.Direction;
import dev.nodera.diagnostics.metric.MessageCounters;
import dev.nodera.diagnostics.metric.TickSkewMeter;
import dev.nodera.diagnostics.metric.TpsMeter;
import dev.nodera.diagnostics.metric.TrafficMeter;
import dev.nodera.diagnostics.model.TelemetrySnapshot;
import dev.nodera.peer.TickSync;
import dev.nodera.protocol.membership.RegionProgress;
import dev.nodera.protocol.membership.SessionKeepAlive;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lane 4 — <b>internal runtime latency</b>: the work the always-on worker does per second while
 * nothing is wrong.
 *
 * <h2>Why idle cost is worth benchmarking</h2>
 *
 * <p>The worker runs beside a Minecraft client on a player's machine, forever. Anything it spends
 * per keep-alive, per sample, per metric is spent by every node in the network on every tick of
 * every session — and unlike the discovery and sync lanes, nobody is waiting on it, so it never
 * shows up as "slow". It shows up as the game stuttering.
 *
 * <p>The three measured paths are the ones on that per-second loop: keep-alive ingestion (once per
 * peer per second, under {@code TickSync}'s monitor), metric recording (once per message, both
 * directions), and the diagnostics sample the {@code NODERA-STATE} verb and the mod's HUD both read
 * — the one that walks every registered source.
 *
 * <p>Thread-context: single-threaded JMH state; {@code TickSync}'s own methods are synchronized,
 * so the number here is the uncontended cost — the floor, not the worst case.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@State(Scope.Benchmark)
public class RuntimeBenchmark {

    private static final int REGIONS = 32;

    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private TickSync tickSync;
    private List<SessionKeepAlive> keepAlives;
    private int keepAliveCursor;

    private TrafficMeter traffic;
    private MessageCounters counters;
    private DiagnosticsCollector diagnostics;
    private NodeId self;

    @Setup(Level.Trial)
    public void setUp() {
        self = BenchFixtures.node(1);
        tickSync = new TickSync(self, new TickSkewMeter(2_000), new TpsMeter(2_000),
                () -> clock.addAndGet(1_000_000L));

        // Four peers, each reporting progress on every region: the shape of one gossip second in a
        // small committee-backed world.
        keepAlives = new ArrayList<>();
        for (int peer = 2; peer <= 5; peer++) {
            List<RegionProgress> progress = new ArrayList<>(REGIONS);
            for (int i = 0; i < REGIONS; i++) {
                progress.add(new RegionProgress(
                        BenchFixtures.region(i % 8, i / 8),
                        new RegionEpoch(3L),
                        BenchFixtures.node(peer),
                        20_000L + i));
            }
            keepAlives.add(new SessionKeepAlive(BenchFixtures.node(peer), peer, progress));
        }

        traffic = new TrafficMeter();
        counters = new MessageCounters();
        diagnostics = new DiagnosticsCollector(traffic, counters);
    }

    /**
     * One peer's keep-alive, ingested. Every region in it updates a high-water mark and feeds the
     * skew meter — the per-peer, per-second cost of knowing how far behind everyone is.
     */
    @Benchmark
    public void ingestKeepAlive() {
        tickSync.onKeepAlive(keepAlives.get(keepAliveCursor++ % keepAlives.size()));
    }

    /** The progress list this node publishes back — rebuilt on every keep-alive it sends. */
    @Benchmark
    public List<RegionProgress> publishLocalProgress() {
        return tickSync.localProgress();
    }

    /** Per-message metering, both directions. Multiplied by every frame the node sees. */
    @Benchmark
    public void meterMessage(Blackhole hole) {
        traffic.record(Direction.TX, 1_024);
        traffic.record(Direction.RX, 24 * 1_024);
        counters.recordTx("SESSION_KEEP_ALIVE");
        counters.recordRx("CONTENT_CHUNK");
        hole.consume(counters);
    }

    /**
     * The diagnostics sample behind {@code NODERA-STATE}, the HUD, and the companion dashboard.
     * The app polls it; the mod draws it every frame it is open — so it is on a UI path, and a
     * regression here is visible as lag rather than as a number.
     */
    @Benchmark
    public TelemetrySnapshot sampleDiagnostics() {
        return diagnostics.sample(1_000L, clock.addAndGet(1_000_000L), self, false);
    }
}
