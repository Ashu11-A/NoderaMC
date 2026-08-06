package dev.nodera.peer.metric;

import dev.nodera.core.identity.NodeId;
import dev.nodera.diagnostics.metric.Direction;
import dev.nodera.diagnostics.metric.TrafficMeter;
import dev.nodera.transport.PeerAddress;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Per-peer byte counters and live transfer rates — the "↑ / ↓ per peer" columns a torrent client
 * shows, and the data behind the companion app's Peers tab.
 *
 * <p>{@link TrafficMeter} answers "how much has this node moved in total"; this answers "with
 * whom, how fast, right now". It is fed by {@link MeteredPeerTransport} at
 * exactly the same two choke points (every {@code send}, every delivered inbound frame), so the
 * per-peer totals sum to the node totals for all traffic that carries a peer address.
 *
 * <h2>Rates are computed by the reader, not by a timer</h2>
 *
 * <p>There is no scheduler here. {@link #snapshot()} re-derives each peer's bytes/second from the
 * delta since that peer's previous sample whenever at least {@link #MIN_SAMPLE_WINDOW_NANOS} has
 * elapsed; a faster poll returns the last computed rate rather than a noisy divide-by-almost-zero.
 * The dashboard (2 s) and the mod (3 s) therefore both get honest numbers with no background
 * thread to own, start, or stop.
 *
 * <h2>Bounded by the writer, not by the reader</h2>
 *
 * <p>A peer that has been silent for {@link #IDLE_EVICT_NANOS} is dropped, so a long-lived node that
 * has met thousands of peers does not accumulate them forever. Explicit {@link #forget} on peer-down
 * is the fast path; the idle sweep is the backstop.
 *
 * <p>That sweep runs from {@link #recordTx}/{@link #recordRx}, on the same path that <em>creates</em>
 * entries and at most once per {@link #SWEEP_INTERVAL_NANOS} — so the table cannot grow without the
 * lid also being lifted. It used to run only from {@link #snapshot()}, which had no production caller
 * at all (issue #218): the only eviction path in the class was reachable exclusively from a readout
 * nobody read, so a worker running for weeks with no companion app attached accumulated one entry per
 * peer it had ever exchanged a frame with, forever. A meter must not leak when nothing is looking at
 * it.
 *
 * <p>Thread-context: safe from any thread. Recording is lock-free ({@link LongAdder} per
 * direction); {@link #snapshot()} mutates only the sampling window fields under the entry's own
 * monitor, so concurrent readers cannot observe a torn rate.
 */
public final class PeerTrafficMeter {

    /** Shortest interval over which a rate is re-derived; faster polls reuse the last value. */
    public static final long MIN_SAMPLE_WINDOW_NANOS = 500_000_000L;

    /** A peer with no traffic for this long is dropped from the table. */
    public static final long IDLE_EVICT_NANOS = 10L * 60 * 1_000_000_000L;

    /**
     * Shortest interval between idle sweeps. A full scan per recorded frame would put an O(peers)
     * walk on the hot send path; once a minute against a ten-minute retention keeps the table within
     * one sweep of its bound while costing one comparison on all but a handful of calls.
     */
    public static final long SWEEP_INTERVAL_NANOS = 60L * 1_000_000_000L;

    /** Nanosecond clock; injectable so rate derivation is testable without sleeping. */
    private final LongSupplier clock;

    private final Map<String, PeerCounters> peers = new ConcurrentHashMap<>();

    /** When the next idle sweep is due. Claimed by CAS so exactly one recorder runs each sweep. */
    private final java.util.concurrent.atomic.AtomicLong nextSweepNanos;

    /** Production meter reading {@link System#nanoTime()}. */
    public PeerTrafficMeter() {
        this(System::nanoTime);
    }

    /**
     * Test/deterministic constructor.
     *
     * @param clock a monotonic nanosecond source.
     */
    public PeerTrafficMeter(LongSupplier clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        // Seeded from the clock rather than from zero: `System.nanoTime()` has an arbitrary origin
        // and is routinely negative, so a fixed seed would either sweep on every call or on none.
        this.nextSweepNanos =
                new java.util.concurrent.atomic.AtomicLong(clock.getAsLong() + SWEEP_INTERVAL_NANOS);
    }

    /**
     * Record {@code byteCount} sent to {@code to}.
     *
     * @param to        the destination; ignored when it identifies no peer.
     * @param byteCount the frame's byte length.
     * @Thread-context any thread.
     */
    public void recordTx(PeerAddress to, int byteCount) {
        record(to, Direction.TX, byteCount);
    }

    /**
     * Record {@code byteCount} received from {@code from}.
     *
     * @param from      the source; ignored when it identifies no peer.
     * @param byteCount the frame's byte length.
     * @Thread-context any thread.
     */
    public void recordRx(PeerAddress from, int byteCount) {
        record(from, Direction.RX, byteCount);
    }

    private void record(PeerAddress peer, Direction direction, int byteCount) {
        String key = keyOf(peer);
        if (key == null || byteCount <= 0) {
            return;
        }
        long now = clock.getAsLong();
        PeerCounters counters = peers.computeIfAbsent(key,
                k -> new PeerCounters(peer.nodeId(), nullToEmpty(peer.route()), now));
        counters.observe(peer, direction, byteCount, now);
        evictIdle(now);
    }

    /**
     * Drop every peer silent for longer than {@link #IDLE_EVICT_NANOS}, at most once per
     * {@link #SWEEP_INTERVAL_NANOS}.
     *
     * <p>The due time is claimed with a compare-and-set before the walk, so concurrent recorders
     * cannot all sweep at once: the loser simply returns and the table is swept exactly once per
     * interval however many threads are sending. Entries are removed by key and value, so a peer that
     * spoke between the staleness test and the removal keeps its counters.
     */
    private void evictIdle(long now) {
        long due = nextSweepNanos.get();
        if (now - due < 0 || !nextSweepNanos.compareAndSet(due, now + SWEEP_INTERVAL_NANOS)) {
            return;
        }
        for (Map.Entry<String, PeerCounters> entry : peers.entrySet()) {
            if (now - entry.getValue().lastActivityNanos() > IDLE_EVICT_NANOS) {
                peers.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Drop a peer's counters (called when the transport reports it down, so a reconnecting peer
     * starts from zero rather than inheriting a stale rate).
     *
     * @param peer the peer that went away.
     * @Thread-context any thread.
     */
    public void forget(PeerAddress peer) {
        String key = keyOf(peer);
        if (key != null) {
            peers.remove(key);
        }
    }

    /**
     * The live per-peer table, ordered by combined throughput (busiest first) so a UI can render
     * the top rows without re-sorting. Re-derives rates where the sample window has elapsed.
     *
     * <p>Rows staler than {@link #IDLE_EVICT_NANOS} are omitted. Reading no longer <i>performs</i>
     * the eviction — {@link #recordTx}/{@link #recordRx} do that on their own cadence — because the
     * table must be bounded whether or not anything ever calls this.
     *
     * @return one row per known peer.
     * @Thread-context any thread.
     */
    public List<PeerTraffic> snapshot() {
        long now = clock.getAsLong();
        List<PeerTraffic> out = new ArrayList<>(peers.size());
        for (Map.Entry<String, PeerCounters> entry : peers.entrySet()) {
            PeerCounters counters = entry.getValue();
            if (now - counters.lastActivityNanos() > IDLE_EVICT_NANOS) {
                continue;
            }
            out.add(counters.sample(entry.getKey(), now));
        }
        out.sort(Comparator
                .comparingLong((PeerTraffic p) -> p.txBytesPerSec() + p.rxBytesPerSec())
                .reversed()
                .thenComparing(PeerTraffic::peerKey));
        return List.copyOf(out);
    }

    /**
     * The live table indexed by node id, for a caller that has a list of peers and wants each one's
     * row. Peers known only by route are not in it — they have no id to look up by.
     *
     * <p>One {@link #snapshot()} rather than a lookup per peer: sampling re-derives a rate from the
     * time since that peer's <i>previous</i> sample, so two entry points into the sampling code meant
     * a peer's window could be reset by whichever of them happened to run first.
     *
     * @return the rows that carry a node id, by that id.
     * @Thread-context any thread.
     */
    public Map<NodeId, PeerTraffic> byNode() {
        Map<NodeId, PeerTraffic> out = new java.util.LinkedHashMap<>();
        for (PeerTraffic row : snapshot()) {
            if (row.nodeId() != null) {
                out.put(row.nodeId(), row);
            }
        }
        return out;
    }

    /**
     * The table key for an address: the node id when known, else the transport route. Returns
     * {@code null} for an address that identifies neither (nothing to attribute the bytes to).
     */
    private static String keyOf(PeerAddress peer) {
        if (peer == null) {
            return null;
        }
        if (peer.nodeId() != null) {
            return peer.nodeId().value().toString();
        }
        String route = peer.route();
        return route == null || route.isBlank() ? null : route;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * One peer's traffic as of the last sample.
     *
     * @param peerKey       the table key (node id when known, else the route).
     * @param nodeId        the peer's id, or {@code null} when only a route is known.
     * @param route         the last route bytes crossed on ({@code "host:port"}), or empty.
     * @param totalTxBytes  cumulative bytes sent to this peer.
     * @param totalRxBytes  cumulative bytes received from this peer.
     * @param txBytesPerSec upload rate over the last sample window.
     * @param rxBytesPerSec download rate over the last sample window.
     */
    public record PeerTraffic(String peerKey, NodeId nodeId, String route,
                              long totalTxBytes, long totalRxBytes,
                              long txBytesPerSec, long rxBytesPerSec) {
    }

    /** Mutable per-peer state: lock-free totals plus a monitor-guarded sampling window. */
    private static final class PeerCounters {
        private final LongAdder tx = new LongAdder();
        private final LongAdder rx = new LongAdder();
        private volatile NodeId nodeId;
        private volatile String route;
        private volatile long lastActivityNanos;

        // Guarded by `this`.
        private long windowNanos;
        private long windowTx;
        private long windowRx;
        private long txRate;
        private long rxRate;

        PeerCounters(NodeId nodeId, String route, long nowNanos) {
            this.nodeId = nodeId;
            this.route = route;
            this.lastActivityNanos = nowNanos;
            this.windowNanos = nowNanos;
        }

        void observe(PeerAddress peer, Direction direction, int byteCount, long nowNanos) {
            // A peer first seen by route later gains an id (the membership reply names it), and a
            // reconnect can change its route. Keep the newest of each so the UI shows where the
            // bytes are actually crossing.
            if (peer.nodeId() != null) {
                this.nodeId = peer.nodeId();
            }
            if (peer.route() != null && !peer.route().isBlank()) {
                this.route = peer.route();
            }
            if (direction == Direction.TX) {
                tx.add(byteCount);
            } else {
                rx.add(byteCount);
            }
            this.lastActivityNanos = nowNanos;
        }

        long lastActivityNanos() {
            return lastActivityNanos;
        }

        PeerTraffic sample(String key, long nowNanos) {
            long totalTx = tx.sum();
            long totalRx = rx.sum();
            long upRate;
            long downRate;
            synchronized (this) {
                long elapsed = nowNanos - windowNanos;
                if (elapsed >= MIN_SAMPLE_WINDOW_NANOS) {
                    // Integer-only rate: bytes moved in the window, scaled to one second. The
                    // deltas are bounded by the window, so the multiply cannot overflow in
                    // practice (it would take ~9 GB/s sustained).
                    txRate = Math.max(0, (totalTx - windowTx)) * 1_000_000_000L / elapsed;
                    rxRate = Math.max(0, (totalRx - windowRx)) * 1_000_000_000L / elapsed;
                    windowNanos = nowNanos;
                    windowTx = totalTx;
                    windowRx = totalRx;
                }
                upRate = txRate;
                downRate = rxRate;
            }
            return new PeerTraffic(key, nodeId, route, totalTx, totalRx, upRate, downRate);
        }
    }
}
