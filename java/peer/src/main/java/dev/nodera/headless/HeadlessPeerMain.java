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

    /**
     * This worker's build version, reported on the control probe (mirrors the mod's expectation).
     *
     * <p>The product version, not a worker-specific one: the app compares what the worker reports
     * with what it was shipped alongside, and two independently maintained numbers made that
     * comparison meaningless. Source of truth is the root {@code VERSION} file.
     */
    public static final String WORKER_VERSION = dev.nodera.core.NoderaConstants.PRODUCT_VERSION;

    private HeadlessPeerMain() {
    }

    public static void main(String[] args) throws Exception {
        long startedAtMillis = System.currentTimeMillis();
        String controlHost = env("NODERA_CONTROL_HOST", "127.0.0.1");
        int controlPort = envInt("NODERA_CONTROL_PORT", 25610);
        String bindHost = env("NODERA_P2P_BIND", "0.0.0.0");
        int p2pPort = envInt("NODERA_P2P_PORT", 25620);
        // NODERA_P2P_PORT_RANGE=start-end widens the bind to the first free port in a range. A
        // single fixed port is forwardable but fails outright when anything else already holds it
        // (a second worker, a stale JVM, a dev client); an ephemeral port always binds but is not
        // forwardable. A small range is the honest middle. Unset ⇒ the 1-wide range {p2pPort}.
        int[] portRange = parsePortRange(env("NODERA_P2P_PORT_RANGE", ""), p2pPort);
        String advertise = resolveHost(env("NODERA_P2P_ADVERTISE", "auto"));
        Path identityFile = Path.of(env("NODERA_IDENTITY_FILE",
                System.getProperty("user.home") + "/.nodera/worker-identity.bin"));

        NodeIdentity identity = new PersistentIdentityStore(identityFile).loadOrGenerate();
        NodeCapabilities caps = NodeCapabilities.initial().withRoles(
                EnumSet.of(PeerRole.FULL_ARCHIVE, PeerRole.BOOTSTRAP, PeerRole.REGION_VALIDATOR));

        // Authenticated mode (issue #41 / L-53): connections prove key possession at accept.
        SocketPeerTransport transport =
                new SocketPeerTransport(identity, bindHost, portRange[0], portRange[1], advertise);
        TrafficMeter meter = new TrafficMeter();
        // Per-peer attribution rides the same choke points as the node totals, so the companion
        // app's Peers tab reports bytes actually moved with each peer instead of placeholders.
        dev.nodera.peer.metric.PeerTrafficMeter peerMeter =
                new dev.nodera.peer.metric.PeerTrafficMeter();
        MeteredPeerTransport metered = new MeteredPeerTransport(transport, meter, peerMeter);
        MessageCounters counters = new MessageCounters();

        LoggingListener sessionListener = new LoggingListener();
        PeerRuntime runtime = PeerRuntime.bootstrap(identity, caps, metered,
                transport::listenRoute, PeerRuntimeConfig.defaults(),
                sessionListener, counters);

        // Discovery services this worker announces hosted worlds to (Task 32 live lane). Defaults
        // match the mod's DEFAULT_TRACKER/RENDEZVOUS_ENDPOINTS so a fresh install is functional; the
        // Tauri supervisor (or scripts/dev.sh) can override with the two env vars below.
        List<TrackerClient.Endpoint> trackerEndpoints = parseTrackers(
                env("NODERA_TRACKER_ENDPOINTS", "127.0.0.1:25600"));
        List<RendezvousEndpoint> rendezvousEndpoints = parseRendezvous(
                env("NODERA_RENDEZVOUS_ENDPOINTS", "127.0.0.1:25601"));

        // ONE tracker client for the whole node. The archive lane, the hosting/announce lane, peer
        // discovery and replication each used to hold their own — four announce cadences and four
        // copies of the endpoint list describing a single node, and no single place to change them,
        // which is precisely why the tracker list used to be a restart-required setting. Sharing one
        // client makes TrackerClient.setEndpoints reach every lane at once.
        TrackerClient tracker = new TrackerClient(trackerEndpoints, identity);

        // The world-archive lane (the continuity increment): this worker seeds the canonical
        // archives of the worlds it hosts and can fetch any world's archive from the swarm, so a
        // shared world's save bytes survive the hosting player's game — and machine — going away.
        Path archiveDir = Path.of(env("NODERA_ARCHIVE_DIR",
                System.getProperty("user.home") + "/.nodera/archive"));
        // Held by reference so the archive directory is a live setting rather than a
        // restart-required one that strands what this node is already seeding (L-58).
        dev.nodera.storage.rocksdb.FsContentStore contentStore =
                new dev.nodera.storage.rocksdb.FsContentStore(
                        archiveDir, new dev.nodera.core.crypto.HashService());
        WorldArchiveService archive = new WorldArchiveService(identity, metered, contentStore,
                tracker);
        // Continuous archive streaming appends a version every `archive.streamIntervalTicks`, so
        // without a window a long session's content store grows for as long as the session lasts
        // (L-61). NODERA_ARCHIVE_RETAINED_VERSIONS bounds it; the floor is 1, because the newest
        // archive of a hosted world IS the world.
        archive.setRetainedVersions((int) envLong("NODERA_ARCHIVE_RETAINED_VERSIONS",
                WorldArchiveService.DEFAULT_RETAINED_VERSIONS));
        // Total disk ceiling for this node's blob tier (L-62). Unbounded by default: this store also
        // holds the worlds this node HOSTS, and "evict to fit" is the wrong answer for those. Set it
        // and the store evicts oldest-cold-first — never a seeded archive, which the archive lane
        // pins, so what a budget bounds is replicas of other people's worlds.
        long contentBudget = envLong("NODERA_CONTENT_BUDGET", 0L);
        if (contentBudget > 0) {
            contentStore.setBudgetBytes(contentBudget);
            LOG.info("Content store bounded to {} byte(s) — seeded archives pinned, "
                    + "oldest cold content evicted first", contentBudget);
        }

        WorldHostingService hosting = new WorldHostingService(identity, caps, runtime::selfRoute,
                tracker, rendezvousEndpoints, archive::holdingsFor);

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
        // Committee peers come from membership now: bind the listener so every view change
        // republishes members' routes + keys into the validation lane. Bind BEFORE any join so a
        // view that lands during startup is not missed, and replay the current view once for the
        // members already known.
        sessionListener.bind(validation);
        sessionListener.onSessionChanged(runtime.sessionView());

        // The permission lane (L-54): grants used to exist only in the author's own
        // nodera-permissions.dat, so a co-hosting peer's permission set was author-local — an
        // operator promotion or a ban did not exist for the rest of the mesh. Every grant this node
        // issues or receives is relayed onward and re-verified locally against the world's author
        // key, so the transport carries the decision without ever being trusted with it.
        WorldGrantGossipService grants = new WorldGrantGossipService(
                identity.nodeId(), metered, () -> runtime.sessionView().members());

        // One application lane, three consumers: validation, archive/content, and permissions.
        // Each ignores message types it does not own.
        runtime.onApplicationMessage((from, msg) -> {
            validation.onMessage(from, msg);
            archive.onMessage(from, msg);
            grants.onMessage(from, msg);
        });

        // Peer discovery (the plane that was built but never asked): on a cadence, ask every
        // tracker and every rendezvous service who else is in each world this worker hosts, and
        // introduce ourselves to each one. Without it a worker only ever meets peers that the one
        // route it was handed happened to gossip.
        dev.nodera.peer.discovery.PeerDiscoveryService discovery =
                new dev.nodera.peer.discovery.PeerDiscoveryService(
                        tracker,
                        new dev.nodera.transport.rendezvous.RendezvousClient(identity,
                                java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(5)),
                        rendezvousEndpoints,
                        () -> {
                            List<dev.nodera.core.Bytes> ids = new ArrayList<>();
                            for (WorldHostingService.HostedWorld w : hosting.hostedWorlds()) {
                                ids.add(dev.nodera.core.Bytes.fromHex(w.worldIdHex()));
                            }
                            return ids;
                        },
                        runtime::announceTo);
        discovery.start();

        // Replication: adopt worlds this node is deterministically placed for, so a shared world's
        // bytes live on more than the machine that shared it. Bounded by NODERA_REPLICATION_BUDGET
        // (bytes; 0 disables the whole lane for operators who only want to host their own worlds).
        WorldReplicationService replication = new WorldReplicationService(
                identity.nodeId(), tracker, archive, hosting,
                envLong("NODERA_REPLICATION_BUDGET",
                        WorldReplicationService.DEFAULT_BUDGET_BYTES),
                (int) envLong("NODERA_REPLICATION_SWEEP_SECONDS",
                        WorldReplicationService.DEFAULT_SWEEP_SECONDS));
        replication.start();

        // The seams a NODERA-CONFIG push may re-bound. Passing the real services (not a copy of
        // their values) is what makes a setting change what this node does, with no second copy of
        // the configuration to drift out of sync.
        WorkerControlHandler handler = new WorkerControlHandler(
                WORKER_VERSION, identity, caps, runtime, meter, hosting, validation, archive,
                peerMeter, discovery,
                new WorkerControlHandler.ConfigSeams(
                        archive.content(), replication, transport, tracker, contentStore),
                grants);
        // Telemetry: OFF unless an endpoint is configured, and silent until somebody consents.
        // Two independent gates, deliberately: an operator who sets an endpoint has decided WHERE
        // reports would go, not that any may be collected — only the person answering the app's
        // first-run question decides that.
        Path telemetryDir = Path.of(env("NODERA_TELEMETRY_DIR",
                System.getProperty("user.home") + "/.nodera"));
        WorkerTelemetryService telemetry = new WorkerTelemetryService(
                env("NODERA_TELEMETRY_ENDPOINT", ""),
                dev.nodera.core.NoderaConstants.CLIENT_AGENT,
                envLong("NODERA_TELEMETRY_INTERVAL_SECONDS", 300L),
                telemetryDir,
                () -> telemetrySnapshot(identity, runtime, meter),
                // The worker holds no regions of its own until the validation lane gives it seats,
                // so it reports "not ticking" rather than inventing a rate. An honest zero.
                dev.nodera.telemetry.SnapshotProjector.TickHealth::unknown);
        handler.attachTelemetry(telemetry);
        telemetry.start();
        telemetry.record(dev.nodera.telemetry.TelemetryEvent
                .named(dev.nodera.telemetry.TelemetryRegistry.SERVICE_START,
                        System.currentTimeMillis())
                .enumeration("version", WORKER_VERSION)
                .enumeration("os", dev.nodera.telemetry.Buckets.osFamily())
                .enumeration("arch", dev.nodera.telemetry.Buckets.arch())
                .number("cpu_cores_bucket", dev.nodera.telemetry.Buckets.magnitude(
                        Runtime.getRuntime().availableProcessors()))
                .number("ram_gb_bucket", dev.nodera.telemetry.Buckets.magnitude(
                        Runtime.getRuntime().maxMemory() / (1024L * 1024L * 1024L)))
                .build());

        ControlServer control = new ControlServer(controlHost, controlPort, handler);
        control.start();

        LOG.info("Nodera peer worker {} online — node {} listening {}, control {}:{}, "
                        + "{} tracker(s) / {} rendezvous",
                WORKER_VERSION, identity.nodeId(), runtime.selfRoute(), controlHost,
                control.boundPort(), trackerEndpoints.size(), rendezvousEndpoints.size());

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Nodera peer worker shutting down");
            telemetry.record(dev.nodera.telemetry.TelemetryEvent
                    .named(dev.nodera.telemetry.TelemetryRegistry.SERVICE_STOP,
                            System.currentTimeMillis())
                    .number("uptime_hours_bucket", dev.nodera.telemetry.Buckets.hours(
                            System.currentTimeMillis() - startedAtMillis))
                    .flag("clean", true)
                    .build());
            telemetry.close();
            discovery.close();
            replication.close();
            hosting.close();
            control.close();
            archive.close();
            tracker.close(); // shared by every lane above, so it is closed once, here.
            runtime.stop();
            stop.countDown();
        }, "nodera-worker-shutdown"));

        stop.await(); // run until signalled
    }

    /**
     * The diagnostics snapshot the telemetry projector reads.
     *
     * <p>Built here rather than shared with the HUD's collector because the worker has no tick loop
     * to sample on: it has cumulative meters and a membership view, which is exactly what the
     * windowed peer events need. Region ownership is reported empty until the worker holds seats —
     * an honest empty rather than a guess.
     */
    private static dev.nodera.diagnostics.model.TelemetrySnapshot telemetrySnapshot(
            NodeIdentity identity, dev.nodera.peer.PeerRuntime runtime,
            dev.nodera.diagnostics.metric.TrafficMeter meter) {
        java.util.List<dev.nodera.diagnostics.model.PeerLink> peers = new ArrayList<>();
        for (dev.nodera.protocol.membership.PeerEntry member : runtime.sessionView().members()) {
            if (member.nodeId().equals(runtime.nodeId())) {
                continue;
            }
            peers.add(new dev.nodera.diagnostics.model.PeerLink(member.nodeId(),
                    member.route() == null ? "" : member.route(), false, "peer", 0L, 0L, true));
        }
        return new dev.nodera.diagnostics.model.TelemetrySnapshot(
                0L, identity.nodeId(), false,
                new dev.nodera.diagnostics.model.SessionInfo(0L, null, runtime.isGateway(),
                        peers.size() + 1, "peer", peers),
                new dev.nodera.diagnostics.model.NetStats(meter.bytesTx(), meter.bytesRx(),
                        0, 0, 0, 0, 0, 0, java.util.Map.of()),
                dev.nodera.diagnostics.model.RegionOwnership.empty(),
                dev.nodera.diagnostics.model.EntityControl.empty(),
                dev.nodera.diagnostics.model.HealthStat.healthy());
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

    /**
     * Parse {@code NODERA_P2P_PORT_RANGE} as {@code start-end}.
     *
     * <p>An unset or malformed value falls back to the single {@code fallbackPort}, logged — a peer
     * that silently bound a range its operator did not ask for would advertise a port nobody
     * forwarded, which reads as "the network is broken" rather than "the config is wrong".
     *
     * @param spec         the raw env value, possibly empty.
     * @param fallbackPort the single port to use when no range is configured.
     * @return {@code {start, end}}, inclusive.
     */
    static int[] parsePortRange(String spec, int fallbackPort) {
        if (spec == null || spec.isBlank()) {
            return new int[]{fallbackPort, fallbackPort};
        }
        int dash = spec.indexOf('-');
        if (dash > 0) {
            try {
                int start = Integer.parseInt(spec.substring(0, dash).trim());
                int end = Integer.parseInt(spec.substring(dash + 1).trim());
                if (start > 0 && end >= start && end <= 65535) {
                    return new int[]{start, end};
                }
            } catch (NumberFormatException ignored) {
                // fall through to the warning below
            }
        }
        LOG.warn("Bad port range in NODERA_P2P_PORT_RANGE='{}', using single port {}",
                spec, fallbackPort);
        return new int[]{fallbackPort, fallbackPort};
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
    /**
     * Logs session lifecycle and — the part that matters — mirrors the membership view into the
     * validation lane as committee peers.
     *
     * <p>A worker can only verify a proposal or a vote from a node whose Ed25519 key it holds, and
     * membership is now where every member's key is published ({@code PeerEntry.publicKey}). So
     * every time the view changes, every member with a route and a key is (re)registered. Without
     * this the worker would take a committee seat it could not act on: proposals from the primary
     * would fail their signature lookup and be silently dropped.
     *
     * <p>The validation service is wired in after construction because the runtime (which needs
     * this listener) is built before it.
     */
    private static final class LoggingListener implements PeerEventListener {

        private final java.util.concurrent.atomic.AtomicReference<WorkerValidationService> validation =
                new java.util.concurrent.atomic.AtomicReference<>();

        void bind(WorkerValidationService service) {
            validation.set(service);
        }

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
            WorkerValidationService service = validation.get();
            if (service == null) {
                return;
            }
            for (dev.nodera.protocol.membership.PeerEntry member : view.members()) {
                if (member.route().isEmpty() || !member.hasPublicKey()) {
                    continue;
                }
                try {
                    service.registerPeer(member.nodeId(),
                            dev.nodera.transport.PeerAddress.of(member.nodeId(), member.route()),
                            member.publicKey());
                } catch (RuntimeException ignored) {
                    // A malformed entry must not tear down membership handling for the rest.
                }
            }
        }
    }
}
