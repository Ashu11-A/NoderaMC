package dev.nodera.mod.common;

import dev.nodera.endpoint.config.NoderaSettings;
import dev.nodera.peer.control.CompanionClient;
import dev.nodera.peer.control.CompanionLink;
import dev.nodera.endpoint.lane.LiveRegionOwnershipProvider;
import dev.nodera.endpoint.share.ShareOptions;
import dev.nodera.endpoint.state.WorkerStateParser;
import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.diagnostics.DiagnosticsCollector;
import dev.nodera.diagnostics.metric.MessageCounters;
import dev.nodera.diagnostics.metric.TrafficMeter;
import dev.nodera.diagnostics.source.EntityControlProvider;
import dev.nodera.diagnostics.source.RegionOwnershipProvider;
import dev.nodera.peer.PeerEventListener;
import dev.nodera.peer.PeerRuntime;
import dev.nodera.peer.PeerRuntimeConfig;
import dev.nodera.peer.SessionView;
import dev.nodera.peer.metric.MeteredPeerTransport;
import dev.nodera.protocol.discovery.AnnounceEvent;
import dev.nodera.protocol.discovery.TrackerAnnounce;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import dev.nodera.transport.rendezvous.RendezvousEndpoint;
import dev.nodera.transport.rendezvous.RendezvousPeerTransport;
import dev.nodera.transport.socket.SocketPeerTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Process-wide holder for this installation's Nodera {@link PeerRuntime}(s) (Phase 6 continuity
 * beta). Bridges the NeoForge lifecycle to the Minecraft-free peer runtime:
 *
 * <ul>
 *   <li>a <b>host</b> (a player's integrated server that pressed "Share", or a dedicated server that
 *       auto-hosts) calls {@link #startHost} to spin up the host peer that joiners connect through —
 *       the role, not the dist, decides who hosts (Task 30);</li>
 *   <li>a <b>joiner</b> calls {@link #onServerSessionInfo} to spin up a player peer that dials the
 *       host's advertised P2P route and joins the mesh, so the players form a direct link that
 *       outlives the host.</li>
 * </ul>
 *
 * <p>The heavy lifting (membership, heartbeats, deterministic gateway migration) lives in
 * {@code peer-runtime} and is exercised headlessly by {@code SessionContinuityIT}; this class is
 * only the thin Minecraft-side wiring.
 *
 * <p>Thread-context: all mutators are {@code synchronized}; runtimes run on their own threads.
 */
public final class NoderaPeerService {

    private static final NoderaPeerService INSTANCE = new NoderaPeerService();
    private static final Logger LOG = LoggerFactory.getLogger("NoderaPeer");

    private NodeIdentity serverIdentity;
    private SocketPeerTransport serverTransport;
    private PeerTransport serverDataTransport;
    private PeerRuntime serverRuntime;
    private DiagnosticsCollector serverCollector;
    private dev.nodera.mod.debug.DiagnosticsService serverDiagnostics;
    private ShareOptions hostOptions;

    // Task 30: the shared world's identity + host capabilities, used to announce to the tracker and
    // register with the rendezvous service. worldId is an INTERIM placeholder (a stable hash of the
    // save name) until the live genesis lane (Task 9/30c) produces the real GenesisManifest hash.
    private Bytes hostWorldId;
    private String hostWorldName;
    /**
     * The world id of the session this client joined, learned from the server's session payload
     * (worker L-41). Blank while single-player, or on a socket-only join that carried no id.
     */
    private volatile String sessionWorldIdHex = "";
    private NodeCapabilities hostCaps;
    private ScheduledExecutorService announceScheduler;
    /** The Minecraft game endpoint ({@code host:port}) announced while the game server is open. */
    private String gameRoute;

    private dev.nodera.peer.discovery.TrackerClient serverTrackerClient;

    /** Per-peer throughput attribution for the host lane (the `/nodera peers` + HUD columns). */
    private final dev.nodera.peer.metric.PeerTrafficMeter serverPeerMeter =
            new dev.nodera.peer.metric.PeerTrafficMeter();
    /** Per-peer throughput attribution for the joiner lane. */
    private final dev.nodera.peer.metric.PeerTrafficMeter clientPeerMeter =
            new dev.nodera.peer.metric.PeerTrafficMeter();

    /**
     * The host's rendezvous transport, held separately from {@link #serverDataTransport} because
     * that field holds the metering wrapper and {@code MeteredPeerTransport} exposes no delegate.
     * This is the handle {@link #refreshRendezvousEndpoints()} needs to replace a relay list without
     * reopening the world (L-84).
     */
    private volatile RendezvousPeerTransport serverRendezvous;

    private NodeIdentity clientIdentity;

    /**
     * Base64 of the {@code SessionDelegation} this session announces, or {@code ""}.
     *
     * <p>{@link #clientIdentity} is deliberately a throwaway: a fresh keypair per session, because
     * it is a transport credential and nothing more. That leaves a gap the permission model cannot
     * bridge on its own — a world's author and every grant it issued are anchored to the persistent
     * key inside the player's always-on worker, so a session announcing only its own key is a
     * stranger to every world it has ever been given anything in. This is the worker's signed
     * statement that the two belong together, and it is what stops a world's creator from being
     * de-opped on joining their own world.
     */
    private volatile String sessionDelegationB64 = "";

    private SocketPeerTransport clientTransport;
    private PeerTransport clientDataTransport;
    /** The joiner's rendezvous transport; see {@link #serverRendezvous}. */
    private volatile RendezvousPeerTransport clientRendezvous;
    private PeerRuntime clientRuntime;
    private DiagnosticsCollector clientCollector;
    private dev.nodera.peer.discovery.TrackerClient clientTrackerClient;
    /**
     * The session bootstrap this joiner dialed — the hosting game's P2P route.
     *
     * <p>Kept because the joiner's own always-on worker needs it: it is the address the worker
     * dials to become a MEMBER of the world its player is playing in ({@link
     * #bindCompanionToSession}).
     */
    private volatile String clientBootstrapRoute = "";
    /**
     * The (route, seed) pair the local companion is currently bound to, or {@code ""} when it is
     * detached. Guards the re-plan cadence: a plan is broadcast on every region-boundary crossing,
     * and re-issuing {@code MESH} for a binding that has not changed would re-announce the worker
     * several times a minute for no gain — and a re-bind of the validation lane is refused outright
     * while regions are active, so the retry would only log an error.
     */
    private volatile String companionBinding = "";

    private NoderaPeerService() {}

    /**
     * Build a {@link dev.nodera.peer.discovery.TrackerClient} from configured {@code host:port}
     * routes (Task 28).
     *
     * <p>Malformed routes are skipped with a loud log rather than aborting startup: one typo in a
     * config file must not stop a server from booting, and the config spec already rejects them at
     * load time — this is the belt to that suspenders.
     *
     * @param routes   the configured endpoints.
     * @param identity the peer identity that will sign announces.
     * @return the client (possibly with no endpoints, which makes it a no-op).
     */
    private static dev.nodera.peer.discovery.TrackerClient trackerClient(
            java.util.List<? extends String> routes, NodeIdentity identity) {
        java.util.List<dev.nodera.peer.discovery.TrackerClient.Endpoint> endpoints =
                new java.util.ArrayList<>();
        for (String route : routes) {
            try {
                endpoints.add(dev.nodera.peer.discovery.TrackerClient.Endpoint.parse(route));
            } catch (IllegalArgumentException e) {
                LOG.warn("Ignoring malformed tracker endpoint '{}': {}", route, e.getMessage());
            }
        }
        return new dev.nodera.peer.discovery.TrackerClient(endpoints, identity);
    }

    /** @return the singleton service for this JVM. */
    public static NoderaPeerService get() {
        return INSTANCE;
    }

    /**
     * Start the host peer for this world (Task 30). Called by a dedicated server that auto-hosts, or
     * by the pause-menu "Share" action on a player's integrated server — the role, not the dist,
     * decides who calls this. Idempotent: re-calling while already hosting keeps the existing runtime
     * and only refreshes the share options.
     *
     * <p>Beyond starting the peer, this announces the world to the configured tracker(s) (so peers
     * can discover it, Task 28) and registers a signed record with the configured rendezvous
     * service(s) (so peers can reach it across NATs, Task 29). Both engage automatically from the
     * embedded default endpoints ({@link NoderaConfig#DEFAULT_TRACKER_ENDPOINTS} /
     * {@link NoderaConfig#DEFAULT_RENDEZVOUS_ENDPOINTS}); an unreachable service degrades to
     * direct/no-announce rather than failing the share.
     *
     * @param bindHost      local bind address.
     * @param port          local P2P port.
     * @param advertiseHost host that joiners dial ({@code "auto"} → best local non-loopback address).
     * @param options       the share options (password, delegation, visibility); never {@code null}.
     * @param worldId       the world identity used to key tracker/rendezvous (interim placeholder
     *                      until the live genesis hash, Task 9/30c); may be {@code null}.
     * @param worldName     the world's display name for the tracker directory.
     * @return the advertised host route ({@code host:port}); {@code null} if the P2P socket bind
     *         failed and even an ephemeral-port retry failed (issue #39) — the world then runs in
     *         vanilla-only mode rather than crashing the server.
     */
    public synchronized String startHost(String bindHost, int port, String advertiseHost,
                                          ShareOptions options, Bytes worldId, String worldName) {
        return startHost(bindHost, port, advertiseHost, options, worldId, worldName,
                NodeIdentity.generate());
    }

    /** Start the host with a save-persistent identity used by durable validation records. */
    public synchronized String startHost(
            String bindHost, int port, String advertiseHost, ShareOptions options,
            Bytes worldId, String worldName, NodeIdentity identity) {
        this.hostOptions = options == null ? ShareOptions.dedicatedDefault() : options;
        if (serverRuntime != null) {
            return serverRuntime.selfRoute();
        }
        if (identity == null) {
            throw new IllegalArgumentException("host identity must not be null");
        }
        serverIdentity = identity;
        this.hostWorldId = worldId;
        this.hostWorldName = worldName == null ? "" : worldName;
        // The host is the world's FULL_ARCHIVE peer + bootstrap + a one-vote validator (Task 26
        // semantics). The tracker honours a world's display name only from a FULL_ARCHIVE host.
        this.hostCaps = NodeCapabilities.initial().withRoles(
                EnumSet.of(PeerRole.FULL_ARCHIVE, PeerRole.BOOTSTRAP, PeerRole.REGION_VALIDATOR));

        String advertise = resolveHost(advertiseHost);
        // Issue #39: a P2P-port bind failure (port already in use — two dev clients sharing the
        // default 25566, a stale JVM, etc.) MUST NOT crash the integrated server. bootHostTransport
        // throws an unchecked TransportException on bind; catch it, retry once on an ephemeral port
        // (the joiner already binds 0 at onServerSessionInfo), and only if that too fails (or the
        // failure is not a port collision) degrade to vanilla-only (return null) — mirroring
        // openGameServer's "keep the server, drop the feature" contract.
        try {
            return bootHostTransport(bindHost, port, advertise, worldId);
        } catch (RuntimeException e) {
            resetHostTransport();
            if (port != 0 && isBindFailure(e)) {
                LOG.warn("Nodera: P2P bind on {}:{} busy ({}); retrying on an ephemeral port",
                        bindHost, port, e.getMessage());
                try {
                    String route = bootHostTransport(bindHost, 0, advertise, worldId);
                    LOG.warn("Nodera: host peer bound ephemeral route {} (configured p2p.port {} busy)",
                            route, port);
                    return route;
                } catch (RuntimeException e2) {
                    LOG.error("Nodera: P2P bind failed even on an ephemeral port ({}); world NOT shared "
                            + "on the Nodera network — vanilla server continues. Change p2p.port or free "
                            + "the port and retry Share.", e2.getMessage());
                }
            } else {
                LOG.error("Nodera: host peer start failed ({}); world NOT shared on the Nodera network "
                        + "— vanilla server continues.", e.getMessage());
            }
            resetHostState();
            return null;
        }
    }

    /**
     * Build + start the host transport/runtime and begin announcing — the body of {@link #startHost}
     * that can throw on a bind failure. Extracted so {@code startHost} can retry on an ephemeral port
     * and degrade without crashing its caller (issue #39).
     *
     * @return the advertised host route.
     * @throws dev.nodera.transport.TransportException if the P2P socket bind fails.
     */
    private String bootHostTransport(String bindHost, int port, String advertise, Bytes worldId) {
        // Authenticated mode (issue #41 / L-53): connections prove key possession at accept.
        serverTransport = new SocketPeerTransport(serverIdentity, bindHost, port, advertise);
        TrafficMeter serverMeter = new TrafficMeter();
        MessageCounters serverCounts = new MessageCounters();

        // Direct socket, optionally wrapped in the rendezvous transport so the host registers a
        // signed record and stays reachable across NATs (Task 29). The socket is the LAN path and the
        // self-route source either way.
        PeerTransport dataTransport = composeHostTransport(worldId);
        MeteredPeerTransport serverMetered = new MeteredPeerTransport(dataTransport, serverMeter,
                serverPeerMeter);
        serverDataTransport = serverMetered;
        serverRuntime = PeerRuntime.bootstrap(serverIdentity, hostCaps,
                serverMetered, serverTransport::listenRoute, PeerRuntimeConfig.defaults(),
                new LoggingListener("host"), serverCounts);
        serverRuntime.setLocalProfile(negotiationProfile(hostCaps));
        serverCollector = new DiagnosticsCollector(serverMeter, serverCounts)
                .register(serverRuntime)
                .register(dev.nodera.endpoint.lane.LiveRegionOwnershipProvider.get())
                .register(dev.nodera.mod.server.entity.LiveEntityControlProvider.get());
        serverDiagnostics = new dev.nodera.mod.debug.DiagnosticsService(serverRuntime, serverCollector);

        // Announce to the tracker (Task 28) and keep re-announcing on its cadence.
        serverTrackerClient = trackerClient(
                selectedTrackerRoutes(NoderaSettings.current().trackerEndpoints()), serverIdentity);
        if (!serverTrackerClient.endpoints().isEmpty()) {
            LOG.info("Nodera tracker endpoints: {}", serverTrackerClient.endpoints());
            startAnnouncing();
        } else {
            LOG.info("Nodera: no tracker endpoints configured — world not announced");
        }

        String route = serverRuntime.selfRoute();
        LOG.info("Nodera host peer online at {} (node {}, world '{}', encryption={})",
                route, serverIdentity.nodeId(), hostWorldName, this.hostOptions.encryptionEnabled());
        // Promote the companion worker from a private daemon into a MEMBER of this world's
        // session. That is what stops a world's peer set from being "whoever is logged in": the
        // worker then counts toward session quorum, is eligible for the committee seats the lane
        // plan hands out, and can win the gateway election and keep the session alive when this
        // game exits. Best-effort and self-catching — a world must still host when no companion is
        // linked, and this runs on a path reachable from server lifecycle events, where an escaping
        // exception would take the integrated server down with it.
        try {
            CompanionClient companion = CompanionLink.client();
            if (companion != null && route != null && !route.isEmpty()) {
                companion.mesh(route, null).ifPresent(err -> LOG.warn(
                        "Nodera: companion worker did not join the world session: {}", err));
            }
        } catch (RuntimeException e) {
            LOG.warn("Nodera: companion session handoff failed: {}", e.toString());
        }
        return route;
    }

    /**
     * What this build validates under, for the handshake (R2, network L-87).
     *
     * <p>Named from {@code FlatWorldRules} because that is the rule set the engine actually
     * re-executes with, on both the host and the joiner lanes. A profile naming anything else would
     * make the handshake's answer wrong in the one direction that matters — reporting agreement with
     * a peer this node cannot in fact agree a state root with.
     */
    private static dev.nodera.protocol.session.Negotiation.LocalProfile negotiationProfile(
            NodeCapabilities caps) {
        return dev.nodera.protocol.session.Negotiation.LocalProfile.of(
                dev.nodera.core.NoderaConstants.PRODUCT_VERSION,
                dev.nodera.simulation.rules.FlatWorldRules.RULES_VERSION,
                dev.nodera.simulation.rules.FlatWorldRules.registryFingerprint(),
                dev.nodera.protocol.service.ServiceRecord.DEFAULT_NETWORK, caps);
    }

    /** Borrowed host I/O for live lanes; lifecycle remains owned by this service. */
    public synchronized HostContext hostContext() {
        return serverRuntime == null
                ? null : new HostContext(serverIdentity, serverDataTransport, serverRuntime);
    }

    public record HostContext(
            NodeIdentity identity, PeerTransport transport, PeerRuntime runtime) {
    }

    /**
     * Wrap the direct socket in the rendezvous transport when endpoints are configured (Task 29), so
     * the host registers a discoverable, NAT-reachable record. Falls back to the direct socket if the
     * rendezvous service is unreachable — a down relay must never stop a LAN/direct share.
     */
    private PeerTransport composeHostTransport(Bytes worldId) {
        java.util.List<? extends String> routes =
                selectedRendezvousRoutes(NoderaSettings.current().rendezvousEndpoints());
        if (routes == null || routes.isEmpty() || worldId == null) {
            return serverTransport;
        }
        List<RendezvousEndpoint> endpoints = new ArrayList<>();
        for (String route : routes) {
            try {
                endpoints.add(RendezvousEndpoint.parse(route));
            } catch (IllegalArgumentException e) {
                LOG.warn("Ignoring malformed rendezvous endpoint '{}': {}", route, e.getMessage());
            }
        }
        if (endpoints.isEmpty()) {
            return serverTransport;
        }
        UUID networkId = UUID.nameUUIDFromBytes(worldId.toArray());
        RendezvousPeerTransport rendezvous = new RendezvousPeerTransport(
                serverIdentity, endpoints, networkId, worldId, hostCaps, serverTransport);
        try {
            rendezvous.start(); // registers the signed record; also starts the direct socket
            LOG.info("Nodera rendezvous: host registered with {} (network {})", endpoints, networkId);
            // Held so a later relay change can be pushed in without reopening the world (L-84).
            this.serverRendezvous = rendezvous;
            return rendezvous;
        } catch (RuntimeException e) {
            // Issue #39: only degrade to "direct socket only" when the direct socket actually came up
            // — i.e. the rendezvous RELAY failed, not the bind. rendezvous.start() binds the direct
            // socket first (RendezvousPeerTransport.start), so if the bind itself failed the socket
            // is NOT listening (listenRoute null) and returning it would make PeerRuntime.start rebind
            // uncaught — the "deferred crash". Rethrow so startHost's catch handles it uniformly.
            if (serverTransport.listenRoute() != null) {
                LOG.warn("Nodera rendezvous unreachable ({}); using direct socket only", e.getMessage());
                return serverTransport;
            }
            throw e;
        }
    }

    /**
     * Wrap the joiner's direct socket in the rendezvous transport when the world identity and
     * endpoints are both known, mirroring {@link #composeHostTransport}. Falls back to the bare
     * socket for a socket-only join or an unreachable rendezvous — a down relay must never stop a
     * LAN/direct join, exactly as on the host side.
     */
    private PeerTransport composeClientTransport(String worldIdHex) {
        if (worldIdHex == null || worldIdHex.isBlank()) {
            return clientTransport;
        }
        java.util.List<? extends String> routes =
                selectedRendezvousRoutes(NoderaSettings.current().clientRendezvousEndpoints());
        if (routes == null || routes.isEmpty()) {
            return clientTransport;
        }
        Bytes worldId;
        try {
            worldId = Bytes.fromHex(worldIdHex.trim());
        } catch (RuntimeException malformed) {
            return clientTransport;
        }
        List<RendezvousEndpoint> endpoints = new ArrayList<>();
        for (String route : routes) {
            try {
                endpoints.add(RendezvousEndpoint.parse(route));
            } catch (IllegalArgumentException e) {
                LOG.warn("Ignoring malformed rendezvous endpoint '{}': {}", route, e.getMessage());
            }
        }
        if (endpoints.isEmpty()) {
            return clientTransport;
        }
        UUID networkId = UUID.nameUUIDFromBytes(worldId.toArray());
        RendezvousPeerTransport rendezvous = new RendezvousPeerTransport(
                clientIdentity, endpoints, networkId, worldId, NodeCapabilities.initial(),
                clientTransport);
        try {
            rendezvous.start();
            LOG.info("Nodera rendezvous: joiner registered with {} (network {})", endpoints, networkId);
            this.clientRendezvous = rendezvous;
            return rendezvous;
        } catch (RuntimeException e) {
            // Same discipline as the host path: degrade to the direct socket only when the socket
            // itself came up. A joiner binds an ephemeral port, so a bind failure here is a real
            // fault worth surfacing rather than silently swallowing.
            if (clientTransport.listenRoute() != null) {
                LOG.warn("Nodera rendezvous unreachable ({}); joining over the direct socket only",
                        e.getMessage());
                return clientTransport;
            }
            throw e;
        }
    }

    /**
     * The rendezvous routes to actually use: the worker's live selection when there is one,
     * otherwise the configured list (L-84).
     *
     * <p>The worker discovers relays from trackers, probes them, scores them on its own
     * measurements, and re-picks whenever one drains. The mod has none of that machinery and does
     * not need it — the companion is already running, already asked, and its answer is the better
     * one. Configuration remains the fallback and not a second-class one: a player with no companion
     * linked, an older worker whose STATE has no {@code rendezvous} array, or a LAN-only deployment
     * with no tracker to ask must all still be able to share a world.
     *
     * <p>Self-catching on purpose. This runs on a path reachable from {@code ServerStartedEvent},
     * where NeoForge's bus does not isolate a listener exception and an escaping one takes the
     * integrated server down. A companion that is absent, slow, or answering nonsense costs the
     * player the better relay list; it must never cost them the world.
     *
     * @param configured the configured fallback.
     * @return the routes to compose the transport from.
     */
    /**
     * The tracker routes to use: the worker's live list when there is one, otherwise the configured
     * list.
     *
     * <p>Split-brain here is not a degraded mode, it is an invisible outage. If the mod announces a
     * world to tracker A while every other peer's worker queries tracker B, the world is live and
     * undiscoverable — and the joiner, finding no route, re-hosts a stale copy from the archive
     * instead. That is how one world silently becomes two. Following the worker makes the two lists
     * incapable of disagreeing.
     */
    private java.util.List<? extends String> selectedTrackerRoutes(
            java.util.List<? extends String> configured) {
        return selectedRoutes(configured, WorkerStateParser::trackerRoutes, "tracker");
    }

    private java.util.List<? extends String> selectedRendezvousRoutes(
            java.util.List<? extends String> configured) {
        return selectedRoutes(configured, WorkerStateParser::rendezvousRoutes, "rendezvous");
    }

    /** Shared body: ask the companion, fall back to configuration, never throw. */
    private java.util.List<? extends String> selectedRoutes(
            java.util.List<? extends String> configured,
            java.util.function.Function<String, List<String>> read,
            String what) {
        try {
            CompanionClient companion = CompanionLink.client();
            if (companion != null) {
                List<String> live = companion.state().map(read).orElse(List.of());
                if (!live.isEmpty()) {
                    if (!live.equals(configured)) {
                        // Said at INFO, with both lists, because this is the difference between a
                        // world everyone can see and a world only its host can.
                        LOG.info("Nodera {}: following the worker's list {} (configured: {})",
                                what, live, configured);
                    }
                    return live;
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Nodera: could not read the worker's {} list ({}); using the configured list",
                    what, e.toString());
        }
        return configured;
    }

    /**
     * Push the worker's current rendezvous selection into whichever transports are live (L-84).
     *
     * <p>This is the half that makes a mid-session migration reach the game. The worker learns a
     * relay is draining from a signed notice on its own control channel and moves within seconds;
     * without this the in-game transport keeps talking to the relay that is leaving until the world
     * is reopened. {@code RendezvousPeerTransport.setEndpoints} re-registers immediately and ignores
     * an empty list, so a worker that has gone quiet leaves the current selection in place rather
     * than clearing it.
     *
     * <p>Called on the announce cadence rather than on a push, because the worker's selection is
     * already the debounced, swept result of its own minute-long cycle — polling a value that
     * changes at most once a minute is not worth a second control channel.
     */
    private void refreshRendezvousEndpoints() {
        RendezvousPeerTransport host = this.serverRendezvous;
        RendezvousPeerTransport joiner = this.clientRendezvous;
        if (host == null && joiner == null) {
            return;
        }
        List<String> live;
        try {
            CompanionClient companion = CompanionLink.client();
            if (companion == null) {
                return;
            }
            live = companion.state().map(WorkerStateParser::rendezvousRoutes).orElse(List.of());
        } catch (RuntimeException e) {
            LOG.debug("Nodera: rendezvous refresh skipped ({})", e.toString());
            return;
        }
        if (live.isEmpty()) {
            return;
        }
        List<RendezvousEndpoint> endpoints = new ArrayList<>();
        for (String route : live) {
            try {
                endpoints.add(RendezvousEndpoint.parse(route));
            } catch (IllegalArgumentException e) {
                LOG.warn("Ignoring malformed rendezvous endpoint '{}' from the worker: {}",
                        route, e.getMessage());
            }
        }
        if (endpoints.isEmpty()) {
            return;
        }
        if (host != null) {
            host.setEndpoints(endpoints);
        }
        if (joiner != null) {
            joiner.setEndpoints(endpoints);
        }
    }

    /** Send the initial STARTED announce and refresh on the tracker's cadence — all off the lock. */
    private void startAnnouncing() {
        int interval = Math.max(15, serverTrackerClient.announceIntervalSeconds());
        announceScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nodera-tracker-announce");
            t.setDaemon(true);
            return t;
        });
        announceScheduler.scheduleWithFixedDelay(
                () -> {
                    sendAnnounce(AnnounceEvent.STARTED);
                    // Same cadence, same thread: the relay list the worker chose is pushed into the
                    // live transport so a mid-session migration reaches the game (L-84).
                    //
                    // Guarded separately because a task that throws out of scheduleWithFixedDelay is
                    // silently never run again — the announce would stop too, and nothing would say
                    // so. The refresh is the newer and less-proven half; it must not be able to take
                    // the announce with it.
                    try {
                        refreshRendezvousEndpoints();
                    } catch (RuntimeException e) {
                        LOG.warn("Nodera: rendezvous refresh failed ({}); the announce continues",
                                e.toString());
                    }
                },
                0, interval, TimeUnit.SECONDS);
    }

    /** Build + send a signed tracker announce for the shared world. Never throws. */
    private void sendAnnounce(AnnounceEvent event) {
        dev.nodera.peer.discovery.TrackerClient tracker;
        Bytes worldId;
        String worldName;
        NodeCapabilities caps;
        String route;
        String game;
        synchronized (this) {
            if (serverTrackerClient == null || serverIdentity == null || hostWorldId == null) {
                return;
            }
            tracker = serverTrackerClient;
            worldId = hostWorldId;
            worldName = hostWorldName;
            caps = hostCaps;
            route = serverRuntime == null ? null : serverRuntime.selfRoute();
            game = gameRoute;
        }
        try {
            List<String> routes = new ArrayList<>(2);
            if (route != null) {
                routes.add(route);
            }
            // The game endpoint rides as an extra route claim ("mc/host:port"); the tracker's
            // routes query serves it to joiners while the P2P PeerEntry skips the mc/ form.
            if (game != null && !game.isBlank()) {
                routes.add("mc/" + game);
            }
            TrackerAnnounce announce = tracker.buildAnnounce(
                    worldId, event, routes, caps, List.of(), worldName,
                    0L, 10_000, System.currentTimeMillis());
            int acks = tracker.announce(announce).size();
            LOG.info("Nodera tracker announce {} for '{}' → {} endpoint ack(s)", event, worldName, acks);
        } catch (RuntimeException e) {
            LOG.warn("Nodera tracker announce {} failed: {}", event, e.getMessage());
        }
    }

    /**
     * Record (or clear, with {@code null}) the Minecraft game endpoint joiners connect to. The
     * next tracker announce carries the change.
     *
     * @param route the {@code host:port} of the open game server, or {@code null} once closed.
     */
    public synchronized void setGameRoute(String route) {
        this.gameRoute = route;
    }

    /** @return the announced Minecraft game endpoint, or {@code null} while the game is closed. */
    public synchronized String gameRoute() {
        return gameRoute;
    }

    /** @return the current host route to advertise to joiners, or {@code null} if not hosting. */
    public synchronized String hostRoute() {
        return serverRuntime == null ? null : serverRuntime.selfRoute();
    }

    /** @return whether this installation is currently hosting a world on the network (Task 30). */
    public synchronized boolean isHosting() {
        return serverRuntime != null;
    }

    /**
     * The world this process is currently part of, whichever side of it this process is on
     * (worker L-41).
     *
     * <p>Hosting is the stronger claim and wins: a host knows its own world id outright, while a
     * joiner only knows what the session payload told it. Blank means there is no world identity to
     * file content under — single-player without sharing, or a socket-only join — and a caller that
     * needs one must do nothing rather than invent one.
     *
     * @return the hex world id, or {@code ""}.
     */
    public String currentWorldIdHex() {
        Bytes hosted = hostWorldId;
        if (hosted != null) {
            return hosted.toHex();
        }
        return sessionWorldIdHex;
    }

    /** @return the active share options while hosting, or {@code null} if not hosting. */
    public synchronized ShareOptions hostOptions() {
        return serverRuntime == null ? null : hostOptions;
    }

    /**
     * Replace the live share options without re-hosting — the re-key path (L-51).
     *
     * <p>A successful password re-key deliberately does not re-`activate` (that would re-seed a
     * plaintext archive over the fresh ciphertext), so nothing else would update the options this
     * service reports. Leaving them stale means the next change compares against a password the
     * world no longer has, and the live-join gate keeps testing the old one.
     *
     * @param options the options now in force; ignored when not hosting or null.
     * @Thread-context any thread.
     */
    public synchronized void updateHostOptions(ShareOptions options) {
        if (serverRuntime != null && options != null) {
            this.hostOptions = options;
        }
    }

    /** @return the server-side diagnostics HUD driver, or {@code null} before the server starts. */
    public synchronized dev.nodera.mod.debug.DiagnosticsService serverDiagnostics() {
        return serverDiagnostics;
    }

    /** @return the client-side runtime, or {@code null} if not meshed. */
    /** @return the client peer's identity, or {@code null} when no session is joined. */
    public synchronized NodeIdentity clientIdentity() {
        return clientIdentity;
    }

    /**
     * @return the route this client dialled to reach the session's host, or {@code ""}. The one
     *         thing every joiner agrees on about who is hosting, which is what the succession
     *         election needs in order to exclude the departing host identically on every peer.
     * @Thread-context any thread.
     */
    public String clientBootstrapRoute() {
        return clientBootstrapRoute;
    }

    /**
     * @return base64 of this session's {@code SessionDelegation}, or {@code ""} when no worker was
     *         reachable to sign one. Blank is not an error: the session then announces exactly what
     *         it announced before delegations existed, and is evaluated as an ordinary member.
     * @Thread-context any thread.
     */
    public String sessionDelegationB64() {
        return sessionDelegationB64;
    }

    /** @return the client peer's metered transport, or {@code null} when no session is joined. */
    public synchronized PeerTransport clientDataTransport() {
        return clientDataTransport;
    }

    public synchronized PeerRuntime clientRuntime() {
        return clientRuntime;
    }

    /** @return the client-side diagnostics collector (for {@code /noderac}), or {@code null}. */
    public synchronized DiagnosticsCollector clientCollector() {
        return clientCollector;
    }

    /**
     * @return the server-side tracker client (announce + query), or {@code null} before start.
     * @Thread-context any thread.
     */
    public synchronized dev.nodera.peer.discovery.TrackerClient serverTrackerClient() {
        return serverTrackerClient;
    }

    /**
     * Tear down the host transport/runtime/tracker/scheduler left by a failed {@link #bootHostTransport}
     * attempt (issue #39), without the STOPPED announce or the "shutting down" log of
     * {@link #stopHosting}. Keeps the host identity/world/caps so an ephemeral-port retry can reuse
     * them. Idempotent and never throws — a cleanup path must not pile a second failure on the first.
     */
    private synchronized void resetHostTransport() {
        if (announceScheduler != null) {
            announceScheduler.shutdownNow();
            announceScheduler = null;
        }
        if (serverTrackerClient != null) {
            try {
                serverTrackerClient.close();
            } catch (RuntimeException ignored) {
                // best-effort cleanup
            }
            serverTrackerClient = null;
        }
        if (serverRuntime != null) {
            try {
                serverRuntime.stop();
            } catch (RuntimeException ignored) {
                // best-effort cleanup
            }
            serverRuntime = null;
        }
        if (serverTransport != null) {
            try {
                serverTransport.stop();
            } catch (RuntimeException ignored) {
                // best-effort cleanup
            }
            serverTransport = null;
        }
        serverDataTransport = null;
        serverRendezvous = null;
        serverCollector = null;
        serverDiagnostics = null;
    }

    /** Full host-state reset (after {@link #resetHostTransport}) so {@link #isHosting()} is false. */
    private synchronized void resetHostState() {
        resetHostTransport();
        serverIdentity = null;
        hostWorldId = null;
        hostWorldName = null;
        hostCaps = null;
        hostOptions = null;
        gameRoute = null;
    }

    /**
     * Does this throwable represent a socket bind failure (port in use / non-local address)? Walks
     * the cause chain so the rendezvous wrapper's {@code TransportException("failed to start
     * rendezvous transport", bindException)} is still recognised. Package-private + static so the
     * retry/degrade decision is unit-testable without the NeoForge runtime (issue #39).
     */
    static boolean isBindFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.net.BindException) {
                return true;
            }
            String msg = c.getMessage();
            if (msg != null && (msg.contains("failed to bind") || msg.contains("Address already in use"))) {
                return true;
            }
        }
        return false;
    }

    /** Stop hosting this world (server stopping, or the "Stop sharing" action). Idempotent. */
    public void stopHosting() {
        // Snapshot under the monitor, then do every slow thing OUTSIDE it.
        //
        // This method used to hold the singleton monitor across a two-second join, a three-second
        // companion detach and a two-second runtime stop — on the SERVER thread, which is the thread
        // the "Saving world" screen waits for. Worse, `isHosting()` and `hostOptions()` need the
        // same monitor, so the shutdown path queued behind anything already holding it: a
        // `startHost` retry or an `onServerSessionInfo` dial can hold it for minutes, and the player
        // watched "Saving world" for all of it.
        //
        // Nulling the fields first is what makes the wait unnecessary rather than merely shorter:
        // `isHosting()` goes false immediately, so nothing else on the shutdown path blocks, and the
        // teardown below runs against a private snapshot that cannot be seen half-done.
        dev.nodera.peer.discovery.TrackerClient tracker;
        PeerRuntime runtime;
        java.util.concurrent.ScheduledExecutorService announces;
        boolean announceStopped;
        synchronized (this) {
            announces = announceScheduler;
            announceScheduler = null;
            tracker = serverTrackerClient;
            runtime = serverRuntime;
            announceStopped = tracker != null && !tracker.endpoints().isEmpty()
                    && serverIdentity != null && hostWorldId != null;
            serverRuntime = null;
            serverTrackerClient = null;
        }
        if (announces != null) {
            announces.shutdownNow();
        }
        stopHostingOutsideTheLock(tracker, runtime, announceStopped);
        synchronized (this) {
            clearHostState();
        }
    }

    /** The slow half of {@link #stopHosting}, deliberately not holding the singleton monitor. */
    private void stopHostingOutsideTheLock(
            dev.nodera.peer.discovery.TrackerClient tracker, PeerRuntime runtime,
            boolean announceStopped) {
        // Fire and forget. The tracker entry expires on its own, and a goodbye nobody waits for is
        // worth exactly as much as one somebody waits two seconds for — which is what the previous
        // version did, while holding the monitor the goodbye itself needed.
        if (announceStopped) {
            Thread.ofPlatform().name("nodera-tracker-stopped").daemon()
                    .start(() -> sendAnnounce(AnnounceEvent.STOPPED));
        }
        if (runtime != null) {
            LOG.info("Nodera host peer shutting down");
            // Off-thread for the same reason the JOINER's detach already is: it is a control
            // exchange with its own timeouts, and the host path never got the same treatment.
            Thread.ofPlatform().name("nodera-companion-detach").daemon().start(() -> {
                try {
                    CompanionClient companion = CompanionLink.client();
                    if (companion != null) {
                        companion.mesh("", null);
                    }
                } catch (RuntimeException ignored) {
                    // Teardown is best-effort; never let it block the shutdown path.
                }
            });
            runtime.stop();
        }
        if (tracker != null) {
            tracker.close();
        }
    }

    /** The old body, kept for the fields that must be cleared under the monitor. */
    private synchronized void clearHostState() {
        if (announceScheduler != null) {
            announceScheduler.shutdownNow();
            announceScheduler = null;
        }
        serverCollector = null;
        serverDiagnostics = null;
        serverTransport = null;
        serverDataTransport = null;
        serverRendezvous = null;
        serverIdentity = null;
        hostOptions = null;
        hostWorldId = null;
        hostWorldName = null;
        hostCaps = null;
        gameRoute = null;
    }

    /**
     * Client callback: the server told us its P2P route via {@link NoderaSessionPayload}; join the
     * mesh. Idempotent (a re-login while still connected is a no-op).
     *
     * @param bootstrapRoute the server's advertised P2P route.
     * @param advertiseHost  this client's advertise host ({@code "auto"} → best local address).
     */
    public synchronized void onServerSessionInfo(String bootstrapRoute, String advertiseHost) {
        onServerSessionInfo(bootstrapRoute, advertiseHost, null);
    }

    /**
     * As {@link #onServerSessionInfo(String, String)}, additionally joining the world's rendezvous
     * namespace so this joiner is discoverable and can fall back to a relay circuit when the direct
     * socket cannot be established (Task 29 / RENDEZVOUS.md §10.2).
     *
     * @param worldIdHex the world being joined, or {@code null}/blank for a socket-only join.
     */
    public synchronized void onServerSessionInfo(String bootstrapRoute, String advertiseHost,
                                                 String worldIdHex) {
        if (clientRuntime != null) {
            return;
        }
        // Remember which world this session belongs to. A committed region snapshot carries a
        // region id, which is a coordinate; seeding one to the worker needs the world's identity,
        // and this payload is where a joiner learns it (worker L-41).
        this.sessionWorldIdHex = worldIdHex == null ? "" : worldIdHex.trim();
        this.clientBootstrapRoute = bootstrapRoute == null ? "" : bootstrapRoute.trim();
        clientIdentity = NodeIdentity.generate();
        // Immediately ask this machine's worker to vouch for the key we just generated. The key
        // stays throwaway — it is a transport credential — but the announce that carries it now
        // also carries the worker's signature saying whose authority it speaks with. Without this
        // step the announce is cryptographically perfect and semantically anonymous, which is how a
        // world's own creator lost /op the moment they joined their own world as a client.
        sessionDelegationB64 = mintSessionDelegation(this.sessionWorldIdHex, clientIdentity);
        String advertise = resolveHost(advertiseHost);
        // Authenticated mode (issue #41 / L-53): connections prove key possession at accept.
        clientTransport = new SocketPeerTransport(clientIdentity, "0.0.0.0", 0, advertise);
        TrafficMeter clientMeter = new TrafficMeter();
        MessageCounters clientCounts = new MessageCounters();
        PeerTransport clientData = composeClientTransport(worldIdHex);
        MeteredPeerTransport clientMetered = new MeteredPeerTransport(clientData, clientMeter,
                clientPeerMeter);
        clientDataTransport = clientMetered;
        PeerAddress bootstrapAddress = PeerAddress.of(null, bootstrapRoute); // socket routes by host:port
        clientRuntime = PeerRuntime.peer(clientIdentity, NodeCapabilities.initial(),
                clientMetered, clientTransport::listenRoute, bootstrapAddress,
                PeerRuntimeConfig.defaults(),
                // L-17 / #35: the handover has to hear onGatewayChanged to freeze this player's
                // submit path, and the log line still has to hear it too. The runtime takes one
                // listener, so both ride a composite; a fault in either is contained there.
                new dev.nodera.peer.CompositePeerEventListener(
                        new LoggingListener("client"), clientGatewayListener()),
                clientCounts);
        clientRuntime.setLocalProfile(negotiationProfile(NodeCapabilities.initial()));
        clientTrackerClient = trackerClient(
                selectedTrackerRoutes(NoderaSettings.current().clientTrackerEndpoints()), clientIdentity);
        clientCollector = new DiagnosticsCollector(clientMeter, clientCounts)
                .register(clientRuntime)
                .register(dev.nodera.endpoint.lane.LiveRegionOwnershipProvider.get())
                .register(dev.nodera.mod.server.entity.LiveEntityControlProvider.get());
        LOG.info("Nodera client peer joining session via {} (node {}, listening {})",
                bootstrapRoute, clientIdentity.nodeId(), clientRuntime.selfRoute());
    }

    /**
     * Ask the local worker to sign a delegation binding this session's key to the worker's own.
     *
     * <p>Best-effort by construction. No worker, an older worker that does not know the verb, or a
     * session with no world id all produce {@code ""}, and the announce then says only what the
     * session can prove about itself. That is the pre-existing behaviour rather than a new failure
     * mode — the player is a member of the world instead of whatever they were granted, which is
     * wrong but survivable, and the log line says which of the two happened.
     *
     * @param worldIdHex the world this session belongs to.
     * @param session    the session identity to have vouched for.
     * @return base64 of the signed delegation, or {@code ""}.
     */
    private static String mintSessionDelegation(String worldIdHex, NodeIdentity session) {
        if (worldIdHex == null || worldIdHex.isBlank() || !CompanionLink.isPresent()) {
            return "";
        }
        try {
            java.util.Optional<dev.nodera.core.Bytes> delegation =
                    CompanionLink.client().delegateSession(worldIdHex, session.publicKeyBytes(),
                            dev.nodera.core.identity.SessionDelegation.DEFAULT_TTL_MILLIS / 1000);
            if (delegation.isEmpty()) {
                LOG.info("Nodera: this machine's worker did not vouch for the session key — any "
                        + "operator role or ownership this player holds in '{}' will not be seen "
                        + "here", worldIdHex);
                return "";
            }
            return java.util.Base64.getEncoder().encodeToString(delegation.get().toArray());
        } catch (RuntimeException e) {
            LOG.info("Nodera: could not obtain a session delegation ({}) — this player joins with "
                    + "no persistent identity attached", e.toString());
            return "";
        }
    }

    /**
     * Promote this joiner's always-on worker from a private daemon into a MEMBER of the world its
     * player just joined (network L-30).
     *
     * <p>Until this existed, {@code MESH} was called on the <b>host</b> startup path only, and the
     * joiner path called {@code mesh("", null)} — which detaches. So a joining player's worker was
     * never part of the world it was playing in, and the session's resident population was capped
     * at one however many workers were running. One seatable resident means one validator behind
     * each player primary, which means exactly one inspectable holder per region and no two
     * independently-computed roots to compare: the whole shape of L-30.
     *
     * <p><b>What authorises the worker to join.</b> Nothing beyond what already authorised the game:
     * membership is not authority. A member is a node that dials the session's bootstrap over the
     * authenticated transport and gets a {@code MembershipUpdate} back; what it may then <i>do</i>
     * is bounded by the same rules as every other peer — a committee seat only entitles it to
     * re-execute its regions and vote, every vote is signature-verified against the key in the plan,
     * and a wrong vote loses to quorum. The worker is handed the same bootstrap route its own player
     * was handed, by the process that is standing in the world, so it inherits that admission rather
     * than inventing one. This is the trust model working as designed, not a new trust anchor:
     * peers verify everything, so being in a session buys a node no belief.
     *
     * <p>Bound to the <b>seed</b> as well as the route because a seat can arrive as soon as the
     * membership reply lands, and a replica activated on the wrong seed re-executes to roots nobody
     * else computes — it would vote against every batch, with nothing in any log to say why. The
     * seed is a plan input, which is why this is called from the plan broadcast rather than from the
     * session payload: that is the first moment a joiner knows it.
     *
     * <p>Best-effort and self-catching. A world must still be playable with no companion linked, and
     * this runs on a payload-handler path where an escaping exception would take the connection with
     * it.
     *
     * <p>The control exchange runs on its own short-lived thread, not the caller's. The caller is
     * the client main thread and {@code MESH} carries the 1.5 s probe budget on both connect and
     * read, so an unresponsive worker would otherwise freeze rendering for up to three seconds on
     * every region-boundary crossing.
     *
     * @param worldSeed the joined world's genesis seed, from the plan broadcast.
     * @Thread-context any thread; the control call is dispatched off it.
     */
    public void bindCompanionToSession(long worldSeed) {
        String route = clientBootstrapRoute;
        if (isHosting() || route == null || route.isEmpty()) {
            return; // the host path owns its own binding; a socket-only join has nothing to dial
        }
        String binding = route + "@" + worldSeed;
        if (binding.equals(companionBinding) || !companionBindInFlight.compareAndSet(false, true)) {
            return;
        }
        CompanionClient companion = CompanionLink.client();
        if (companion == null) {
            companionBindInFlight.set(false);
            return;
        }
        Thread.ofPlatform().name("nodera-companion-bind").daemon().start(() -> {
            try {
                java.util.Optional<String> error = companion.mesh(route, worldSeed);
                if (error.isPresent()) {
                    // Say what it COSTS, not just that it happened. This player's worker holding no
                    // seat is the difference between a world validated by an always-on peer and one
                    // validated only for as long as somebody's game is open, and the previous
                    // message said neither — a run where this fired every few seconds looked
                    // identical, in the state document, to a worker nobody had chosen.
                    //
                    // The refusal itself is deliberate on the worker's side: rebinding the world
                    // seed while regions are active would strand replicas that belong to a
                    // different world. It is retried on the next lane plan, which arrives on every
                    // membership or region-boundary change, so this is a degraded state and not a
                    // terminal one — but it is a degraded state that has to be visible.
                    LOG.warn("Nodera: this player's worker did not join the world session: {}."
                            + " It will hold no committee seats for this world and cannot keep it"
                            + " alive once this game closes; the next lane plan retries.",
                            error.get());
                    return;
                }
                companionBinding = binding;
                LOG.info("Nodera: this player's worker joined the world session via {} — it "
                        + "validates and keeps the world alive alongside the host's", route);
            } catch (RuntimeException e) {
                LOG.warn("Nodera: companion session bind failed: {}", e.toString());
            } finally {
                companionBindInFlight.set(false);
            }
        });
    }

    /** One companion bind at a time; see {@link #bindCompanionToSession}. */
    private final java.util.concurrent.atomic.AtomicBoolean companionBindInFlight =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Stop the client peer (on disconnect). Idempotent. */
    public synchronized void stopClient() {
        if (clientRuntime != null) {
            LOG.info("Nodera client peer leaving session");
            // Detach the worker from a session this process is leaving, so it returns to its own
            // session of one rather than heartbeating into a world it no longer has a player in.
            // Mirrors the host teardown in stopHosting(), but off-thread: this runs on the client
            // thread during logout, and MESH can spend its whole 1.5 s + 1.5 s probe budget on a
            // worker that has already gone. Ordering against runtime.stop() below does not matter —
            // a detach that loses the race leaves the worker heartbeating at a dead route, which is
            // precisely the case it already handles by re-electing itself gateway.
            CompanionClient companion = CompanionLink.client();
            if (companion != null && !companionBinding.isEmpty()) {
                Thread.ofPlatform().name("nodera-companion-detach").daemon().start(() -> {
                    try {
                        companion.mesh("", null);
                    } catch (RuntimeException ignored) {
                        // Teardown is best-effort; never let it surface on the disconnect path.
                    }
                });
            }
            companionBinding = "";
            clientBootstrapRoute = "";
            clientRuntime.stop();
            clientRuntime = null;
        }
        if (clientTrackerClient != null) {
            clientTrackerClient.close();
            clientTrackerClient = null;
        }
        clientCollector = null;
        clientTransport = null;
        clientDataTransport = null;
        clientRendezvous = null;
        clientIdentity = null;
        // The delegation names a key that no longer exists. Keeping it would let the next session
        // announce a statement about a dead one.
        sessionDelegationB64 = "";
    }

    /**
     * Resolve {@code "auto"} to a best-guess site-local IPv4; otherwise return the literal host.
     *
     * <p>Delegates to {@link dev.nodera.core.net.NetworkAddresses}, which the headless peer uses
     * too. This method and {@code PeerNode}'s were byte-for-byte identical copies of a rule that
     * turned out to be wrong — it treated a docker bridge and a VPN tunnel as being as good a
     * place to be reached as the real NIC — and two copies of a wrong rule is how one of them gets
     * fixed and the other goes on being wrong.
     */
    public static String resolveHost(String configured) {
        String resolved = dev.nodera.core.net.NetworkAddresses.resolveHost(configured);
        if (dev.nodera.core.net.NetworkAddresses.LOOPBACK.equals(resolved)
                && (configured == null || configured.isBlank()
                    || configured.equalsIgnoreCase("auto"))) {
            LOG.warn("Nodera: no reachable LAN address was found to advertise — falling back to {}."
                    + " Other peers will not be able to dial this node; set p2p.advertiseHost if"
                    + " this machine's real address cannot be detected.", resolved);
        }
        return resolved;
    }

    /** Logs the session lifecycle so operators can watch the mesh and gateway migration. */
    /**
     * The gateway-handover listener for the live entity lane, when one is installed (L-17 / #35).
     *
     * <p>Resolved lazily and defensively: the lane is installed by the session bootstrap, which may
     * not have run when the client peer starts, and a session with no lane simply has no submits to
     * freeze. Returning {@code null} is a supported answer — {@code CompositePeerEventListener}
     * drops nulls rather than rejecting them, so this needs no branch at the call site.
     */
    private static PeerEventListener clientGatewayListener() {
        try {
            var runtime = NoderaHost.entityLaneRuntime();
            var identity = get().clientIdentity();
            return runtime == null || identity == null
                    ? null : runtime.gatewayListener(identity.nodeId());
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static final class LoggingListener implements PeerEventListener {
        private final String tag;

        LoggingListener(String tag) {
            this.tag = tag;
        }

        @Override
        public void onGatewayChanged(NodeId previous, NodeId current, long epoch) {
            LOG.info("[{}] gateway → {} (epoch {})", tag, current, epoch);
        }

        @Override
        public void onPeerJoined(NodeId who) {
            LOG.info("[{}] peer joined: {}", tag, who);
        }

        @Override
        public void onPeerLeft(NodeId who, String reason) {
            LOG.info("[{}] peer left: {} ({})", tag, who, reason);
        }

        @Override
        public void onSessionChanged(SessionView view) {
            LOG.debug("[{}] session epoch={} gateway={} members={}",
                    tag, view.epoch(), view.gatewayId(), view.size());
        }
    }
}
