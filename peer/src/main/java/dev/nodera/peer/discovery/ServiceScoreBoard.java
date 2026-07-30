package dev.nodera.peer.discovery;

import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.service.ServiceDirectoryEntry;
import dev.nodera.protocol.service.ServiceKind;
import dev.nodera.protocol.service.ServiceObservation;
import dev.nodera.protocol.service.ServiceScore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What this peer has measured about the infrastructure services it uses, and which ones it should
 * therefore be using.
 *
 * <h2>Why a peer scores locally at all</h2>
 *
 * <p>A tracker's aggregate answers "which rendezvous works for the network". This answers "which
 * rendezvous works <i>for me</i>", and the two genuinely differ: a relay with excellent global
 * availability may be a continent away, and the RTT that matters for a peer's own reservations is the
 * one that peer observes. So a service this peer has measured is scored on <b>its own</b> numbers, and
 * the tracker's aggregate is the fallback for services it has never probed — which is exactly the
 * position a peer joining the network is in.
 *
 * <p>The scoring function is {@link ServiceScore#composite(int, int, int, int)}, byte-identical to the
 * Rust one, so "the tracker's number" and "my number" are the same arithmetic over different evidence.
 * Nothing here consumes the transmitted {@code compositePermille}.
 *
 * <h2>Availability outranks latency, on purpose</h2>
 *
 * <p>The weights are 40/30/20/10 over availability, latency, capacity, freshness. Registration and
 * discovery are latency-tolerant (rendezvous {@code REFERENCE.md} §15), so a slow rendezvous that is
 * always up must beat a fast one that is usually down. The latency term reads p95, not the median: a
 * relay whose tail is bad is bad, and a median hides the stalls that leave a peer sitting on a path it
 * cannot use.
 *
 * <h2>What a window holds</h2>
 *
 * <p>Per service, the last {@link #WINDOW_SAMPLES} probe results — a bounded ring, so a long-running
 * worker's memory does not grow with uptime. Reports to trackers carry counters and percentiles over
 * that window and never a verdict, so a tracker aggregates evidence rather than trusting one peer's
 * judgement.
 *
 * <p>Thread-context: thread-safe. Probes arrive from a scheduler thread while selection runs on
 * whichever thread is composing a transport, so the sample rings are guarded individually.
 */
public final class ServiceScoreBoard {

    /** Probe results retained per service. About an hour at the worker's 15-second probe cadence. */
    public static final int WINDOW_SAMPLES = 240;

    /** Services a peer registers with at once, unless a caller asks for a different number. */
    public static final int DEFAULT_FANOUT = 3;

    /** One probe result. */
    private record Sample(boolean reachable, int rttMillis, long atEpochMillis) {}

    /** The bounded sample ring for one service, plus what kind it is. */
    private static final class Window {
        private final ServiceKind kind;
        private final Deque<Sample> samples = new ArrayDeque<>(WINDOW_SAMPLES);

        Window(ServiceKind kind) {
            this.kind = kind;
        }

        synchronized void add(Sample sample) {
            if (samples.size() == WINDOW_SAMPLES) {
                samples.removeFirst();
            }
            samples.addLast(sample);
        }

        synchronized List<Sample> snapshot() {
            return List.copyOf(samples);
        }
    }

    private final Map<NodeId, Window> windows = new ConcurrentHashMap<>();

    /**
     * Record one probe of one service.
     *
     * @param service     the service probed.
     * @param kind        which kind it is.
     * @param reachable   whether it answered.
     * @param rttMillis   the round-trip time; ignored when {@code reachable} is false.
     * @param nowEpochMillis when the probe completed.
     * @throws IllegalArgumentException if a reference argument is null.
     * @Thread-context any thread.
     */
    public void record(NodeId service, ServiceKind kind, boolean reachable, long rttMillis,
            long nowEpochMillis) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(kind, "kind");
        // A negative or absurd RTT is a failed measurement, not a fast one: Reachability reports -1
        // for unreachable, and treating that as 0 ms would make a dead service the best-scoring one.
        int rtt = reachable && rttMillis >= 0
                ? (int) Math.min(rttMillis, Integer.MAX_VALUE)
                : -1;
        windows.computeIfAbsent(service, id -> new Window(kind))
                .add(new Sample(reachable && rtt >= 0, Math.max(rtt, 0), nowEpochMillis));
    }

    /**
     * Forget everything measured about a service.
     *
     * <p>Used when a service announces {@code STOPPED}: keeping its window would let a decommissioned
     * host's history influence a later service that reuses its id.
     *
     * @param service the service to forget.
     * @Thread-context any thread.
     */
    public void forget(NodeId service) {
        windows.remove(Objects.requireNonNull(service, "service"));
    }

    /** @return how many services this peer holds measurements for. @Thread-context any thread. */
    public int measuredServices() {
        return windows.size();
    }

    /**
     * This peer's own measurements, as the counters a tracker aggregates.
     *
     * @param nowEpochMillis the current wall clock, stamped on each row as its freshness bound.
     * @return one row per measured service; services with no samples are omitted.
     * @Thread-context any thread.
     */
    public List<ServiceObservation> observations(long nowEpochMillis) {
        List<ServiceObservation> rows = new ArrayList<>(windows.size());
        for (Map.Entry<NodeId, Window> held : windows.entrySet()) {
            List<Sample> samples = held.getValue().snapshot();
            if (samples.isEmpty()) {
                continue;
            }
            rows.add(new ServiceObservation(held.getKey(), held.getValue().kind, samples.size(),
                    (int) samples.stream().filter(Sample::reachable).count(),
                    percentile(samples, 50), percentile(samples, 95), nowEpochMillis));
        }
        rows.sort(Comparator.comparing(row -> row.service().value()));
        return List.copyOf(rows);
    }

    /**
     * This peer's own availability for a service, in permille, or {@code -1} when unmeasured.
     *
     * @param service the service.
     * @return 0..1000, or -1.
     * @Thread-context any thread.
     */
    public int measuredAvailabilityPermille(NodeId service) {
        Window window = windows.get(Objects.requireNonNull(service, "service"));
        if (window == null) {
            return -1;
        }
        List<Sample> samples = window.snapshot();
        if (samples.isEmpty()) {
            return -1;
        }
        long reachable = samples.stream().filter(Sample::reachable).count();
        return (int) ((reachable * ServiceScore.PERMILLE) / samples.size());
    }

    /**
     * The score this peer assigns a directory row, preferring its own evidence.
     *
     * <p>Where this peer has probed the service, availability and latency come from its own window and
     * the tracker's aggregate is ignored; capacity and freshness always come from the service's signed
     * record and the answering tracker, because a peer cannot observe either. Where this peer has never
     * probed the service, the tracker's aggregate is all the evidence there is — the position a peer
     * joining the network is in, and the reason the aggregate exists.
     *
     * @param entry          the directory row.
     * @param nowEpochMillis the current wall clock.
     * @return the composite in permille.
     * @throws IllegalArgumentException if {@code entry} is null.
     * @Thread-context any thread.
     */
    public int scoreOf(ServiceDirectoryEntry entry, long nowEpochMillis) {
        Objects.requireNonNull(entry, "entry");
        // A draining service scores nothing: it has told us, in a message we verified, that it is
        // about to stop. Ranking it at all would send new work at a host that already said no.
        if (!entry.record().lifecycle().acceptsNewWork()) {
            return 0;
        }
        // An expired record is stale evidence about a host that may be long gone.
        if (entry.record().expiresAtEpochMillis() != 0
                && nowEpochMillis > entry.record().expiresAtEpochMillis()) {
            return 0;
        }
        ServiceScore aggregate = entry.score();
        int availability = aggregate.availabilityPermille();
        int rttP95 = aggregate.rttP95Millis();
        Window window = windows.get(entry.record().service());
        if (window != null) {
            List<Sample> samples = window.snapshot();
            if (!samples.isEmpty()) {
                long reachable = samples.stream().filter(Sample::reachable).count();
                availability = (int) ((reachable * ServiceScore.PERMILLE) / samples.size());
                rttP95 = percentile(samples, 95);
                // Measured as unreachable and never once answering: no aggregate can rescue that,
                // because this peer is the one that has to reach it.
                if (reachable == 0) {
                    return 0;
                }
            }
        }
        return ServiceScore.composite(availability, rttP95,
                entry.record().capacityPermille(), aggregate.freshnessPermille());
    }

    /**
     * Choose which services to use, best first.
     *
     * <p>Returns up to {@code fanout} rows, because a peer that registered with several rendezvous but
     * queried only the first would have converted redundancy into a silent single point of failure —
     * the fallback endpoints would hold records nobody reads (rendezvous {@code REFERENCE.md} §9.1).
     * Rows scoring zero are dropped rather than kept as a last resort: they are draining, expired, or
     * measured dead, and dialling them wastes the reconnect budget that failover needs.
     *
     * @param directory      candidate rows, in any order.
     * @param fanout         how many to select; values below 1 read as 1.
     * @param nowEpochMillis the current wall clock.
     * @return the selection, best first.
     * @throws IllegalArgumentException if {@code directory} is null.
     * @Thread-context any thread.
     */
    public List<ServiceDirectoryEntry> select(List<ServiceDirectoryEntry> directory, int fanout,
            long nowEpochMillis) {
        Objects.requireNonNull(directory, "directory");
        List<ServiceDirectoryEntry> ranked = new ArrayList<>();
        for (ServiceDirectoryEntry entry : directory) {
            if (scoreOf(entry, nowEpochMillis) > 0) {
                ranked.add(entry);
            }
        }
        // Ordering, in three steps, and each step exists because the one before it can tie:
        //
        //   1. this peer's own score — the only evidence about whether *this* peer can use the relay;
        //   2. the tracker's aggregate, recomputed — when a peer's own probes cannot separate two
        //      relays (a handful of successful probes at similar RTT look identical), the network's
        //      broader evidence is a better tie-break than an arbitrary constant;
        //   3. service id — so two peers with genuinely identical evidence still make the *same*
        //      choice, instead of scattering a swarm across relays that never share a path.
        ranked.sort(Comparator
                .comparingInt((ServiceDirectoryEntry e) -> -scoreOf(e, nowEpochMillis))
                .thenComparingInt(e -> -e.score().recomputedComposite())
                .thenComparing(e -> e.record().service().value()));
        return List.copyOf(ranked.subList(0, Math.min(Math.max(fanout, 1), ranked.size())));
    }

    /**
     * Nearest-rank percentile of the successful samples' RTTs.
     *
     * <p>Failed probes are excluded: a timeout has no round-trip time, and folding it in as a large
     * number would double-count the failure that availability already reflects.
     */
    private static int percentile(List<Sample> samples, int percentile) {
        int[] rtts = samples.stream().filter(Sample::reachable).mapToInt(Sample::rttMillis)
                .sorted().toArray();
        if (rtts.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil((percentile / 100.0) * rtts.length) - 1;
        return rtts[Math.min(Math.max(rank, 0), rtts.length - 1)];
    }
}
