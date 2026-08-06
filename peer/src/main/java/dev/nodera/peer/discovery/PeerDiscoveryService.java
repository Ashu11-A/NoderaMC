package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.discovery.TrackerResponse;
import dev.nodera.protocol.discovery.TrackerRoutesResponse;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.rendezvous.PeerCandidate;
import dev.nodera.protocol.rendezvous.RendezvousPeers;
import dev.nodera.protocol.rendezvous.SignedRecord;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.rendezvous.RendezvousClient;
import dev.nodera.transport.rendezvous.RendezvousEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Turns the discovery plane into an actual <b>peer</b>-discovery plane.
 *
 * <p>The tracker and the rendezvous service always knew who was in a world — but nothing asked
 * them on behalf of the session. A {@code PeerRuntime} learned members from exactly one place: the
 * single bootstrap route it was constructed with, plus whatever that bootstrap gossiped. So a peer
 * whose bootstrap was unreachable simply never meshed, no matter how many other members of the same
 * world were online and listed. That is the gap this closes: on a cadence, ask every configured
 * tracker <i>and</i> every configured rendezvous who else is in this world, merge the answers, and
 * hand each newly-learned dialable route to the session.
 *
 * <h2>Merged, never arbitrated</h2>
 *
 * <p>Sources are merged ({@code docs/tracker/REFERENCE.md}, "What this service deliberately does
 * not do": a tracker knows only who announced to <i>it</i>, so
 * several partial views beat one authoritative one). A source that omits peers loses influence
 * rather than winning it; a source that invents them costs one failed dial, because the transport
 * handshake re-verifies identity and nothing here is trusted as authority (docs/rendezvous/REFERENCE.md).
 *
 * <h2>Announce, do not connect</h2>
 *
 * <p>This class never opens a connection itself. It produces {@link PeerAddress}es and passes them
 * to the {@code announce} callback — in production {@code PeerRuntime::joinSession}-style
 * announcing — so all connection lifecycle stays in the runtime that owns it. Each address is
 * announced once per session unless it changes, so a stable swarm produces no repeated dialing.
 *
 * <p>Thread-context: {@link #start}/{@link #close} from any one thread; polling runs on a single
 * daemon scheduler.
 */
public final class PeerDiscoveryService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaDiscovery");

    /** Default seconds between discovery sweeps; trackers pace announces, not queries. */
    public static final int DEFAULT_POLL_SECONDS = 30;

    /** Route claims prefixed with this are Minecraft game endpoints, not P2P dial routes. */
    private static final String MC_ROUTE_PREFIX = "mc/";

    private final TrackerClient tracker;
    private final RendezvousClient rendezvous;
    private final List<RendezvousEndpoint> rendezvousEndpoints;
    /** The worlds to sweep; supplied live so hosting/joining a world changes the sweep set. */
    private final Supplier<Collection<Bytes>> worldIds;
    private final Consumer<PeerAddress> announce;
    private final int pollSeconds;

    /** Everything learned this run: nodeId string → the address last announced for it. */
    private final Map<String, PeerAddress> known = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;

    /**
     * @param tracker             the tracker client (may have no endpoints — then it contributes
     *                            nothing rather than failing).
     * @param rendezvous          the rendezvous client used for namespace discovery.
     * @param rendezvousEndpoints the rendezvous endpoints to sweep; may be empty.
     * @param worldIds            supplies the worlds to sweep, evaluated each sweep.
     * @param announce            called once per newly-learned dialable peer address.
     */
    public PeerDiscoveryService(TrackerClient tracker, RendezvousClient rendezvous,
                                List<RendezvousEndpoint> rendezvousEndpoints,
                                Supplier<Collection<Bytes>> worldIds,
                                Consumer<PeerAddress> announce) {
        this(tracker, rendezvous, rendezvousEndpoints, worldIds, announce, DEFAULT_POLL_SECONDS);
    }

    /** As the primary constructor, with an explicit sweep cadence (tests use a short one). */
    public PeerDiscoveryService(TrackerClient tracker, RendezvousClient rendezvous,
                                List<RendezvousEndpoint> rendezvousEndpoints,
                                Supplier<Collection<Bytes>> worldIds,
                                Consumer<PeerAddress> announce, int pollSeconds) {
        this.tracker = tracker;
        this.rendezvous = rendezvous;
        this.rendezvousEndpoints = rendezvousEndpoints == null ? List.of()
                : List.copyOf(rendezvousEndpoints);
        this.worldIds = java.util.Objects.requireNonNull(worldIds, "worldIds");
        this.announce = java.util.Objects.requireNonNull(announce, "announce");
        this.pollSeconds = Math.max(5, pollSeconds);
    }

    /** Begin sweeping. Idempotent; the first sweep runs immediately. */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nodera-peer-discovery");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::sweepQuietly, 0, pollSeconds, TimeUnit.SECONDS);
    }

    /**
     * Run one sweep now, off the cadence (e.g. right after this node starts hosting a world, when
     * waiting 30 s for the next tick would be a visible stall).
     *
     * @Thread-context any thread; the sweep itself runs on the scheduler.
     */
    public void sweepNow() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.execute(this::sweepQuietly);
        }
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    // --- internals -------------------------------------------------------------------------------

    private void sweepQuietly() {
        try {
            sweep();
        } catch (RuntimeException e) {
            // A discovery sweep is best-effort telemetry-grade work: it must never kill the
            // scheduler thread and silently stop all future discovery.
            LOG.debug("discovery sweep failed: {}", e.toString());
        }
    }

    /**
     * One sweep over every world: merge tracker and rendezvous answers into a route set, then
     * announce the ones we have not already announced at that address.
     */
    void sweep() {
        Collection<Bytes> worlds = worldIds.get();
        if (worlds == null || worlds.isEmpty()) {
            return;
        }
        for (Bytes worldId : worlds) {
            Map<String, PeerAddress> found = new LinkedHashMap<>();
            found.putAll(fromTracker(worldId));
            // Rendezvous records carry richer candidates (host first, relay as fallback), so they
            // are merged last and win ties — a peer that published a fresh host candidate should be
            // dialed there rather than at a stale tracker route.
            found.putAll(fromRendezvous(worldId));

            for (Map.Entry<String, PeerAddress> entry : found.entrySet()) {
                PeerAddress previous = known.get(entry.getKey());
                if (previous != null && previous.route().equals(entry.getValue().route())) {
                    continue; // already meshed at this exact route; announcing again is noise
                }
                known.put(entry.getKey(), entry.getValue());
                try {
                    announce.accept(entry.getValue());
                } catch (RuntimeException e) {
                    LOG.debug("announce to {} failed: {}", entry.getValue(), e.toString());
                }
            }
        }
    }

    /** Peers a tracker knows for this world, with a dialable P2P route. */
    private Map<String, PeerAddress> fromTracker(Bytes worldId) {
        Map<String, PeerAddress> out = new LinkedHashMap<>();
        if (tracker == null || tracker.endpoints().isEmpty()) {
            return out;
        }
        // The peer list names who is in the world; the routes query is what makes them dialable.
        Set<String> inWorld = new LinkedHashSet<>();
        try {
            TrackerResponse response = tracker.query(worldId).orElse(null);
            if (response != null) {
                for (PeerEntry peer : response.peers()) {
                    inWorld.add(peer.nodeId().value().toString());
                    if (!peer.route().isBlank() && !peer.route().startsWith(MC_ROUTE_PREFIX)) {
                        out.put(peer.nodeId().value().toString(),
                                PeerAddress.of(peer.nodeId(), peer.route()));
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("tracker peer query failed: {}", e.toString());
        }
        try {
            TrackerRoutesResponse routes = tracker.routes(worldId);
            for (TrackerRoutesResponse.PeerRoutes peer : routes.peers()) {
                for (String route : peer.routes()) {
                    // "mc/host:port" is the Minecraft game endpoint a player connects to, not a
                    // P2P dial route — dialing it would speak the wrong protocol at a game server.
                    if (route.isBlank() || route.startsWith(MC_ROUTE_PREFIX)) {
                        continue;
                    }
                    out.put(peer.peer().value().toString(), PeerAddress.of(peer.peer(), route));
                    break;
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("tracker routes query failed: {}", e.toString());
        }
        return out;
    }

    /** Peers a rendezvous service knows for this world, preferring their direct candidates. */
    private Map<String, PeerAddress> fromRendezvous(Bytes worldId) {
        Map<String, PeerAddress> out = new LinkedHashMap<>();
        if (rendezvous == null || rendezvousEndpoints.isEmpty()) {
            return out;
        }
        UUID networkId = UUID.nameUUIDFromBytes(worldId.toArray());
        for (RendezvousEndpoint endpoint : rendezvousEndpoints) {
            try {
                RendezvousPeers page = rendezvous.discover(endpoint, networkId, worldId, 0, 0);
                for (SignedRecord record : page.records()) {
                    String best = bestDirectCandidate(record.record().candidates());
                    if (best != null) {
                        out.put(record.record().peer().value().toString(),
                                PeerAddress.of(record.record().peer(), best));
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOG.debug("rendezvous discover at {} failed: {}", endpoint, e.toString());
            }
        }
        return out;
    }

    /**
     * The highest-priority direct candidate of a record, or {@code null} when the peer published
     * only a relay candidate. A relay address is not dialable by the plain socket transport, and
     * handing one to the runtime would produce a connection attempt that can only fail — the relay
     * path belongs to the rendezvous transport, which reaches it through its own circuit.
     */
    private static String bestDirectCandidate(List<PeerCandidate> candidates) {
        List<PeerCandidate> direct = new ArrayList<>();
        for (PeerCandidate candidate : candidates) {
            if (candidate.isDirect() && !candidate.address().isBlank()) {
                direct.add(candidate);
            }
        }
        if (direct.isEmpty()) {
            return null;
        }
        direct.sort(java.util.Comparator.comparingInt(PeerCandidate::priority).reversed());
        return direct.get(0).address();
    }
}
