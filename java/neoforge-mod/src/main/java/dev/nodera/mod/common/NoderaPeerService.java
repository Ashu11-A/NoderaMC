package dev.nodera.mod.common;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
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
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.socket.SocketPeerTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Process-wide holder for this installation's Nodera {@link PeerRuntime}(s) (Phase 6 continuity
 * beta). Bridges the NeoForge lifecycle to the Minecraft-free peer runtime:
 *
 * <ul>
 *   <li>on a dedicated server, {@link #startBootstrap} spins up the <b>bootstrap peer</b> that
 *       clients join through (the "server acting as a peer");</li>
 *   <li>on a client, {@link #onServerSessionInfo} spins up a <b>player peer</b> that dials the
 *       server's advertised P2P route and joins the mesh, so the two players form a direct link
 *       that outlives the server.</li>
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
    private PeerRuntime serverRuntime;
    private DiagnosticsCollector serverCollector;
    private dev.nodera.mod.debug.DiagnosticsService serverDiagnostics;

    private dev.nodera.peer.discovery.TrackerClient serverTrackerClient;

    private NodeIdentity clientIdentity;
    private SocketPeerTransport clientTransport;
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
     * Start the bootstrap peer (dedicated server). Idempotent.
     *
     * @param bindHost      local bind address.
     * @param port          local P2P port.
     * @param advertiseHost host clients dial ({@code "auto"} → best local non-loopback address).
     * @return the advertised bootstrap route ({@code host:port}), never null once started.
     */
    public synchronized String startBootstrap(String bindHost, int port, String advertiseHost) {
        if (serverRuntime != null) {
            return serverRuntime.selfRoute();
        }
        serverIdentity = NodeIdentity.generate();
        String advertise = resolveHost(advertiseHost);
        serverTransport = new SocketPeerTransport(serverIdentity.nodeId(), bindHost, port, advertise);
        TrafficMeter serverMeter = new TrafficMeter();
        MessageCounters serverCounts = new MessageCounters();
        MeteredPeerTransport serverMetered = new MeteredPeerTransport(serverTransport, serverMeter);
        serverRuntime = PeerRuntime.bootstrap(serverIdentity, NodeCapabilities.initial(),
                serverMetered, serverTransport::listenRoute, PeerRuntimeConfig.defaults(),
                new LoggingListener("server"), serverCounts);
        serverCollector = new DiagnosticsCollector(serverMeter, serverCounts)
                .register(serverRuntime)
                .register(RegionOwnershipProvider.stub())
                .register(EntityControlProvider.stub());
        serverDiagnostics = new dev.nodera.mod.debug.DiagnosticsService(serverRuntime, serverCollector);
        // Task 28: the tracker is a separate process now. The bootstrap peer is the world's
        // FULL_ARCHIVE host, so it is the peer whose announce carries the world's display name.
        serverTrackerClient = trackerClient(NoderaConfig.TRACKER_ENDPOINTS.get(), serverIdentity);
        if (!serverTrackerClient.endpoints().isEmpty()) {
            LOG.info("Nodera tracker endpoints: {}", serverTrackerClient.endpoints());
        }
        String route = serverRuntime.selfRoute();
        LOG.info("Nodera bootstrap peer online at {} (node {})", route, serverIdentity.nodeId());
        return route;
    }

    /** @return the current bootstrap route to advertise to clients, or {@code null} if offline. */
    public synchronized String bootstrapRoute() {
        return serverRuntime == null ? null : serverRuntime.selfRoute();
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

    /** Stop the bootstrap peer. Idempotent. */
    public synchronized void stopServer() {
        if (serverRuntime != null) {
            LOG.info("Nodera bootstrap peer shutting down");
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
        serverIdentity = null;
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
        PeerAddress bootstrapAddress = PeerAddress.of(null, bootstrapRoute); // socket routes by host:port
        clientRuntime = PeerRuntime.peer(clientIdentity, NodeCapabilities.initial(),
                clientMetered, clientTransport::listenRoute, bootstrapAddress,
                PeerRuntimeConfig.defaults(), new LoggingListener("client"), clientCounts);
        clientTrackerClient = trackerClient(NoderaConfig.CLIENT_TRACKER_ENDPOINTS.get(), clientIdentity);
        clientCollector = new DiagnosticsCollector(clientMeter, clientCounts)
                .register(clientRuntime)
                .register(RegionOwnershipProvider.stub())
                .register(EntityControlProvider.stub());
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
        clientIdentity = null;
    }

    /** Resolve {@code "auto"} to a best-guess site-local IPv4; otherwise return the literal host. */
    private static String resolveHost(String configured) {
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
