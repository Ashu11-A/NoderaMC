package dev.nodera.headless;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.core.services.DefaultServices;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Task 32: the Nodera <b>node</b> — the always-on, Minecraft-free peer. It is what keeps a player
 * part of the network even with Minecraft closed: it boots a {@link PeerRuntime} over a real socket,
 * holds a <em>persistent</em> identity (so the node keeps its {@code NodeId} across restarts, L-28),
 * and serves the loopback {@link ControlServer} the Minecraft mod probes at startup — the mod
 * refuses to launch if this node is not running.
 *
 * <p><b>This class is the only way to build a peer.</b> That is the point of it. The composition
 * used to live inside {@code HeadlessPeerMain.main}, in a module nothing else could depend on, so
 * every other embedder — the mod, the Android host, a test — assembled its own subset and a peer
 * that hosted nothing, seeded nothing and answered no control verb was a perfectly ordinary thing
 * to construct. {@link #start} always builds every always-on service, so "a peer is a worker" is a
 * property of the code rather than a convention somebody has to remember.
 *
 * <p>{@code HeadlessPeerMain} (in {@code src/headless}) is a six-line shell over this: parse argv,
 * {@link #start}, {@link #await}. It is separate so that a launchable {@code main} cannot reach the
 * NeoForge mod's fat jar, which bundles this module.
 *
 * <p>The Tauri companion app ({@code rust/nodera-app}) supervises the process (auto-launch at login,
 * tray, dashboard); {@code scripts/dev.sh} can also run it directly for development. Desktop
 * configuration is environment-driven; the in-process Android worker uses system properties where
 * it cannot receive a process environment.
 *
 * <p>Thread-context: {@link #start} runs on the caller's thread and returns once every service is
 * listening. {@link #close} is idempotent and also runs from the JVM shutdown hook.
 */
public final class PeerNode implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaWorker");

    /**
     * This worker's build version, reported on the control probe (mirrors the mod's expectation).
     *
     * <p>The product version, not a worker-specific one: the app compares what the worker reports
     * with what it was shipped alongside, and two independently maintained numbers made that
     * comparison meaningless. Source of truth is the root {@code VERSION} file.
     */
    public static final String WORKER_VERSION = dev.nodera.core.NoderaConstants.PRODUCT_VERSION;

    /** Counted down by {@link #close}; {@link #await} blocks on it. */
    private final CountDownLatch stopped = new CountDownLatch(1);

    /** Everything {@link #close} must unwind, in the order it must unwind in. */
    private final List<AutoCloseable> shutdown;

    private final WorkerTelemetryService telemetry;
    private final long startedAtMillis;

    private PeerNode(WorkerTelemetryService telemetry, long startedAtMillis,
                     List<AutoCloseable> shutdown) {
        this.telemetry = telemetry;
        this.startedAtMillis = startedAtMillis;
        this.shutdown = shutdown;
    }

    // No `nodeId()` / `runtime()` accessors here on purpose. They were written, and the structural
    // report caught them the same hour: nothing called either, and this repository's dominant
    // defect is code that is implemented, tested, and has zero production call sites. Add an
    // accessor when an embedder needs it, not in case one might.

    /** Block until {@link #close} runs, whether from a signal or from a caller. */
    public void await() throws InterruptedException {
        stopped.await();
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

    /**
     * Build and start every always-on service, and return once they are all listening.
     *
     * <p>Everything about a node except {@code testMode} comes from the environment, because the
     * supervisor that starts it — the Tauri app, a script, Android — passes an environment and not
     * an argv. Test mode is the exception on purpose: a remote-control surface must be something a
     * human deliberately started, not something a file another program can write turns on.
     *
     * @param testMode whether this node answers NODERA-TEST, and as which player.
     * @return the running node. Close it, or {@link #await} it and let the shutdown hook close it.
     */
    public static PeerNode start(TestMode testMode) throws Exception {
        long startedAtMillis = System.currentTimeMillis();
        String controlHost = env("NODERA_CONTROL_HOST", "127.0.0.1");
        // Control stays environment-only: the desktop supervisor and its Rust client inherit the
        // same value. Android has no environment handoff and both sides deliberately keep 25610;
        // accepting an independent Java property here could strand the app on the wrong endpoint.
        int controlPort = envInt("NODERA_CONTROL_PORT", 25610);
        String bindHost = env("NODERA_P2P_BIND", "0.0.0.0");
        int p2pPort = settingInt("NODERA_P2P_PORT", 25620);
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
        // R2 (network L-87): declare the rule set and registry this worker re-executes under, so the
        // handshake can answer a skew in one frame instead of letting it surface as an exception from
        // inside the region engine minutes later. These are the same two values the validation lane
        // is built with below — a profile that named anything else would be a lie about this peer.
        runtime.setLocalProfile(dev.nodera.protocol.session.Negotiation.LocalProfile.of(
                WORKER_VERSION,
                dev.nodera.simulation.rules.FlatWorldRules.RULES_VERSION,
                dev.nodera.simulation.rules.FlatWorldRules.registryFingerprint(),
                dev.nodera.protocol.service.ServiceRecord.DEFAULT_NETWORK, caps));

        // Discovery services this worker announces hosted worlds to (Task 32 live lane). Defaults
        // match the mod's DEFAULT_TRACKER/RENDEZVOUS_ENDPOINTS — both read them from
        // `DefaultServices`, which is the published service index compiled in, or the localhost stack
        // in development mode. The Tauri supervisor (or scripts/dev.sh) can override with the two
        // env vars below.
        //
        // The last fallback used to be `127.0.0.1:25600` unconditionally. On a phone, where the app
        // is the only thing that can supply a list and does so through the file below, a worker that
        // started before the file was written announced to the handset itself and stayed there.
        // The official list is the honest floor: reachable from anywhere, and the same addresses the
        // app's built-in store carries.
        SyncedServices synced = SyncedServices.load(setting("NODERA_SERVICES_FILE"));
        List<TrackerClient.Endpoint> trackerEndpoints = parseTrackers(
                env("NODERA_TRACKER_ENDPOINTS", synced.trackersOr(
                        DefaultServices.joined(DefaultServices.trackerEndpoints()))));
        List<RendezvousEndpoint> rendezvousEndpoints = parseRendezvous(
                env("NODERA_RENDEZVOUS_ENDPOINTS", synced.rendezvousOr(
                        DefaultServices.joined(DefaultServices.rendezvousEndpoints()))));

        // ONE tracker client for the whole node. The archive lane, the hosting/announce lane, peer
        // discovery and replication each used to hold their own — four announce cadences and four
        // copies of the endpoint list describing a single node, and no single place to change them,
        // which is precisely why the tracker list used to be a restart-required setting. Sharing one
        // client makes TrackerClient.setEndpoints reach every lane at once.
        TrackerClient tracker = new TrackerClient(trackerEndpoints, identity);
        // An explicitly configured tracker list is the operator's, and the companion app's config
        // push must not be able to take it away — it replaced the list outright, so attaching an app
        // to a worker started against a LAN tracker silently moved that worker onto the app's
        // trackers instead. Pinned only when somebody actually stated a list: a worker the app
        // itself launched has no operator list, and the app must stay in full control of that one.
        //
        // Both sources count, and the second is why this is not just the env var. `NODERA_TRACKER_
        // ENDPOINTS` is unreachable on Android — a process cannot set its own environment there, so
        // the app hands the worker a services FILE instead. Keying the pin on the env var alone
        // therefore meant `pinned` was empty on every phone that has ever run this worker, and the
        // one mechanism built to stop a config push from silently rewriting a node's discovery
        // plane did not exist on the platform where the app is the only thing that pushes.
        if (setting("NODERA_TRACKER_ENDPOINTS") != null || synced.hasTrackers()) {
            tracker.pinEndpoints(trackerEndpoints);
        }

        // The world-archive lane (the continuity increment): this worker seeds the canonical
        // archives of the worlds it hosts and can fetch any world's archive from the swarm, so a
        // shared world's save bytes survive the hosting player's game — and machine — going away.
        // Usually a directory path. On Android it may instead be a `content://` document tree —
        // the folder the user picked with the system file manager, which has no filesystem path at
        // all (frontend M-1). ArchiveDirectories is the one place that decides which it is.
        String archiveLocation = env("NODERA_ARCHIVE_DIR",
                System.getProperty("user.home") + "/.nodera/archive");
        // Held by reference so the archive directory is a live setting rather than a
        // restart-required one that strands what this node is already seeding (L-58).
        dev.nodera.storage.fs.FsContentStore contentStore =
                new dev.nodera.storage.fs.FsContentStore(
                        ArchiveDirectories.open(archiveLocation),
                        new dev.nodera.core.crypto.HashService());
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
                tracker, rendezvousEndpoints, archive::holdingsFor, worldRegistry, worldKeys);
        // W-DUP-1: reconcile every registry row against the world this node can still SERVE. A row
        // restored from `worlds.dat` whose bytes are gone from the content plane — a save deleted
        // outside the worker, an archive directory wiped — used to be re-announced on every start
        // forever, sending joiners to a node that cannot honour them. A world with no archived
        // content AND no live game route is suppressed from the announce set on the next refresh
        // cycle; its row is kept, so re-fetching the content reinstates it under the same identity.
        hosting.bindServability(worldIdHex -> archive.newestManifest(worldIdHex).isPresent());

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
        // A seat names the state it is being taken on, and this is how the worker obtains it. Before
        // this, every member derived an all-air base — the only state everyone could produce without
        // a transfer — so the validated lane held air plus whatever edits it had witnessed, never
        // the world the players were standing in. Returning null refuses the seat, which is the
        // right answer: validating a world this node does not hold is worse than not validating.
        validation.regionBaseSource((region, indexRoot) -> {
            // A region names a dimension, not a world, so the world is whichever one this worker
            // holds that knows about this region. In practice a worker is seated for one session at
            // a time; iterating is what keeps that an observation rather than an assumption.
            for (var world : hosting.hostedWorlds()) {
                try {
                    return archive.fetchRegion(world.worldIdHex(), region, indexRoot,
                            java.time.Duration.ofSeconds(30));
                } catch (RuntimeException unavailable) {
                    LOG.debug("world {} could not supply the base for {}: {}",
                            world.worldIdHex(), region, unavailable.toString());
                }
            }
            LOG.warn("no hosted world could supply the base for {}", region);
            return null;
        });

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
        // …and the one exception to that refusal: the owner sharing the world again. The tombstone
        // itself carries the ownership claim, and the world's private key is on this disk if this
        // node is the administrator, so the restore can be minted and signed from what is already
        // here — no state had to be kept between the delete and the re-share.
        hosting.reviveOnOwnerReshare(worldIdHex -> {
            java.util.Optional<dev.nodera.storage.WorldTombstone> held =
                    deletions.tombstone(worldIdHex);
            if (held.isEmpty()) {
                return false;
            }
            java.util.Optional<dev.nodera.storage.WorldOwnership> claim = held.get().ownership();
            if (claim.isEmpty() || !claim.get().isOwner(identity.nodeId())) {
                return false; // somebody else's world: the deletion stands
            }
            java.util.Optional<dev.nodera.storage.PersistedWorldKey> worldKey =
                    worldKeys.load(worldIdHex);
            if (worldKey.isEmpty()) {
                LOG.warn("Cannot restore world {} — this node owns it but no longer holds its key",
                        worldIdHex);
                return false;
            }
            dev.nodera.storage.WorldRevival revival;
            try {
                revival = dev.nodera.storage.WorldRevival.create(identity, worldKey.get(),
                        claim.get(), "shared again by its owner", System.currentTimeMillis());
            } catch (RuntimeException e) {
                LOG.warn("Cannot restore world {}: {}", worldIdHex, e.getMessage());
                return false;
            }
            WorldDeletionService.Outcome outcome = deletions.publish(revival);
            if (outcome.error() != null) {
                LOG.warn("Restore of world {} refused: {}", worldIdHex, outcome.error());
                return false;
            }
            LOG.info("World {} shared again by its owner — the deletion is withdrawn from {} peer(s)"
                    + " and every tracker this node announces to", worldIdHex,
                    outcome.peersNotified());
            return true;
        });
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
        if (testMode.enabled()) {
            testMode.bind(events);
            handler.attachTestMode(testMode);
            // Announced rather than silent: a worker with a remote-control surface open should say
            // so in the first lines of its log, and an integration run greps this to prove the role
            // it asked for is the role that started.
            LOG.info("NODERA-TEST mode active — role {}{}", testMode.role(),
                    testMode.debug() ? " (debug)" : "");
        }
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

        // Say what the endpoint will and will not touch. A verb refused for naming a path outside
        // these roots reports the path but deliberately not the roots, so this line is where an
        // operator finds out why — without it the guard is correct and undebuggable.
        LOG.info("Control endpoint may read/write under: {}",
                ControlPaths.fromEnvironment().roots());
        ControlServer control = new ControlServer(controlHost, controlPort, handler);
        control.start();

        LOG.info("Nodera peer worker {} online — node {} listening {}, control {}:{}, "
                        + "{} tracker(s) / {} rendezvous",
                WORKER_VERSION, identity.nodeId(), runtime.selfRoute(), controlHost,
                control.boundPort(), trackerEndpoints.size(), rendezvousEndpoints.size());

        // Unwind order is load-bearing and is the reason this is a list built here rather than a
        // set of fields closed in whatever order somebody writes later: telemetry reports the stop
        // before its transport goes away, the tracker client is shared by every lane above it and
        // so is closed exactly once after all of them, and the runtime stops last.
        List<AutoCloseable> unwind = new ArrayList<>();
        if (lanSessions != null) {
            unwind.add(lanSessions);
        }
        unwind.add(tunnel);
        unwind.add(discovery);
        unwind.add(replication);
        unwind.add(hosting);
        unwind.add(control);
        unwind.add(archive);
        unwind.add(tracker); // shared by every lane above, so it is closed once, here.
        unwind.add(runtime::stop);

        PeerNode node = new PeerNode(telemetry, startedAtMillis, unwind);
        Runtime.getRuntime().addShutdownHook(new Thread(node::close, "nodera-worker-shutdown"));
        return node;
    }

    /**
     * Stop every service, in the order they were started in reverse.
     *
     * <p>Idempotent, and deliberately so: it runs from the JVM shutdown hook AND from any caller
     * that used the node in a try-with-resources, and on a normal exit both happen.
     *
     * <p>A service that throws on close does not stop the ones after it. A half-unwound node is how
     * a port stays bound after the process that held it is gone, which is a failure the next start
     * reports as "address already in use" and blames on the wrong thing.
     */
    @Override
    public void close() {
        if (stopped.getCount() == 0) {
            return;
        }
        LOG.info("Nodera peer worker shutting down");
        telemetry.record(dev.nodera.telemetry.TelemetryEvent
                .named(dev.nodera.telemetry.TelemetryRegistry.SERVICE_STOP,
                        System.currentTimeMillis())
                .number("uptime_hours_bucket", dev.nodera.telemetry.Buckets.hours(
                        System.currentTimeMillis() - startedAtMillis))
                .flag("clean", true)
                .build());
        closeQuietly(telemetry);
        for (AutoCloseable service : shutdown) {
            closeQuietly(service);
        }
        stopped.countDown();
    }

    private static void closeQuietly(AutoCloseable service) {
        try {
            service.close();
        } catch (Exception e) {
            LOG.warn("{} did not shut down cleanly", service.getClass().getSimpleName(), e);
        }
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

        /**
         * Whether somebody actually stated a tracker list here.
         *
         * <p>The distinction the pin depends on: endpoints that came from this file were chosen,
         * and endpoints that came from the compiled-in defaults were merely not overridden. Only
         * the first kind is an authority worth protecting from a later config push.
         */
        boolean hasTrackers() {
            return !trackers.isEmpty();
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

    /** Environment-first integer setting with a system-property fallback for in-process Android. */
    static int settingInt(String key, int fallback) {
        String v = setting(key);
        if (v == null) {
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

    /**
     * Resolve {@code "auto"} to a best-guess site-local IPv4; otherwise return the literal host.
     *
     * <p>Shared with the mod's own peer service through
     * {@link dev.nodera.core.net.NetworkAddresses} — see the note there on why "the first
     * site-local address" picks a VPN tunnel on a developer machine, and why a node that advertises
     * one is unreachable in a way it cannot detect about itself.
     */
    private static String resolveHost(String configured) {
        String resolved = dev.nodera.core.net.NetworkAddresses.resolveHost(configured);
        if (dev.nodera.core.net.NetworkAddresses.LOOPBACK.equals(resolved)
                && (configured == null || configured.isBlank()
                    || configured.equalsIgnoreCase("auto"))) {
            LOG.warn("no reachable LAN address was found to advertise — falling back to {}. Other"
                    + " peers will not be able to dial this node; set NODERA_P2P_ADVERTISE if this"
                    + " machine's real address cannot be detected.", resolved);
        }
        return resolved;
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
