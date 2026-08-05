package dev.nodera.peer.discovery;

import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.service.ServiceDirectoryEntry;
import dev.nodera.protocol.service.ServiceDrainNotice;
import dev.nodera.protocol.service.ServiceKind;
import dev.nodera.protocol.service.ServiceLifecycle;
import dev.nodera.transport.Reachability;
import dev.nodera.transport.rendezvous.RendezvousEndpoint;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Where a peer's rendezvous points come from, and which of them it should be using right now.
 *
 * <h2>What this replaces</h2>
 *
 * <p>A hand-written list. Before this class, every peer's rendezvous set was whatever string was in
 * {@code NODERA_RENDEZVOUS_ENDPOINTS} or {@code rendezvous.endpoints}, with three consequences:
 * adding a rendezvous to the network reached nobody, losing one was an outage for everyone configured
 * to use it, and no peer had any way to prefer the relay that actually worked for it.
 *
 * <p>Now: ask the trackers, verify every row, score it against this peer's own measurements, and keep
 * the best few. Configured endpoints are not discarded — they are <b>seeds</b>, kept ahead of discovered
 * ones for as long as they answer, because an operator who pinned a relay meant it and a LAN-only
 * deployment has no tracker to ask.
 *
 * <h2>Several at once, and all of them read</h2>
 *
 * <p>{@link #selected()} returns a list, not an endpoint. Registering with several rendezvous but
 * querying only the first converts redundancy into a silent single point of failure — the fallback
 * endpoints hold records nobody reads ({@code docs/rendezvous/REFERENCE.md}). Callers register with,
 * and discover through, every entry in that list.
 *
 * <h2>A drain is a migration, not a failure</h2>
 *
 * <p>{@link #onDrainNotice} takes a signed notice pushed down a relay's own control channel. The
 * service is dropped from the selection immediately, its named replacements are folded in, and the
 * refresh happens on the next sweep — so the peer moves while the old relay is still carrying its
 * existing circuits, rather than after they break.
 *
 * <p>Thread-context: thread-safe. Sweeps run on a scheduler thread; {@link #selected()} is read by
 * whichever thread composes a transport, and drain notices arrive on a relay reader thread.
 */
public final class RendezvousDirectory {

    /** How many rendezvous points to use at once. */
    public static final int DEFAULT_FANOUT = ServiceScoreBoard.DEFAULT_FANOUT;

    /** Probe timeout: a relay that cannot complete a TCP handshake this fast is not a good relay. */
    /**
     * How long a candidate probe waits for a TCP handshake.
     *
     * <p>Above {@link dev.nodera.protocol.service.ServiceScore#LATENCY_CEILING_MILLIS}, past
     * which a relay scores zero regardless — giving up sooner would drop a relay the scorer
     * was still willing to rank, which is how a working service becomes an invisible one.
     * 400 ms was under one round trip to most of the world; see WorldHostingService.
     */
    public static final Duration PROBE_TIMEOUT = Duration.ofMillis(
            dev.nodera.protocol.service.ServiceScore.LATENCY_CEILING_MILLIS + 500);

    /**
     * How a candidate is measured. A seam, not a wrapper: the production implementation is a TCP
     * handshake against the relay's own port, and a test needs to describe a relay as reachable or not
     * without standing one up.
     */
    @FunctionalInterface
    public interface Prober {
        /**
         * Probe one endpoint.
         *
         * @param host    the host.
         * @param port    the port.
         * @param timeout how long to wait.
         * @return the result.
         */
        Reachability.Probe probe(String host, int port, Duration timeout);
    }

    private final TrackerClient trackers;
    private final ServiceScoreBoard scores;
    private final UUID networkId;
    private final int fanout;
    private final List<RendezvousEndpoint> seeds;
    private final Prober prober;

    /** The last verified directory, by service id. Replaced wholesale so readers see a whole state. */
    private final AtomicReference<List<ServiceDirectoryEntry>> directory =
            new AtomicReference<>(List.of());

    /** The current selection, best first. */
    private final AtomicReference<List<ServiceDirectoryEntry>> selection =
            new AtomicReference<>(List.of());

    /** Services that told us they are draining, so a sweep does not re-select them. */
    private final Set<NodeId> draining = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Replacements named by drain notices, folded into the next sweep. */
    private final List<ServiceDirectoryEntry> offered = new CopyOnWriteArrayList<>();

    /** Notified whenever the endpoint list changes, so a transport can re-register. */
    private final List<Consumer<List<RendezvousEndpoint>>> listeners = new CopyOnWriteArrayList<>();

    /**
     * @param trackers  the tracker client to ask; may have no endpoints (then only seeds are used).
     * @param scores    this peer's measurements.
     * @param networkId the network this peer serves.
     * @param fanout    how many rendezvous to use at once; below 1 reads as 1.
     * @param seeds     configured endpoints, kept as preferred seeds.
     * @throws IllegalArgumentException if a reference argument is null.
     * @Thread-context any thread (construction only).
     */
    public RendezvousDirectory(TrackerClient trackers, ServiceScoreBoard scores, UUID networkId,
            int fanout, List<RendezvousEndpoint> seeds) {
        this(trackers, scores, networkId, fanout, seeds, Reachability::measure);
    }

    /**
     * @param trackers  the tracker client to ask.
     * @param scores    this peer's measurements.
     * @param networkId the network this peer serves.
     * @param fanout    how many rendezvous to use at once.
     * @param seeds     configured endpoints, kept as preferred seeds.
     * @param prober    how a candidate is measured.
     * @throws IllegalArgumentException if a reference argument is null.
     * @Thread-context any thread (construction only).
     */
    public RendezvousDirectory(TrackerClient trackers, ServiceScoreBoard scores, UUID networkId,
            int fanout, List<RendezvousEndpoint> seeds, Prober prober) {
        this.trackers = Objects.requireNonNull(trackers, "trackers");
        this.scores = Objects.requireNonNull(scores, "scores");
        this.networkId = Objects.requireNonNull(networkId, "networkId");
        this.fanout = Math.max(fanout, 1);
        this.seeds = List.copyOf(Objects.requireNonNull(seeds, "seeds"));
        this.prober = Objects.requireNonNull(prober, "prober");
    }

    /**
     * Register a listener for endpoint-list changes.
     *
     * @param listener called with the new list whenever it changes.
     * @throws IllegalArgumentException if {@code listener} is null.
     * @Thread-context any thread.
     */
    public void onEndpointsChanged(Consumer<List<RendezvousEndpoint>> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * @return the verified directory as last answered, best first.
     * @Thread-context any thread.
     */
    public List<ServiceDirectoryEntry> knownServices() {
        return directory.get();
    }

    /**
     * @return the selected rows, best first.
     * @Thread-context any thread.
     */
    public List<ServiceDirectoryEntry> selectedServices() {
        return selection.get();
    }

    /**
     * The endpoints a caller should register with and discover through, best first.
     *
     * <p>Seeds come first while they are configured: an operator who pinned a relay meant it, and this
     * class exists to <i>add</i> reach, not to override a deployment's own choices. Discovered
     * endpoints follow in score order.
     *
     * @return the endpoint list, deduplicated, never null.
     * @Thread-context any thread.
     */
    public List<RendezvousEndpoint> selected() {
        Set<RendezvousEndpoint> ordered = new LinkedHashSet<>(seeds);
        for (ServiceDirectoryEntry entry : selection.get()) {
            for (String route : entry.record().routes()) {
                RendezvousEndpoint parsed = parseRoute(route);
                if (parsed != null) {
                    ordered.add(parsed);
                    break; // one endpoint per service: the rest are alternate names for the same host
                }
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * Re-query the trackers, probe the candidates, re-select, and report what was measured.
     *
     * <p>The order matters. Probing happens against the <i>candidates</i>, not only the current
     * selection, because a peer that only ever measured what it was already using could never discover
     * that something better exists — and could never notice that its own relay's replacement is healthy.
     *
     * @param nowEpochMillis the current wall clock.
     * @return the endpoints in force after the sweep.
     * @Thread-context a scheduler thread; safe to call concurrently with readers.
     */
    public List<RendezvousEndpoint> sweep(long nowEpochMillis) {
        List<ServiceDirectoryEntry> answered =
                trackers.serviceDirectory(ServiceKind.RENDEZVOUS, networkId, 0);
        Map<NodeId, ServiceDirectoryEntry> candidates = new LinkedHashMap<>();
        for (ServiceDirectoryEntry entry : answered) {
            candidates.put(entry.record().service(), entry);
        }
        // A replacement a draining relay named is a candidate even if no tracker has mentioned it yet:
        // the relay heard about it from a tracker one announce ago, and the whole point of carrying it
        // in the notice is to skip a discovery round trip at the moment it is least affordable.
        for (ServiceDirectoryEntry replacement : offered) {
            candidates.putIfAbsent(replacement.record().service(), replacement);
        }
        offered.clear();

        // A service that said it is draining stays out until it announces Serving again. Its own
        // record is the authority on that, not our memory of the notice.
        for (ServiceDirectoryEntry entry : candidates.values()) {
            if (entry.record().lifecycle() == ServiceLifecycle.SERVING) {
                draining.remove(entry.record().service());
            }
        }

        List<ServiceDirectoryEntry> usable = new ArrayList<>();
        for (ServiceDirectoryEntry entry : candidates.values()) {
            if (!draining.contains(entry.record().service())) {
                usable.add(entry);
                probe(entry, nowEpochMillis);
            }
        }
        directory.set(List.copyOf(usable));
        selection.set(scores.select(usable, fanout, nowEpochMillis));

        // Reporting last: the trackers get this sweep's measurements, so the aggregate everyone else
        // reads includes what we just learned.
        trackers.reportServiceScores(networkId, scores.observations(nowEpochMillis), nowEpochMillis);
        List<RendezvousEndpoint> endpoints = selected();
        notifyListeners(endpoints);
        return endpoints;
    }

    /**
     * Handle a signed drain notice from a rendezvous.
     *
     * <p>The caller must have verified the signature — an unverified notice is an eviction primitive
     * anyone on the path could forge, herding this peer's traffic onto a relay of their choosing.
     * {@link TrackerClient#verifyServiceEntry} does the same check for a directory row.
     *
     * @param notice the verified notice.
     * @return the endpoints in force after dropping the draining service.
     * @throws IllegalArgumentException if {@code notice} is null.
     * @Thread-context any thread; typically a relay reader thread.
     */
    public List<RendezvousEndpoint> onDrainNotice(ServiceDrainNotice notice) {
        Objects.requireNonNull(notice, "notice");
        NodeId leaving = notice.record().service();
        draining.add(leaving);
        offered.addAll(notice.replacements());
        // Drop it from the selection now, without waiting for a sweep: the deadline in the notice may
        // be seconds away, and re-selecting from what we already hold costs nothing.
        List<ServiceDirectoryEntry> remaining = new ArrayList<>();
        for (ServiceDirectoryEntry entry : directory.get()) {
            if (!entry.record().service().equals(leaving)) {
                remaining.add(entry);
            }
        }
        for (ServiceDirectoryEntry replacement : notice.replacements()) {
            if (remaining.stream()
                    .noneMatch(e -> e.record().service().equals(replacement.record().service()))) {
                remaining.add(replacement);
            }
        }
        directory.set(List.copyOf(remaining));
        selection.set(scores.select(remaining, fanout, notice.record().issuedAtEpochMillis()));
        List<RendezvousEndpoint> endpoints = selected();
        notifyListeners(endpoints);
        return endpoints;
    }

    /**
     * Whether a service is currently excluded because it said it is draining.
     *
     * @param service the service.
     * @return true when excluded.
     * @Thread-context any thread.
     */
    public boolean isDraining(NodeId service) {
        return draining.contains(Objects.requireNonNull(service, "service"));
    }

    private void probe(ServiceDirectoryEntry entry, long nowEpochMillis) {
        for (String route : entry.record().routes()) {
            RendezvousEndpoint endpoint = parseRoute(route);
            if (endpoint == null) {
                continue;
            }
            Reachability.Probe probe =
                    prober.probe(endpoint.host(), endpoint.port(), PROBE_TIMEOUT);
            scores.record(entry.record().service(), entry.record().kind(), probe.reachable(),
                    probe.latencyMillis(), nowEpochMillis);
            // One route per service per sweep: probing every alternate name would multiply the
            // sweep's cost by however many addresses an operator chose to publish.
            return;
        }
    }

    /**
     * Parse a route into an endpoint, or null when it is not a form this transport can dial.
     *
     * <p>Null rather than an exception: routes are a service's own claim, and one malformed entry in a
     * directory answer must not abort a sweep that would otherwise have found four working relays.
     */
    private static RendezvousEndpoint parseRoute(String route) {
        if (route == null || route.isBlank()) {
            return null;
        }
        String bare = route.startsWith("tcp://") ? route.substring("tcp://".length()) : route;
        // The relay transport is TCP: a udp:// claim is a tracker route, not a rendezvous one.
        if (bare.contains("://")) {
            return null;
        }
        try {
            return RendezvousEndpoint.parse(bare);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private void notifyListeners(List<RendezvousEndpoint> endpoints) {
        for (Consumer<List<RendezvousEndpoint>> listener : listeners) {
            try {
                listener.accept(endpoints);
            } catch (RuntimeException e) {
                // A listener that throws must not stop the others from learning, and must not kill
                // the sweep thread: this runs on the worker's scheduler.
                System.err.println("nodera: rendezvous-directory listener failed: " + e);
            }
        }
    }
}
