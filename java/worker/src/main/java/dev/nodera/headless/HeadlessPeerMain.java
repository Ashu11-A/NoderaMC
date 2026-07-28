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

    static LocalState openLocalState(Path identityFile, Path worldsFile, Path worldKeysDir) {
        return new LocalState(
                new PersistentIdentityStore(identityFile).loadOrGenerate(),
                new WorldRegistryStore(worldsFile),
                new WorldKeyStore(worldKeysDir));
    }

    record LocalState(NodeIdentity identity, WorldRegistryStore worldRegistry,
                      WorldKeyStore worldKeys) {
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
        Path stateDir = Path.of(env("NODERA_STATE_DIR", System.getProperty("user.home") + "/.nodera"));
        LocalState localState = openLocalState(
                identityFile,
                Path.of(env("NODERA_WORLDS_FILE", stateDir.resolve("worlds.dat").toString())),
                Path.of(env("NODERA_WORLD_KEYS_DIR", stateDir.resolve("world-keys").toString())));

        NodeIdentity identity = localState.identity();
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
        // The companion app's synchronised list, when there is one. Consulted only where the
        // environment is silent, so anything explicitly configured still wins.
        SyncedServices synced = SyncedServices.load(setting("NODERA_SERVICES_FILE"));
        List<TrackerClient.Endpoint> trackerEndpoints = parseTrackers(
                env("NODERA_TRACKER_ENDPOINTS", synced.trackersOr("127.0.0.1:25600")));
        List<RendezvousEndpoint> rendezvousEndpoints = parseRendezvous(
                env("NODERA_RENDEZVOUS_ENDPOINTS", synced.rendezvousOr("127.0.0.1:25601")));

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

        // What this peer keeps on the network, and which of it this peer administers.
        //
        // Both are on disk, and both are read BEFORE the hosting service is built, because the
        // hosting service restores itself from the registry at construction. Without this the
        // worker forgot every hosted and seeded world on every restart: it stopped announcing them,
        // stopped advertising the pieces it still held, and reported an empty world list to the
        // companion app — the app looked broken and the worker was the one that had lost the state.
        WorldRegistryStore worldRegistry = localState.worldRegistry();
        // The private key of every world this player created. Its presence is what makes this node
        // that world's provable administrator; it is generated locally and never accepted from the
        // network.
        WorldKeyStore worldKeys = localState.worldKeys();

        WorldHostingService hosting = new WorldHostingService(identity, caps, runtime::selfRoute,
                tracker, rendezvousEndpoints, archive::holdingsFor, worldRegistry);

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

        // Every region this worker commits is seeded into the content plane (L-41). The archive
        // lane keeps the hosted world's save alive after the driving game closes; this keeps the
        // validated lane's region state alive on the same terms, from the same always-on process.
        // The observer slot is free here by construction: it exists for the client's predict/
        // rollback view, and a headless worker renders nothing.
        validation.onCommit(new CommittedRegionSeeder(
                () -> hosting.hostedWorlds().stream()
                        .map(WorldHostingService.HostedWorld::worldIdHex).toList(),
                archive::seedRegion));

        // The permission lane (L-54): grants used to exist only in the author's own
        // nodera-permissions.dat, so a co-hosting peer's permission set was author-local — an
        // operator promotion or a ban did not exist for the rest of the mesh. Every grant this node
        // issues or receives is relayed onward and re-verified locally against the world's author
        // key, so the transport carries the decision without ever being trusted with it.
        WorldGrantGossipService grants = new WorldGrantGossipService(
                identity.nodeId(), metered, () -> runtime.sessionView().members());

        // The connection tunnel: what carries an unmodified player's game traffic to somebody
        // else's "Open to LAN" world. It moves the connection, never the world — no save is copied
        // and no chunk is replicated, which is exactly why a vanilla client can use it at all.
        dev.nodera.peer.tunnel.TunnelService tunnel =
                new dev.nodera.peer.tunnel.TunnelService(identity.nodeId(), metered);

        // The ownership lane: which peer administers each world this node serves. A supporter holds
        // a world's bytes and, without this, nothing about the authority behind them — so it could
        // neither show a player who runs the world nor tell an administrative claim from an
        // assertion. Every claim is verified locally against both its signatures; nothing here is
        // trusted because it was relayed.
        WorldOwnershipService ownership = new WorldOwnershipService(
                identity.nodeId(), metered, () -> runtime.sessionView().members());

        // The deletion lane: the owner of a world can ask the network to forget it, and every peer
        // decides for itself whether the request is really the owner's. Built before the message
        // lane is bound so no deletion can arrive while nothing is listening for it, and given the
        // hosting service as its admission gate so a deleted world cannot come back through the
        // ordinary host/seed paths.
        WorldDeletionService deletions = new WorldDeletionService(
                identity.nodeId(), metered, () -> runtime.sessionView().members(),
                hosting, worldRegistry, archive, tunnel, null);
        deletions.attachStore(new WorldTombstoneStore(
                Path.of(env("NODERA_TOMBSTONE_DIR", stateDir.resolve("deleted").toString()))));
        deletions.attachTrackers(tracker);
        hosting.refuseDeletedWorlds(deletions::isDeleted);
        // Worlds deleted while this node was down are undone here rather than left announced: the
        // registry restored them at construction, before the tombstones were read back.
        for (dev.nodera.storage.WorldTombstone tombstone : deletions.tombstones()) {
            hosting.stop(tombstone.worldIdHex());
            worldRegistry.remove(tombstone.worldIdHex());
            archive.forget(tombstone.worldIdHex());
        }

        // One application lane, five consumers: validation, archive/content, permissions,
        // ownership and deletion. Each ignores message types it does not own.
        runtime.onApplicationMessage((from, msg) -> {
            validation.onMessage(from, msg);
            archive.onMessage(from, msg);
            grants.onMessage(from, msg);
            ownership.onMessage(from, msg);
            tunnel.onMessage(from, msg);
            deletions.onMessage(from, msg);
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

        // Where this node's rendezvous points come from. Configured endpoints are seeds; the trackers
        // answer with the rest, this node scores them against its OWN probes, and it keeps the best
        // few rather than one. Three things follow that did not work before:
        //
        //   * a peer with nothing configured but a tracker reaches the network at all,
        //   * adding a rendezvous to the network reaches every peer instead of nobody,
        //   * a rendezvous restart is a migration, because the drain notice arrives on the relay's own
        //     control channel and the replacement it names is already in the candidate set.
        //
        // The sweep also reports what this node measured, which is the half of the scoring loop that
        // stops the aggregate from being nothing but the services' own self-praise.
        dev.nodera.peer.discovery.ServiceScoreBoard serviceScores =
                new dev.nodera.peer.discovery.ServiceScoreBoard();
        dev.nodera.peer.discovery.RendezvousDirectory rendezvousDirectory =
                new dev.nodera.peer.discovery.RendezvousDirectory(tracker, serviceScores,
                        dev.nodera.protocol.service.ServiceRecord.DEFAULT_NETWORK,
                        (int) envLong("NODERA_RENDEZVOUS_FANOUT",
                                dev.nodera.peer.discovery.RendezvousDirectory.DEFAULT_FANOUT),
                        rendezvousEndpoints);
        rendezvousDirectory.onEndpointsChanged(hosting::setRendezvousEndpoints);
        int directorySweepSeconds = (int) envLong("NODERA_RENDEZVOUS_SWEEP_SECONDS", 60);
        java.util.concurrent.ScheduledExecutorService directoryScheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "nodera-rendezvous-directory");
                    t.setDaemon(true);
                    return t;
                });
        // Presence in the commons namespace, so a node holding no world is still findable.
        //
        // Without this a world-less peer — every phone, and any desktop before its first share —
        // announces nothing, appears in no tracker answer, and cannot ask what worlds exist,
        // because the tracker deliberately has no full-scrape endpoint. It is reachable, healthy,
        // and invisible: observed on a handset that reported its tracker up at 67 ms while the
        // world its three desktop peers shared reported `peers 3`, none of them the phone.
        //
        // Announced unconditionally rather than only when world-less. A node that hosts something
        // is discoverable through that world, but it is also the node a phone most wants to find,
        // and dropping out of the commons the moment you share would make the swarm hardest to join
        // exactly when there is finally something in it.
        dev.nodera.peer.discovery.CommonsPresence commons =
                new dev.nodera.peer.discovery.CommonsPresence(tracker, identity.nodeId(), caps);

        directoryScheduler.scheduleWithFixedDelay(() -> {
            try {
                rendezvousDirectory.sweep(System.currentTimeMillis());
            } catch (RuntimeException e) {
                // A sweep failure degrades discovery and nothing else; killing the scheduler thread
                // would silently freeze the endpoint set at whatever it last was.
                LOG.warn("rendezvous-directory sweep failed: {}", e.getMessage());
            }
            // Guarded separately: a task that throws out of scheduleWithFixedDelay is silently
            // never run again, and the sweep above must not be taken down by the newer half.
            try {
                List<dev.nodera.protocol.membership.PeerEntry> present =
                        commons.round(runtime.selfRoute(), System.currentTimeMillis());
                for (dev.nodera.protocol.membership.PeerEntry peer : present) {
                    // Dial what the commons offered, by node id and route — the same call the
                    // mesh lane uses, so a peer met here is a peer in every other sense.
                    try {
                        runtime.joinSession(dev.nodera.transport.PeerAddress.of(
                                peer.nodeId(), peer.route()));
                    } catch (RuntimeException dial) {
                        LOG.debug("commons: {} did not answer ({})", peer.route(), dial.toString());
                    }
                }
                if (!present.isEmpty()) {
                    LOG.info("commons: {} peer(s) present", present.size());
                }
            } catch (RuntimeException e) {
                LOG.warn("commons round failed: {}", e.getMessage());
            }
        }, 5, directorySweepSeconds, java.util.concurrent.TimeUnit.SECONDS);

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

        // What this node tells local clients about things that HAPPEN, as opposed to what is true
        // of it. The companion app's "you opened a world to LAN — share it?" prompt hangs off this:
        // a client that had to notice that by diffing state snapshots would need to have been
        // connected at the moment the player pressed the button, which is a coin toss.
        dev.nodera.peer.control.WorkerEventBus events =
                new dev.nodera.peer.control.WorkerEventBus();

        // LAN detection. Optional by construction: a machine whose network stack will not let us
        // join the multicast group (a locked-down container, a VPN-only interface) still runs a
        // perfectly good peer — it just cannot notice a world being opened to LAN, and the control
        // verbs say so rather than reporting an empty list forever.
        LanSessionService lan = null;
        String lanUnavailable = "";
        if (envBool("NODERA_LAN_WATCH", true)) {
            try {
                lan = new LanSessionService(identity.nodeId(), tunnel, hosting, events);
                lan.start();
            } catch (java.io.IOException noMulticast) {
                lanUnavailable = "this machine's network stack would not let the worker listen for "
                        + "Minecraft's LAN announcements: " + noMulticast.getMessage();
                LOG.warn("Not watching for LAN worlds: {}", noMulticast.getMessage());
            }
        } else {
            // Switched off deliberately. Reported as such, because "off" and "broken" are different
            // situations and only one of them is the user's to fix.
            lanUnavailable = "LAN detection is switched off for this worker (NODERA_LAN_WATCH=0)";
            LOG.info("LAN detection disabled by NODERA_LAN_WATCH");
        }
        final LanSessionService lanSessions = lan;
        List<String> rendezvousRoutes = new ArrayList<>();
        for (RendezvousEndpoint endpoint : rendezvousEndpoints) {
            rendezvousRoutes.add(endpoint.host() + ":" + endpoint.port());
        }

        // The seams a NODERA-CONFIG push may re-bound. Passing the real services (not a copy of
        // their values) is what makes a setting change what this node does, with no second copy of
        // the configuration to drift out of sync.
        WorkerControlHandler handler = new WorkerControlHandler(
                WORKER_VERSION, identity, caps, runtime, meter, hosting, validation, archive,
                peerMeter, discovery,
                new WorkerControlHandler.ConfigSeams(
                        archive.content(), replication, transport, tracker, contentStore),
                grants, worldKeys,
                new WorkerControlHandler.LiveLanes(lan, tunnel, tracker, rendezvousRoutes,
                        lanUnavailable));
        handler.attachOwnership(ownership);
        handler.attachEvents(events);
        handler.attachDeletion(deletions);
        deletions.attachEvents(events);
        // Claims already on disk are republished once at startup: a peer that was offline while a
        // world's owner was online would otherwise never learn who administers it.
        for (dev.nodera.storage.WorldRegistry.Entry world : worldRegistry.entries()) {
            world.ownership().ifPresent(ownership::publish);
        }
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
            if (lanSessions != null) {
                lanSessions.close();
            }
            tunnel.close();
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

    /** A boolean switch from the environment; anything but an explicit off keeps the default. */
    /**
     * One setting, from the environment or from a system property of the same name.
     *
     * <p>The environment wins, because that is how this worker has always been configured and how
     * every script and CI job still configures it. The system-property fallback exists for
     * <b>Android</b>, where the worker runs inside the companion app's own process: a process cannot
     * set environment variables for itself, so without this the phone's worker would be permanently
     * stuck on its compiled-in defaults — wrong archive directory, wrong trackers, and no way to
     * say otherwise.
     *
     * @param key the {@code NODERA_*} name.
     * @return the configured value, or null when neither source has one.
     */
    /**
     * The endpoint list the companion app keeps in sync, read from a file.
     *
     * <p><b>Why a file.</b> On the desktop the app spawns this worker and hands it environment
     * variables. On Android it cannot: the worker is loaded into the app's own process, and a
     * process cannot set environment variables for itself from Java. So an Android install had no
     * way to tell its worker about a tracker at all — it fell back to {@code 127.0.0.1:25600},
     * which on a handset is the handset, and every tracker store the user added stopped at the app.
     *
     * <p>The format is one {@code <kind> <route>} pair per line, with {@code #} comments. Not JSON:
     * this runs before anything else, and a malformed line must cost one endpoint rather than the
     * worker's ability to start. Every parse failure is a skipped line.
     */
    record SyncedServices(List<String> trackers, List<String> rendezvous) {

        static SyncedServices empty() {
            return new SyncedServices(List.of(), List.of());
        }

        /**
         * Read the file, or return nothing for any reason at all.
         *
         * @param path the configured path, or {@code null} to look in the default location.
         */
        static SyncedServices load(String path) {
            java.nio.file.Path file;
            if (path != null && !path.isBlank()) {
                file = java.nio.file.Path.of(path.trim());
            } else {
                // The directory the worker derives everything else from, so a desktop install that
                // never sets the variable still finds a file the app wrote.
                file = java.nio.file.Path.of(
                        System.getProperty("user.home", "."), ".nodera", "nodera-services.list");
            }
            if (!java.nio.file.Files.isReadable(file)) {
                return empty();
            }
            List<String> trackers = new ArrayList<>();
            List<String> rendezvous = new ArrayList<>();
            try {
                for (String line : java.nio.file.Files.readAllLines(file)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    String[] parts = trimmed.split("\\s+", 2);
                    if (parts.length != 2 || parts[1].isBlank()) {
                        continue;
                    }
                    switch (parts[0]) {
                        case "tracker" -> trackers.add(parts[1].trim());
                        case "rendezvous" -> rendezvous.add(parts[1].trim());
                        default -> { /* a kind this build does not know; skip it */ }
                    }
                }
            } catch (java.io.IOException | RuntimeException e) {
                // A worker that refuses to start because a cache file is malformed is worse than one
                // that starts on its defaults and says so.
                LOG.warn("Nodera: could not read the synced service list {}: {}", file, e.toString());
                return empty();
            }
            LOG.info("Nodera: synced services from {} ({} tracker(s), {} relay(s))",
                    file, trackers.size(), rendezvous.size());
            return new SyncedServices(List.copyOf(trackers), List.copyOf(rendezvous));
        }

        String trackersOr(String fallback) {
            return trackers.isEmpty() ? fallback : String.join(",", trackers);
        }

        String rendezvousOr(String fallback) {
            return rendezvous.isEmpty() ? fallback : String.join(",", rendezvous);
        }
    }

    private static String setting(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = System.getProperty(key);
        }
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean envBool(String key, boolean fallback) {
        String v = setting(key);
        if (v == null) {
            return fallback;
        }
        String value = v.trim().toLowerCase(java.util.Locale.ROOT);
        return !(value.equals("0") || value.equals("false") || value.equals("no"));
    }

    private static String env(String key, String fallback) {
        String v = setting(key);
        return v == null ? fallback : v;
    }

    private static long envLong(String key, long fallback) {
        String v = setting(key);
        if (v == null) {
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
