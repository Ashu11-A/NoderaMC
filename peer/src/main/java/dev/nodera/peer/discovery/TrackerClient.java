package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.protocol.content.ManifestHolding;
import dev.nodera.protocol.discovery.AnnounceEvent;
import dev.nodera.protocol.discovery.TrackerAnnounce;
import dev.nodera.protocol.discovery.TrackerAnnounceAck;
import dev.nodera.protocol.discovery.TrackerQuery;
import dev.nodera.protocol.discovery.TrackerResponse;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.service.ServiceAnnounceAck;
import dev.nodera.protocol.service.ServiceDirectoryEntry;
import dev.nodera.protocol.service.ServiceDirectoryQuery;
import dev.nodera.protocol.service.ServiceDirectoryResponse;
import dev.nodera.protocol.service.ServiceKind;
import dev.nodera.protocol.service.ServiceObservation;
import dev.nodera.protocol.service.ServiceScoreReport;
import dev.nodera.transport.Frames;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The peer half of the tracker (Task 28): announces this peer to configured
 * {@code nodera-tracker} endpoints and queries them for a world's peers and seeders.
 *
 * <p>It replaces the <b>serving</b> role that Task 20 embedded in a Java peer
 * ({@code TrackerService}, deleted with this class's arrival — see {@code docs/tracker/LIMITATIONS.fixed.md}).
 * Only the process that answers strangers moved out; what this client learns is handed to
 * {@link PeerDiscoveryService}, which is what dials.
 *
 * <h2>Nothing here is trusted</h2>
 *
 * <p>A tracker can hide peers or invent unreachable ones. It cannot forge state (every piece is
 * hash-verified against a manifest) or identities (records are Ed25519-signed by the peer they
 * describe). So this client treats a response as a set of <i>hints</i>: addresses to dial and
 * seeders to try. When several endpoints answer, their peer lists are merged rather than
 * arbitrated — a tracker that omits peers loses influence instead of winning it.
 *
 * <h2>The tracker paces the traffic</h2>
 *
 * <p>Each ack carries {@code nextAnnounceAfterSeconds}; {@link #announceIntervalSeconds()} reports
 * the most recently accepted value so a caller's loop can honour it. An operator widening the
 * interval under load therefore does not need every peer to ship a new build.
 *
 * <p>Thread-context: thread-safe. Each call opens, uses and closes its own short-lived socket, so
 * concurrent announces and queries never share connection state. {@link #close()} is idempotent.
 */
public final class TrackerClient implements TrackerLookup {

    /** Interval assumed before any tracker has answered. */
    public static final int DEFAULT_ANNOUNCE_INTERVAL_SECONDS = 120;

    /** Largest request this client will put in a datagram; anything larger goes over TCP. */
    static final int UDP_MAX_REQUEST_BYTES = 8 * 1024;
    /** Receive-buffer bound for a UDP reply — the datagram analogue of the frame-length cap. */
    static final int UDP_MAX_REPLY_BYTES = 32 * 1024;
    /** Datagram send attempts before falling back to TCP (UDP may drop either direction). */
    static final int UDP_ATTEMPTS = 2;

    /**
     * How a tracker endpoint is reached. Both surfaces carry the same frozen message family; they
     * differ only in framing and in what a service is willing to answer (see
     * {@code docs/tracker/REFERENCE.md}, "Surfaces").
     */
    public enum Transport {
        /**
         * Length-prefixed frames over TCP: the complete surface. Any answer size, any request
         * size, and the handshake proves the source address.
         */
        TCP,
        /**
         * One datagram per request, no length prefix — the datagram boundary is the frame. Cheaper
         * (no handshake) for a peer sweeping many trackers on a cadence, but the service bounds
         * both the request and the answer because a UDP source address is forgeable. A query whose
         * answer exceeds those bounds simply goes unanswered, which is why {@link #query} retries
         * such an endpoint over TCP rather than reporting the world as empty.
         */
        UDP;

        /** The URI scheme this transport is written as in config ({@code tcp} / {@code udp}). */
        public String scheme() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * One tracker endpoint.
     *
     * @param host      the host name or literal address.
     * @param port      the port.
     * @param transport how to reach it.
     * @Thread-context immutable record, safe for any thread.
     */
    public record Endpoint(String host, int port, Transport transport) {

        /**
         * Compact constructor.
         *
         * @throws IllegalArgumentException if the host is blank or the port is out of range.
         */
        public Endpoint {
            Objects.requireNonNull(host, "host");
            if (host.isBlank()) {
                throw new IllegalArgumentException("host must not be blank");
            }
            if (port <= 0 || port > 65_535) {
                throw new IllegalArgumentException("port out of range: " + port);
            }
            transport = transport == null ? Transport.TCP : transport;
        }

        /** A TCP endpoint — the default surface, and what a bare {@code host:port} means. */
        public Endpoint(String host, int port) {
            this(host, port, Transport.TCP);
        }

        /**
         * Parse a route as it appears in config: {@code host:port}, {@code tcp://host:port}, or
         * {@code udp://host:port}.
         *
         * <p>A bare {@code host:port} is TCP, so every existing config keeps working unchanged and
         * an operator opts into UDP explicitly.
         *
         * @param route the route.
         * @return the endpoint.
         * @throws IllegalArgumentException if the route is malformed or names an unknown scheme.
         * @Thread-context any thread.
         */
        public static Endpoint parse(String route) {
            Objects.requireNonNull(route, "route");
            String remainder = route.trim();
            Transport transport = Transport.TCP;
            int scheme = remainder.indexOf("://");
            if (scheme > 0) {
                String name = remainder.substring(0, scheme);
                try {
                    transport = Transport.valueOf(name.toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException unknown) {
                    throw new IllegalArgumentException(
                            "unknown tracker scheme '" + name + "' in " + route
                                    + " (expected tcp:// or udp://)", unknown);
                }
                remainder = remainder.substring(scheme + 3);
            }
            int idx = remainder.lastIndexOf(':');
            if (idx <= 0 || idx == remainder.length() - 1) {
                throw new IllegalArgumentException("malformed tracker endpoint: " + route);
            }
            String host = remainder.substring(0, idx);
            // Strip the brackets of a literal IPv6 route so InetSocketAddress accepts it.
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            try {
                return new Endpoint(host, Integer.parseInt(remainder.substring(idx + 1)), transport);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("malformed tracker endpoint port: " + route, e);
            }
        }

        /** @return this endpoint as the same TCP host:port, for a UDP endpoint's fallback. */
        public Endpoint asTcp() {
            return transport == Transport.TCP ? this : new Endpoint(host, port, Transport.TCP);
        }

        /** @return the canonical config form, e.g. {@code "udp://127.0.0.1:25600"}. */
        @Override
        public String toString() {
            return transport.scheme() + "://" + host + ":" + port;
        }
    }

    /**
     * The configured endpoints. Volatile and replaceable ({@link #setEndpoints}) because one client
     * is now shared by every lane of a node (announce, query, catalog, replication), so changing
     * the tracker list has to reach all of them at once — and because it moves "tracker endpoints"
     * from a restart-required setting to a live one. Replaced wholesale, never mutated in place, so
     * an in-flight sweep iterates a consistent list.
     */
    private volatile List<Endpoint> endpoints;

    /** Operator-supplied endpoints a configuration push may add to but never remove. */
    private volatile List<Endpoint> pinned = List.of();

    /** Verification only; the signing key never leaves {@link #identity}. */
    private static final SignatureService SIGNATURES = new SignatureService();

    /**
     * Directory ordering: best recomputed composite first, then by service id so two peers with the
     * same evidence build the same failover list.
     */
    private static final java.util.Comparator<ServiceDirectoryEntry> SERVICE_ENTRY_ORDER =
            java.util.Comparator
                    .comparingInt((ServiceDirectoryEntry e) -> -e.score().recomputedComposite())
                    .thenComparing(e -> e.record().service().value());

    private final NodeIdentity identity;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile int announceIntervalSeconds = DEFAULT_ANNOUNCE_INTERVAL_SECONDS;

    /**
     * Where a tracker's "that world was deleted" answer goes; null until the deletion lane binds.
     *
     * <p>This is how a peer that was offline when a world was deleted finds out. It announces the
     * world as usual, and instead of an ack the tracker hands back the owner's signed record. The
     * handler re-verifies it — nothing here trusts the tracker's verdict, only its delivery.
     */
    private volatile java.util.function.Consumer<
            dev.nodera.protocol.membership.WorldDeletionGossip> deletionNotices;

    /**
     * @param endpoints the configured tracker endpoints; may be empty (the client then no-ops,
     *                  which is the correct behaviour for a LAN-only deployment).
     * @param identity  this peer's identity — used to sign announces. The private key never leaves
     *                  it, and no tracker ever sees signing material.
     * @throws IllegalArgumentException if an argument is null.
     * @Thread-context any thread (construction only).
     */
    public TrackerClient(List<Endpoint> endpoints, NodeIdentity identity) {
        this(endpoints, identity, Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    /**
     * @param endpoints      the configured tracker endpoints.
     * @param identity       this peer's identity.
     * @param connectTimeout socket connect timeout.
     * @param readTimeout    socket read timeout — an unresponsive tracker must never block a peer's
     *                       announce loop indefinitely.
     * @throws IllegalArgumentException if an argument is null or a timeout is negative.
     * @Thread-context any thread (construction only).
     */
    public TrackerClient(List<Endpoint> endpoints, NodeIdentity identity,
                         Duration connectTimeout, Duration readTimeout) {
        this.endpoints = List.copyOf(Objects.requireNonNull(endpoints, "endpoints"));
        this.identity = Objects.requireNonNull(identity, "identity");
        this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        this.readTimeout = requirePositive(readTimeout, "readTimeout");
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    /** @return the configured endpoints, in order. */
    public List<Endpoint> endpoints() {
        return endpoints;
    }

    /**
     * Replace the tracker list while the node is running.
     *
     * <p>Takes effect on the next announce/query — there is no connection state to migrate, since
     * every exchange opens and closes its own short-lived socket. A node that drops a tracker stops
     * announcing to it and simply ages out of that tracker's listing; one that adds a tracker
     * appears in it on the next heartbeat. Neither needs a restart.
     *
     * <p>An empty list is legitimate and means "announce nowhere" (a LAN-only or fully-manual
     * deployment); it is not treated as a mistake.
     *
     * <p>Endpoints {@linkplain #pinEndpoints pinned} by whoever started this worker survive the
     * replacement: see that method for why an app must not be able to take them away.
     *
     * @param newEndpoints the endpoints to use from now on; copied defensively.
     * @throws IllegalArgumentException if {@code newEndpoints} is null.
     * @Thread-context any thread.
     */
    public void setEndpoints(List<Endpoint> newEndpoints) {
        Objects.requireNonNull(newEndpoints, "endpoints");
        java.util.LinkedHashSet<Endpoint> merged = new java.util.LinkedHashSet<>(pinned);
        merged.addAll(newEndpoints);
        this.endpoints = List.copyOf(merged);
    }

    /**
     * Endpoints this node must always announce to, whatever any later {@link #setEndpoints} says.
     *
     * <p>For the tracker list the companion app and the operator are two authorities, and the app
     * used to win outright: it pushes its whole settings document on connect, {@code
     * network.default_trackers} is applied live, and the apply REPLACED the list. So attaching an
     * app to a worker that had been started with {@code NODERA_TRACKER_ENDPOINTS} pointing at a
     * LAN tracker swapped that tracker for the app's own — and the operator's tracker was gone from
     * a node that never asked to leave it.
     *
     * <p>What that looks like from the outside is not a settings problem. Observed on a two-player
     * dev stack: each player's world announced to a tracker that does not answer
     * ("world 'Ashu' is on NO tracker — 0 of 1 answered"), each player's Worlds tab listed nothing,
     * and the one worker with no app attached — the spare — was the only node in the whole stack
     * that could see the directory. Two players on one LAN, one tracker between them, and neither
     * could find the other.
     *
     * <p>Pinned endpoints are a floor, not a lock: the app can still add trackers, and everything it
     * adds is used. Only removal of the operator's own is refused.
     *
     * @param operatorEndpoints the endpoints supplied out of band (env, service file, CLI).
     * @throws IllegalArgumentException if {@code operatorEndpoints} is null.
     * @Thread-context any thread.
     */
    public void pinEndpoints(List<Endpoint> operatorEndpoints) {
        Objects.requireNonNull(operatorEndpoints, "operatorEndpoints");
        this.pinned = List.copyOf(operatorEndpoints);
        setEndpoints(this.endpoints);
    }

    /** @return the endpoints no configuration push can remove. */
    public List<Endpoint> pinnedEndpoints() {
        return pinned;
    }

    /**
     * @return the announce interval the trackers most recently asked for, in seconds.
     * @Thread-context any thread.
     */
    public int announceIntervalSeconds() {
        return announceIntervalSeconds;
    }

    /**
     * Build and sign an announce for this peer.
     *
     * @param genesisHash    the world.
     * @param event          the lifecycle event.
     * @param routes         the routes this peer is reachable on.
     * @param capabilities   this peer's declared capabilities, roles included.
     * @param holdings       what this peer holds for the world.
     * @param worldName      the display name — honoured by the tracker only from a
     *                       {@code FULL_ARCHIVE} host, so a non-host may pass {@code ""}.
     * @param retentionDeadlineEpochMillis the countdown to surface, or {@code 0}.
     * @param reliabilityBps this peer's reliability in basis points.
     * @param nowEpochMillis the current wall clock — the tracker's freshness window is checked
     *                       against it, so a peer with a badly wrong clock is rejected loudly
     *                       rather than silently ignored.
     * @return the signed announce.
     * @Thread-context any thread.
     */
    public TrackerAnnounce buildAnnounce(
            Bytes genesisHash, AnnounceEvent event, List<String> routes,
            NodeCapabilities capabilities, List<ManifestHolding> holdings,
            String worldName, long retentionDeadlineEpochMillis, int reliabilityBps,
            long nowEpochMillis) {
        return buildAnnounce(genesisHash, event, routes, capabilities, holdings, worldName,
                retentionDeadlineEpochMillis, reliabilityBps,
                TrackerAnnounce.UNKNOWN_PLAYER_COUNT, nowEpochMillis);
    }

    /**
     * As above, also reporting how many players this peer can see in the world.
     *
     * @param worldPlayerCount players in-world, or {@link TrackerAnnounce#UNKNOWN_PLAYER_COUNT}
     *                         when this peer has no game there and therefore cannot count. Every
     *                         seeder is in the second case, which is why the overload above
     *                         defaults to it rather than to zero.
     */
    public TrackerAnnounce buildAnnounce(
            Bytes genesisHash, AnnounceEvent event, List<String> routes,
            NodeCapabilities capabilities, List<ManifestHolding> holdings,
            String worldName, long retentionDeadlineEpochMillis, int reliabilityBps,
            long worldPlayerCount, long nowEpochMillis) {
        // Signature covers everything but itself, so the placeholder below is never signed over.
        TrackerAnnounce unsigned = new TrackerAnnounce(
                genesisHash, identity.nodeId(), identity.publicKeyBytes(), event, routes,
                capabilities, holdings, worldName, retentionDeadlineEpochMillis, reliabilityBps,
                worldPlayerCount, nowEpochMillis, Bytes.empty());
        Bytes signature = identity.sign(unsigned.signedPortion());
        return new TrackerAnnounce(
                genesisHash, identity.nodeId(), identity.publicKeyBytes(), event, routes,
                capabilities, holdings, worldName, retentionDeadlineEpochMillis, reliabilityBps,
                worldPlayerCount, nowEpochMillis, signature);
    }

    /**
     * Send an announce to every configured endpoint.
     *
     * <p>Endpoints are independent: one unreachable tracker does not stop the others, and the
     * method never throws for a network failure — a peer whose announce loop died on a refused
     * connection would silently vanish from every world list.
     *
     * @param announce the signed announce.
     * @return the per-endpoint acks, in endpoint order; an endpoint that failed is absent.
     * @Thread-context any thread.
     */
    public Map<Endpoint, TrackerAnnounceAck> announce(TrackerAnnounce announce) {
        Objects.requireNonNull(announce, "announce");
        Map<Endpoint, TrackerAnnounceAck> acks = new LinkedHashMap<>();
        for (Endpoint endpoint : endpoints) {
            exchange(endpoint, announce).ifPresent(reply -> {
                if (reply instanceof TrackerAnnounceAck ack) {
                    acks.put(endpoint, ack);
                    if (ack.accepted()) {
                        announceIntervalSeconds = ack.nextAnnounceAfterSeconds();
                    }
                } else if (reply instanceof dev.nodera.protocol.membership.WorldDeletionGossip
                        deletion) {
                    // Not an ack, and not an error either: the world we just announced has been
                    // deleted by its owner, and the tracker is handing us the proof rather than
                    // asking us to take its word for it.
                    java.util.function.Consumer<dev.nodera.protocol.membership.WorldDeletionGossip>
                            handler = deletionNotices;
                    if (handler != null) {
                        handler.accept(deletion);
                    }
                }
            });
        }
        return acks;
    }

    /**
     * Bind the handler for deletion notices returned in place of an announce ack.
     *
     * @param handler receives the notice; it must verify the record itself before acting on it.
     * @Thread-context any thread.
     */
    public void onDeletionNotice(java.util.function.Consumer<
            dev.nodera.protocol.membership.WorldDeletionGossip> handler) {
        this.deletionNotices = handler;
    }

    /**
     * Tell every configured tracker that a world has been deleted.
     *
     * <p>Sent to the trackers rather than only to peers because a tracker is where a world is
     * <b>found</b>. A deletion that reached every current peer but no tracker would leave the world
     * advertised to everyone who had not joined yet, and the first of them to announce it would put
     * it back in front of the rest.
     *
     * @param deletion the signed deletion.
     * @return how many trackers answered.
     * @Thread-context any thread; never throws for a network failure.
     */
    public int publishDeletion(dev.nodera.protocol.membership.WorldDeletionGossip deletion) {
        Objects.requireNonNull(deletion, "deletion");
        int delivered = 0;
        for (Endpoint endpoint : endpoints) {
            if (exchange(endpoint, deletion).isPresent()) {
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * Tell every configured tracker that a deleted world is back, on its owner's signed instruction.
     *
     * <p>The trackers are the half of the network that remembers a deletion the longest — 120 days,
     * deliberately, so that peers which were offline still hear about it. That is exactly why the
     * restore has to reach them too: without this the owner's re-share would be refused by every
     * directory on the network while the peers happily held the world, and the world would be
     * unfindable rather than merely unlisted.
     *
     * @param revival the signed restore.
     * @return how many trackers answered.
     * @Thread-context any thread; never throws for a network failure.
     */
    public int publishRevival(dev.nodera.protocol.membership.WorldRevivalGossip revival) {
        Objects.requireNonNull(revival, "revival");
        int delivered = 0;
        for (Endpoint endpoint : endpoints) {
            if (exchange(endpoint, revival).isPresent()) {
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * Query every configured endpoint and merge the answers.
     *
     * <p>Merging, not arbitrating: peers and seeders from all responses are unioned (first
     * occurrence wins for a given {@code NodeId}), and the counters come from the response
     * reporting the most peers. A tracker that omits peers therefore dilutes its own influence
     * instead of censoring the world.
     *
     * @param genesisHash the world.
     * @return the merged response, or empty if no endpoint answered.
     * @Thread-context any thread.
     */
    public Optional<TrackerResponse> query(Bytes genesisHash) {
        Objects.requireNonNull(genesisHash, "genesisHash");
        TrackerQuery query = new TrackerQuery(genesisHash);
        List<TrackerResponse> responses = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            exchange(endpoint, query).ifPresent(reply -> {
                if (reply instanceof TrackerResponse response) {
                    responses.add(response);
                }
            });
        }
        return merge(genesisHash, responses);
    }

    /**
     * Fetch the tracker directory listing — every listed world, for the multiplayer "Worlds" tab.
     *
     * <p>Answers from all configured endpoints are unioned by genesis hash (first occurrence
     * wins), preserving each tracker's own order, so one dead tracker cannot blank the listing.
     *
     * @param limit max worlds per endpoint ({@code 0} = the tracker's default page).
     * @return the merged directory entries; empty when no endpoint answered.
     * @Thread-context any thread.
     */
    public List<dev.nodera.protocol.discovery.TrackerCatalogEntry> catalog(int limit) {
        Map<Bytes, dev.nodera.protocol.discovery.TrackerCatalogEntry> merged =
                new LinkedHashMap<>();
        var query = new dev.nodera.protocol.discovery.TrackerCatalogQuery(limit);
        for (Endpoint endpoint : endpoints) {
            exchange(endpoint, query).ifPresent(reply -> {
                if (reply instanceof dev.nodera.protocol.discovery.TrackerCatalogResponse response) {
                    for (var entry : response.worlds()) {
                        merged.putIfAbsent(entry.genesisHash(), entry);
                    }
                }
            });
        }
        return List.copyOf(merged.values());
    }

    /**
     * Fetch the full claimed dial-route lists of one world's live peers (the join flow: a
     * {@code PeerEntry} carries one P2P route, while joining needs the host's {@code mc/host:port}
     * game endpoint too).
     *
     * <p>Per-peer route lists from all endpoints are unioned (first occurrence per peer wins).
     *
     * @param genesisHash the world.
     * @return the merged response; peers empty when no endpoint knows the world.
     * @Thread-context any thread.
     */
    public dev.nodera.protocol.discovery.TrackerRoutesResponse routes(Bytes genesisHash) {
        Objects.requireNonNull(genesisHash, "genesisHash");
        Map<Object, dev.nodera.protocol.discovery.TrackerRoutesResponse.PeerRoutes> merged =
                new LinkedHashMap<>();
        var query = new dev.nodera.protocol.discovery.TrackerRoutesQuery(genesisHash);
        for (Endpoint endpoint : endpoints) {
            exchange(endpoint, query).ifPresent(reply -> {
                if (reply instanceof dev.nodera.protocol.discovery.TrackerRoutesResponse response) {
                    for (var peer : response.peers()) {
                        merged.putIfAbsent(peer.peer(), peer);
                    }
                }
            });
        }
        return new dev.nodera.protocol.discovery.TrackerRoutesResponse(
                genesisHash, List.copyOf(merged.values()));
    }

    /**
     * Merge several trackers' answers for one world.
     *
     * @param genesisHash the world.
     * @param responses   the answers received.
     * @return the merged answer, or empty when {@code responses} is empty.
     * @Thread-context any thread; pure function.
     */
    static Optional<TrackerResponse> merge(Bytes genesisHash, List<TrackerResponse> responses) {
        if (responses.isEmpty()) {
            return Optional.empty();
        }
        if (responses.size() == 1) {
            return Optional.of(responses.get(0));
        }
        TrackerResponse best = responses.get(0);
        for (TrackerResponse candidate : responses) {
            if (candidate.peers().size() > best.peers().size()) {
                best = candidate;
            }
        }
        Map<Object, PeerEntry> peers = new LinkedHashMap<>();
        for (TrackerResponse response : responses) {
            for (PeerEntry entry : response.peers()) {
                peers.putIfAbsent(entry.nodeId(), entry);
            }
        }
        return Optional.of(new TrackerResponse(
                genesisHash,
                best.worldName(),
                List.copyOf(peers.values()),
                best.seeders(),
                best.worldPlayerCount(),
                best.storedChunks(),
                best.reliabilityBps(),
                best.health(),
                best.retentionDeadlineEpochMillis()));
    }

    /**
     * Whether a peer with these capabilities should announce world display metadata.
     *
     * @param capabilities the peer's capabilities.
     * @return {@code true} only for the {@code FULL_ARCHIVE} host (rule 0).
     * @Thread-context any thread.
     */
    public static boolean isWorldHost(NodeCapabilities capabilities) {
        return capabilities.hasRole(PeerRole.FULL_ARCHIVE);
    }

    /**
     * Ask every tracker which services of a kind it knows, and merge the answers.
     *
     * <p>This is the query that ends the hand-written rendezvous list. Merging is a <b>union with a
     * best-evidence tie-break</b>, never an arbitration: a service listed by three trackers appears
     * once, carrying the highest score any of them reported, because a tracker that has heard from
     * more peers has more evidence — not more authority.
     *
     * <p>Every row's signature is verified here, and every composite is recomputed from its
     * components. A tracker can therefore omit an honest rendezvous or list one that does not answer
     * — the powers it already has over worlds — but it cannot forge a service's identity, invent a
     * drain deadline, or inflate a score to steer this peer's traffic.
     *
     * @param kind      which kind of service to list.
     * @param networkId the network this peer serves.
     * @param limit     maximum rows per tracker; 0 lets each tracker choose its page size.
     * @return the merged, verified rows, best composite first.
     * @throws IllegalArgumentException if a reference argument is null.
     * @Thread-context any thread; each endpoint is contacted sequentially.
     */
    public List<ServiceDirectoryEntry> serviceDirectory(ServiceKind kind, UUID networkId,
            int limit) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(networkId, "networkId");
        ServiceDirectoryQuery request = new ServiceDirectoryQuery(kind, networkId, limit);
        Map<NodeId, ServiceDirectoryEntry> merged = new LinkedHashMap<>();
        for (Endpoint endpoint : endpoints) {
            Optional<NoderaMessage> reply = exchange(endpoint, request);
            if (reply.isEmpty() || !(reply.get() instanceof ServiceDirectoryResponse response)) {
                continue;
            }
            for (ServiceDirectoryEntry entry : response.entries()) {
                if (!verifyServiceEntry(entry)) {
                    continue;
                }
                ServiceDirectoryEntry held = merged.get(entry.record().service());
                if (held == null
                        || entry.score().recomputedComposite()
                                > held.score().recomputedComposite()) {
                    merged.put(entry.record().service(), entry);
                }
            }
        }
        List<ServiceDirectoryEntry> rows = new ArrayList<>(merged.values());
        // Recomputed, never the transmitted composite: a tracker that inflated a favourite's number
        // must not be able to reorder this peer's failover list.
        rows.sort(SERVICE_ENTRY_ORDER);
        return List.copyOf(rows);
    }

    /**
     * Verify one directory row end to end: the signature over the record's own canonical bytes.
     *
     * @param entry the row to check.
     * @return true when the row is the service's own signed record.
     * @Thread-context any thread.
     */
    public static boolean verifyServiceEntry(ServiceDirectoryEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return SIGNATURES.verify(entry.record().publicKey(), entry.record().signedBytes(),
                entry.signature());
    }

    /**
     * Publish measured observations to every tracker.
     *
     * <p>Signed with this peer's identity so a tracker can attribute the report and cap how much one
     * identity may move a score. Unattributed reports would make scoring the cheapest attack in the
     * system: claim every rival rendezvous is dead and take over routing for the whole network.
     *
     * @param networkId    the network the measurements were taken in.
     * @param observations one row per measured service; an empty list is a no-op.
     * @param nowEpochMillis the current wall clock — trackers check it against their freshness window.
     * @return how many trackers accepted the report.
     * @throws IllegalArgumentException if a reference argument is null.
     * @Thread-context any thread.
     */
    public int reportServiceScores(UUID networkId, List<ServiceObservation> observations,
            long nowEpochMillis) {
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty() || endpoints.isEmpty()) {
            return 0;
        }
        // The signature covers everything but itself, so this placeholder is never signed over.
        ServiceScoreReport unsigned = new ServiceScoreReport(identity.nodeId(),
                identity.publicKeyBytes(), networkId, observations, nowEpochMillis, Bytes.empty());
        ServiceScoreReport report = new ServiceScoreReport(identity.nodeId(),
                identity.publicKeyBytes(), networkId, observations, nowEpochMillis,
                identity.sign(unsigned.signedPortion()));
        int accepted = 0;
        for (Endpoint endpoint : endpoints) {
            Optional<NoderaMessage> reply = exchange(endpoint, report);
            if (reply.isPresent() && reply.get() instanceof ServiceAnnounceAck ack
                    && ack.accepted()) {
                accepted++;
            }
        }
        return accepted;
    }

    /**
     * Send one request to one endpoint and decode its reply.
     *
     * <p>A UDP endpoint that does not answer is retried over TCP against the same {@code host:port}
     * before giving up. That fallback is not defensive padding: the service deliberately drops a
     * UDP answer that would exceed its size or amplification bounds, so "no datagram came back" is
     * an expected outcome for a large world's peer list — and reporting it as "no peers" would make
     * a busy world look dead precisely because it is busy.
     */
    private Optional<NoderaMessage> exchange(Endpoint endpoint, NoderaMessage request) {
        if (closed.get()) {
            return Optional.empty();
        }
        byte[] frame = WireCodec.encode(request);
        if (endpoint.transport() == Transport.UDP) {
            Optional<NoderaMessage> answered = exchangeUdp(endpoint, frame);
            if (answered.isPresent()) {
                return answered;
            }
            return exchangeTcp(endpoint.asTcp(), frame);
        }
        return exchangeTcp(endpoint, frame);
    }

    private Optional<NoderaMessage> exchangeTcp(Endpoint endpoint, byte[] frame) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()),
                    (int) connectTimeout.toMillis());
            socket.setSoTimeout((int) readTimeout.toMillis());
            // Frames caps the reply length: a hostile or broken tracker must not be able to make
            // a peer allocate a gigabyte.
            Frames.write(socket.getOutputStream(), frame);
            return Frames.read(socket.getInputStream()).map(WireCodec::decode);
        } catch (IOException | RuntimeException e) {
            // Unreachable, slow, or misbehaving trackers are an expected steady state, not an
            // error to propagate: discovery degrades, the peer keeps playing.
            return Optional.empty();
        }
    }

    /**
     * One datagram out, one datagram back. The datagram boundary is the frame, so there is no
     * length prefix — and the receive buffer is a hard bound on what a tracker can make this peer
     * hold, which is the UDP analogue of {@link Frames}' length cap.
     *
     * <p>Retried a bounded number of times: UDP may silently drop either direction, and a single
     * lost packet must not read as "this tracker knows nothing".
     */
    private Optional<NoderaMessage> exchangeUdp(Endpoint endpoint, byte[] frame) {
        if (frame.length > UDP_MAX_REQUEST_BYTES) {
            return Optional.empty(); // too large for a datagram; the caller falls back to TCP
        }
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
            socket.setSoTimeout((int) Math.max(1, readTimeout.toMillis() / UDP_ATTEMPTS));
            java.net.InetSocketAddress target =
                    new InetSocketAddress(endpoint.host(), endpoint.port());
            if (target.isUnresolved()) {
                return Optional.empty();
            }
            byte[] buffer = new byte[UDP_MAX_REPLY_BYTES];
            for (int attempt = 0; attempt < UDP_ATTEMPTS; attempt++) {
                try {
                    socket.send(new java.net.DatagramPacket(frame, frame.length, target));
                    java.net.DatagramPacket reply =
                            new java.net.DatagramPacket(buffer, buffer.length);
                    socket.receive(reply);
                    // Only accept an answer from the address we asked; an off-path spoofer that
                    // guesses the ephemeral port still has to match the source.
                    if (!reply.getAddress().equals(target.getAddress())
                            || reply.getPort() != target.getPort()) {
                        continue;
                    }
                    byte[] body = java.util.Arrays.copyOfRange(reply.getData(), reply.getOffset(),
                            reply.getOffset() + reply.getLength());
                    return Optional.of(WireCodec.decode(body));
                } catch (java.net.SocketTimeoutException retry) {
                    // Lost datagram; try again within the overall read budget.
                } catch (RuntimeException undecodable) {
                    // A datagram we cannot decode is not an answer. Stop here and let the caller
                    // fall back to TCP rather than spinning on a broken service.
                    return Optional.empty();
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Stop using this client. In-flight exchanges finish; later ones are no-ops.
     *
     * @Thread-context any thread; idempotent.
     */
    @Override
    public void close() {
        closed.set(true);
    }
}
