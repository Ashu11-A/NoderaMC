package dev.nodera.mod.common;

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

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
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
    private NodeCapabilities hostCaps;
    private ScheduledExecutorService announceScheduler;
    /** The Minecraft game endpoint ({@code host:port}) announced while the game server is open. */
    private String gameRoute;

    private dev.nodera.peer.discovery.TrackerClient serverTrackerClient;

    private NodeIdentity clientIdentity;
    private SocketPeerTransport clientTransport;
    private PeerTransport clientDataTransport;
    private PeerRuntime clientRuntime;
    private DiagnosticsCollector clientCollector;
    private dev.nodera.peer.discovery.TrackerClient clientTrackerClient;

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
     * @return the advertised host route ({@code host:port}), never null once started.
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
        serverTransport = new SocketPeerTransport(serverIdentity.nodeId(), bindHost, port, advertise);
        TrafficMeter serverMeter = new TrafficMeter();
        MessageCounters serverCounts = new MessageCounters();

        // Direct socket, optionally wrapped in the rendezvous transport so the host registers a
        // signed record and stays reachable across NATs (Task 29). The socket is the LAN path and the
        // self-route source either way.
        PeerTransport dataTransport = composeHostTransport(worldId);
        MeteredPeerTransport serverMetered = new MeteredPeerTransport(dataTransport, serverMeter);
        serverDataTransport = serverMetered;
        serverRuntime = PeerRuntime.bootstrap(serverIdentity, hostCaps,
                serverMetered, serverTransport::listenRoute, PeerRuntimeConfig.defaults(),
                new LoggingListener("host"), serverCounts);
        serverCollector = new DiagnosticsCollector(serverMeter, serverCounts)
                .register(serverRuntime)
                .register(dev.nodera.mod.server.entity.LiveRegionOwnershipProvider.get())
                .register(dev.nodera.mod.server.entity.LiveEntityControlProvider.get());
        serverDiagnostics = new dev.nodera.mod.debug.DiagnosticsService(serverRuntime, serverCollector);

        // Announce to the tracker (Task 28) and keep re-announcing on its cadence.
        serverTrackerClient = trackerClient(NoderaConfig.TRACKER_ENDPOINTS.get(), serverIdentity);
        if (!serverTrackerClient.endpoints().isEmpty()) {
            LOG.info("Nodera tracker endpoints: {}", serverTrackerClient.endpoints());
            startAnnouncing();
        } else {
            LOG.info("Nodera: no tracker endpoints configured — world not announced");
        }

        String route = serverRuntime.selfRoute();
        LOG.info("Nodera host peer online at {} (node {}, world '{}', encryption={})",
                route, serverIdentity.nodeId(), hostWorldName, this.hostOptions.encryptionEnabled());
        return route;
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
        java.util.List<? extends String> routes = NoderaConfig.RENDEZVOUS_ENDPOINTS.get();
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
            return rendezvous;
        } catch (RuntimeException e) {
            LOG.warn("Nodera rendezvous unreachable ({}); using direct socket only", e.getMessage());
            return serverTransport;
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
                () -> sendAnnounce(AnnounceEvent.STARTED), 0, interval, TimeUnit.SECONDS);
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

    /** @return the active share options while hosting, or {@code null} if not hosting. */
    public synchronized ShareOptions hostOptions() {
        return serverRuntime == null ? null : hostOptions;
    }

    /** @return the server-side runtime (for the {@code /nodera} command), or {@code null}. */
    public synchronized PeerRuntime serverRuntime() {
        return serverRuntime;
    }

    /** @return the server-side diagnostics collector (for the HUD), or {@code null}. */
    public synchronized DiagnosticsCollector serverCollector() {
        return serverCollector;
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
     * @return the client-side tracker client, used by the Task 26 multiplayer world list, or
     *         {@code null} before the client peer joins.
     * @Thread-context any thread.
     */
    public synchronized dev.nodera.peer.discovery.TrackerClient clientTrackerClient() {
        return clientTrackerClient;
    }

    /** Stop hosting this world (server stopping, or the "Stop sharing" action). Idempotent. */
    public synchronized void stopHosting() {
        if (announceScheduler != null) {
            announceScheduler.shutdownNow();
            announceScheduler = null;
        }
        // Tell the tracker the world is gone (best-effort) before we tear the runtime down.
        if (serverTrackerClient != null && !serverTrackerClient.endpoints().isEmpty()
                && serverIdentity != null && hostWorldId != null) {
            sendAnnounce(AnnounceEvent.STOPPED);
        }
        if (serverRuntime != null) {
            LOG.info("Nodera host peer shutting down");
            serverRuntime.stop();
            serverRuntime = null;
        }
        if (serverTrackerClient != null) {
            serverTrackerClient.close();
            serverTrackerClient = null;
        }
        serverCollector = null;
        serverDiagnostics = null;
        serverTransport = null;
        serverDataTransport = null;
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
        if (clientRuntime != null) {
            return;
        }
        clientIdentity = NodeIdentity.generate();
        String advertise = resolveHost(advertiseHost);
        clientTransport = new SocketPeerTransport(clientIdentity.nodeId(), "0.0.0.0", 0, advertise);
        TrafficMeter clientMeter = new TrafficMeter();
        MessageCounters clientCounts = new MessageCounters();
        MeteredPeerTransport clientMetered = new MeteredPeerTransport(clientTransport, clientMeter);
        clientDataTransport = clientMetered;
        PeerAddress bootstrapAddress = PeerAddress.of(null, bootstrapRoute); // socket routes by host:port
        clientRuntime = PeerRuntime.peer(clientIdentity, NodeCapabilities.initial(),
                clientMetered, clientTransport::listenRoute, bootstrapAddress,
                PeerRuntimeConfig.defaults(), new LoggingListener("client"), clientCounts);
        clientTrackerClient = trackerClient(NoderaConfig.CLIENT_TRACKER_ENDPOINTS.get(), clientIdentity);
        clientCollector = new DiagnosticsCollector(clientMeter, clientCounts)
                .register(clientRuntime)
                .register(dev.nodera.mod.server.entity.LiveRegionOwnershipProvider.get())
                .register(dev.nodera.mod.server.entity.LiveEntityControlProvider.get());
        LOG.info("Nodera client peer joining session via {} (node {}, listening {})",
                bootstrapRoute, clientIdentity.nodeId(), clientRuntime.selfRoute());
    }

    /** Stop the client peer (on disconnect). Idempotent. */
    public synchronized void stopClient() {
        if (clientRuntime != null) {
            LOG.info("Nodera client peer leaving session");
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
        clientIdentity = null;
    }

    /** Resolve {@code "auto"} to a best-guess site-local IPv4; otherwise return the literal host. */
    public static String resolveHost(String configured) {
        if (configured != null && !configured.equalsIgnoreCase("auto") && !configured.isBlank()) {
            return configured;
        }
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isSiteLocalAddress() && a.getAddress().length == 4) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Nodera advertise-host auto-detect failed, falling back to 127.0.0.1", e);
        }
        return "127.0.0.1";
    }

    /** Logs the session lifecycle so operators can watch the mesh and gateway migration. */
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
