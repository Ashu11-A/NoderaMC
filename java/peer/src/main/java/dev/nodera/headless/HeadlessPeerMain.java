package dev.nodera.headless;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.diagnostics.metric.MessageCounters;
import dev.nodera.diagnostics.metric.TrafficMeter;
import dev.nodera.peer.PeerEventListener;
import dev.nodera.peer.PeerRuntime;
import dev.nodera.peer.PeerRuntimeConfig;
import dev.nodera.peer.SessionView;
import dev.nodera.peer.control.ControlServer;
import dev.nodera.peer.discovery.PersistentIdentityStore;
import dev.nodera.peer.discovery.TrackerClient;
import dev.nodera.peer.metric.MeteredPeerTransport;
import dev.nodera.peer.validation.WorkerValidationService;
import dev.nodera.transport.rendezvous.RendezvousEndpoint;
import dev.nodera.transport.socket.SocketPeerTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Task 32: the Nodera <b>peer worker</b> — the always-on, Minecraft-free network node. It is what
 * keeps a player part of the network even with Minecraft closed: it boots a {@link PeerRuntime} over
 * a real socket, holds a <em>persistent</em> identity (so the node keeps its {@code NodeId} across
 * restarts, L-28), and serves the loopback {@link ControlServer} the Minecraft mod probes at startup
 * — the mod refuses to launch if this worker is not running.
 *
 * <p>The Tauri companion app ({@code rust/nodera-app}) supervises this process (auto-launch at login,
 * tray, dashboard); {@code scripts/dev.sh} can also run it directly for development. Configuration is
 * environment-driven so the supervisor can pass it without a config file.
 *
 * <p>Deferred to the live lane: per-world tracker announce + rendezvous registration + seeding are
 * driven by control verbs once the mod asks the worker to host/join a world; this MVP establishes the
 * always-on node + the presence gate it backs.
 */
public final class HeadlessPeerMain {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaWorker");

    /** This worker's build version, reported on the control probe (mirrors the mod's expectation). */
    public static final String WORKER_VERSION = "0.1.0-SNAPSHOT";

    private HeadlessPeerMain() {
    }

    public static void main(String[] args) throws Exception {
        String controlHost = env("NODERA_CONTROL_HOST", "127.0.0.1");
        int controlPort = envInt("NODERA_CONTROL_PORT", 25610);
        String bindHost = env("NODERA_P2P_BIND", "0.0.0.0");
        int p2pPort = envInt("NODERA_P2P_PORT", 25620);
        String advertise = resolveHost(env("NODERA_P2P_ADVERTISE", "auto"));
        Path identityFile = Path.of(env("NODERA_IDENTITY_FILE",
                System.getProperty("user.home") + "/.nodera/worker-identity.bin"));

        NodeIdentity identity = new PersistentIdentityStore(identityFile).loadOrGenerate();
        NodeCapabilities caps = NodeCapabilities.initial().withRoles(
                EnumSet.of(PeerRole.FULL_ARCHIVE, PeerRole.BOOTSTRAP, PeerRole.REGION_VALIDATOR));

        SocketPeerTransport transport =
                new SocketPeerTransport(identity.nodeId(), bindHost, p2pPort, advertise);
        TrafficMeter meter = new TrafficMeter();
        MeteredPeerTransport metered = new MeteredPeerTransport(transport, meter);
        MessageCounters counters = new MessageCounters();

        PeerRuntime runtime = PeerRuntime.bootstrap(identity, caps, metered,
                transport::listenRoute, PeerRuntimeConfig.defaults(),
                new LoggingListener(), counters);

        // Discovery services this worker announces hosted worlds to (Task 32 live lane). Defaults
        // match the mod's DEFAULT_TRACKER/RENDEZVOUS_ENDPOINTS so a fresh install is functional; the
        // Tauri supervisor (or scripts/dev.sh) can override with the two env vars below.
        List<TrackerClient.Endpoint> trackerEndpoints = parseTrackers(
                env("NODERA_TRACKER_ENDPOINTS", "127.0.0.1:25600"));
        List<RendezvousEndpoint> rendezvousEndpoints = parseRendezvous(
                env("NODERA_RENDEZVOUS_ENDPOINTS", "127.0.0.1:25601"));

        // The world-archive lane (the continuity increment): this worker seeds the canonical
        // archives of the worlds it hosts and can fetch any world's archive from the swarm, so a
        // shared world's save bytes survive the hosting player's game — and machine — going away.
        Path archiveDir = Path.of(env("NODERA_ARCHIVE_DIR",
                System.getProperty("user.home") + "/.nodera/archive"));
        WorldArchiveService archive = new WorldArchiveService(identity, metered,
                new dev.nodera.storage.rocksdb.FsContentStore(
                        archiveDir, new dev.nodera.core.crypto.HashService()),
                trackerEndpoints);

        WorldHostingService hosting = new WorldHostingService(identity, caps, runtime::selfRoute,
                trackerEndpoints, rendezvousEndpoints, archive::holdingsFor);

        // The validation lane (L-48/L-30): this worker re-executes region batches out-of-game
        // with THE engine and participates in committee quorum over the same PeerTransport its
        // membership session rides. Regions activate via the host/join control verbs; the
        // service idles (zero cost) until then.
        WorkerValidationService validation = new WorkerValidationService(
                identity, metered,
                new dev.nodera.simulation.engine.FlatWorldRegionEngine(
                        dev.nodera.simulation.rules.FlatWorldRules.RULES_VERSION,
                        dev.nodera.simulation.rules.FlatWorldRules.registryFingerprint(),
                        new dev.nodera.core.crypto.HashService()),
                new dev.nodera.core.crypto.HashService(),
                new dev.nodera.storage.event.InMemoryCertificateStore(
                        new dev.nodera.core.crypto.HashService()),
                envLong("NODERA_WORLD_SEED", 0x4E4F4445_5241L),
                dev.nodera.simulation.rules.FlatWorldRules.RULES_VERSION,
                dev.nodera.simulation.rules.FlatWorldRules.registryFingerprint(),
                2000L);
        // One application lane, two consumers: the validation flow and the archive/content flow.
        // Each ignores message types it does not own.
        runtime.onApplicationMessage((from, msg) -> {
            validation.onMessage(from, msg);
            archive.onMessage(from, msg);
        });

        WorkerControlHandler handler = new WorkerControlHandler(
                WORKER_VERSION, identity, caps, runtime, meter, hosting, validation, archive);
        ControlServer control = new ControlServer(controlHost, controlPort, handler);
        control.start();

        LOG.info("Nodera peer worker {} online — node {} listening {}, control {}:{}, "
                        + "{} tracker(s) / {} rendezvous",
                WORKER_VERSION, identity.nodeId(), runtime.selfRoute(), controlHost,
                control.boundPort(), trackerEndpoints.size(), rendezvousEndpoints.size());

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Nodera peer worker shutting down");
            hosting.close();
            control.close();
            archive.close();
            runtime.stop();
            stop.countDown();
        }, "nodera-worker-shutdown"));

        stop.await(); // run until signalled
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static long envLong(String key, long fallback) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            LOG.warn("Bad long in {}='{}', using {}", key, v, fallback);
            return fallback;
        }
    }

    private static int envInt(String key, int fallback) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            LOG.warn("Bad integer in {}='{}', using {}", key, v, fallback);
            return fallback;
        }
    }

    /** Parse a comma-separated {@code host:port} list into tracker endpoints (malformed entries skipped). */
    private static List<TrackerClient.Endpoint> parseTrackers(String csv) {
        List<TrackerClient.Endpoint> out = new ArrayList<>();
        for (String route : csv.split(",")) {
            String trimmed = route.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                out.add(TrackerClient.Endpoint.parse(trimmed));
            } catch (IllegalArgumentException e) {
                LOG.warn("Ignoring malformed tracker endpoint '{}': {}", trimmed, e.getMessage());
            }
        }
        return out;
    }

    /** Parse a comma-separated {@code host:port} list into rendezvous endpoints (malformed skipped). */
    private static List<RendezvousEndpoint> parseRendezvous(String csv) {
        List<RendezvousEndpoint> out = new ArrayList<>();
        for (String route : csv.split(",")) {
            String trimmed = route.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                out.add(RendezvousEndpoint.parse(trimmed));
            } catch (IllegalArgumentException e) {
                LOG.warn("Ignoring malformed rendezvous endpoint '{}': {}", trimmed, e.getMessage());
            }
        }
        return out;
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
            LOG.warn("advertise-host auto-detect failed, falling back to 127.0.0.1", e);
        }
        return "127.0.0.1";
    }

    /** Logs the session lifecycle so operators (and the Tauri dashboard) can watch the mesh. */
    private static final class LoggingListener implements PeerEventListener {
        @Override
        public void onGatewayChanged(NodeId previous, NodeId current, long epoch) {
            LOG.info("gateway → {} (epoch {})", current, epoch);
        }

        @Override
        public void onPeerJoined(NodeId who) {
            LOG.info("peer joined: {}", who);
        }

        @Override
        public void onPeerLeft(NodeId who, String reason) {
            LOG.info("peer left: {} ({})", who, reason);
        }

        @Override
        public void onSessionChanged(SessionView view) {
            LOG.debug("session epoch={} gateway={} members={}",
                    view.epoch(), view.gatewayId(), view.size());
        }
    }
}
