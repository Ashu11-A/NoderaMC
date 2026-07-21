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
import dev.nodera.peer.metric.MeteredPeerTransport;
import dev.nodera.transport.socket.SocketPeerTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Enumeration;
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

        WorkerControlHandler handler = new WorkerControlHandler(WORKER_VERSION, identity, runtime, meter);
        ControlServer control = new ControlServer(controlHost, controlPort, handler);
        control.start();

        LOG.info("Nodera peer worker {} online — node {} listening {}, control {}:{}",
                WORKER_VERSION, identity.nodeId(), runtime.selfRoute(), controlHost,
                control.boundPort());

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Nodera peer worker shutting down");
            control.close();
            runtime.stop();
            stop.countDown();
        }, "nodera-worker-shutdown"));

        stop.await(); // run until signalled
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
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
