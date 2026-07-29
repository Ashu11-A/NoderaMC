package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.peer.discovery.TrackerClient;
import dev.nodera.storage.PersistedWorldKey;
import dev.nodera.storage.WorldOwnership;
import dev.nodera.protocol.discovery.AnnounceEvent;
import dev.nodera.protocol.rendezvous.CandidateKind;
import dev.nodera.protocol.rendezvous.PeerCandidate;
import dev.nodera.protocol.rendezvous.RegistrationEvent;
import dev.nodera.protocol.rendezvous.SignedRecord;
import dev.nodera.transport.rendezvous.RendezvousClient;
import dev.nodera.transport.rendezvous.RendezvousEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Task 32/33 (live lane): the worker's world-hosting engine — the part that makes a shared world
 * <b>persist on the network after Minecraft closes</b>. When the mod presses "Share" it hands the
 * world off to this worker over the control endpoint ({@code NODERA-HOST}); from then on the worker,
 * not the game, keeps the world <em>discoverable</em>: it announces the world to every configured
 * {@code nodera-tracker} (STARTED, then HEARTBEAT on the tracker's cadence) and registers the host's
 * reachable route with every {@code nodera-rendezvous} service so cross-NAT joiners can find and dial
 * it. On {@code NODERA-STOP} it announces STOPPED and unregisters.
 *
 * <p>This is the same tracker/rendezvous protocol the mod's {@code NoderaPeerService} spoke in-process
 * (Task 30); moving it into the always-on worker is exactly what Task 32 requires — the host no longer
 * dies with the game. The heavy P2P membership stays in the worker's {@link dev.nodera.peer.PeerRuntime};
 * this service is the discovery/announce layer on top of it.
 *
 * <p>Reachability of each tracker/rendezvous endpoint is probed on a background cadence and surfaced
 * to the companion dashboard (the "VPN server list" health rows) without blocking a control reply.
 *
 * <p>Thread-context: all mutators are safe from any thread; hosted-world state is held in concurrent
 * maps and the announce/refresh/health work runs on a single daemon scheduler.
 */
public final class WorldHostingService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaWorker");

    /** How long a rendezvous registration record is valid before a refresh must renew it. */
    private static final Duration REGISTRATION_TTL = Duration.ofMinutes(5);

    /** Route-claim prefix marking a Minecraft game endpoint (vs a bare P2P {@code host:port}). */
    public static final String MC_ROUTE_PREFIX = "mc/";

    /** Endpoint-health probe cadence + how stale a reachability reading may be. */
    private static final int HEALTH_PROBE_SECONDS = 15;

    /** Floor on the announce cadence: a tracker cannot ask this node to announce in a tight loop. */
    static final int MINIMUM_REFRESH_SECONDS = 15;
    /**
     * How long a reachability probe waits for a TCP handshake.
     *
     * <p>Tied to {@link dev.nodera.protocol.service.ServiceScore#LATENCY_CEILING_MILLIS}, above
     * which a service scores zero anyway: a probe that gives up sooner than the scorer would is a
     * probe that reports a usable service as dead.
     *
     * <p>It was 400 ms, which is less than one round trip to most of the planet. Measured on a
     * handset behind a VPN, reaching the project's own Frankfurt tracker:
     *
     * <pre>
     *   port 6970 (closed)  652 ms   connection refused
     *   port 22   (open)   1105 ms   handshake completed
     *   port 6969 (open)   1103 ms   handshake completed
     * </pre>
     *
     * The service was reachable, answering, and reported as "none reachable from this network" —
     * because 1103 &gt; 400. A developer on the same LAN as their tracker (47 ms) never sees it, and
     * every user on another continent, a mobile network, or a VPN sees nothing else.
     */
    private static final int PROBE_TIMEOUT_MILLIS =
            dev.nodera.protocol.service.ServiceScore.LATENCY_CEILING_MILLIS + 500;

    private final NodeIdentity identity;
    private final NodeCapabilities capabilities;
    /** Supplies the worker's currently-advertised P2P route (may change as the runtime settles). */
    private final Supplier<String> selfRoute;

    /**
     * The rendezvous points in use, best first.
     *
     * <p><b>Volatile and replaceable</b> ({@link #setRendezvousEndpoints}). The set is discovered from
     * trackers and re-scored on a cadence now, so losing a relay or gaining a better one has to reach
     * the register, refresh and probe paths at once — and it moves "rendezvous endpoints" from a
     * restart-required setting to a live one, which is what makes a relay restart survivable.
     */
    private volatile List<RendezvousEndpoint> rendezvousEndpoints;
    private final TrackerClient tracker;
    private final RendezvousClient rendezvous;

    /** L-38 coordinated retention: countdown on zero-seeder worlds, cancel on return. */
    private final dev.nodera.peer.archival.RetentionPolicy retention =
            new dev.nodera.peer.archival.RetentionPolicy();

    /** @return the retention policy driving the announce's decommission deadline (L-38). */
    public dev.nodera.peer.archival.RetentionPolicy retention() {
        return retention;
    }

    private final Map<String, HostedWorld> worlds = new ConcurrentHashMap<>();
    private final Map<String, dev.nodera.transport.Reachability.Probe> trackerReachable =
            new ConcurrentHashMap<>();
    private final Map<String, dev.nodera.transport.Reachability.Probe> rendezvousReachable =
            new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;

    /** worldIdHex → the piece-bitmap holdings to ride that world's announce (archive lane). */
    private final java.util.function.Function<String,
            List<dev.nodera.protocol.content.ManifestHolding>> holdingsFor;

    /**
     * The on-disk record of what this peer keeps on the network, or {@code null} when this
     * embedding is memory-only (the control-verb tests).
     *
     * <p>With it, a worker restart resumes announcing what it was announcing. Without it — which is
     * how this class shipped — a restart silently stopped announcing everything, and the node kept
     * the world's bytes on disk while telling every tracker it had none.
     */
    private final WorldRegistryStore registry;

    /**
     * The private keys of the worlds this node created, or {@code null} when this embedding keeps
     * none. Its presence is what lets a stopped-then-re-seeded world read as administered again
     * (W-DUP-2): the key survives {@code stop} on disk, so re-binding the ownership claim from it
     * restores the badge without minting a second identity.
     */
    private final WorldKeyStore keys;

    /**
     * Worlds this node must refuse to serve, whatever asks it to (see
     * {@link #refuseDeletedWorlds}). Defaults to refusing nothing.
     */
    private volatile java.util.function.Predicate<String> deleted = worldIdHex -> false;

    /**
     * Whether this node can still <b>serve</b> each world it holds (W-DUP-1). Defaults to every
     * world being servable. A world that fails this test is <b>suppressed from the announce set</b>
     * within one refresh cycle but its row is kept — in memory and in the registry — so a repair can
     * reinstate it rather than mint a second identity for the save.
     */
    private volatile java.util.function.Predicate<String> servable = worldIdHex -> true;

    /** A hosting service with no archive lane: its announces carry no piece holdings. */
    public WorldHostingService(NodeIdentity identity, NodeCapabilities capabilities,
                               Supplier<String> selfRoute,
                               List<TrackerClient.Endpoint> trackerEndpoints,
                               List<RendezvousEndpoint> rendezvousEndpoints) {
        this(identity, capabilities, selfRoute, trackerEndpoints, rendezvousEndpoints,
                worldId -> List.of());
    }

    public WorldHostingService(NodeIdentity identity, NodeCapabilities capabilities,
                               Supplier<String> selfRoute,
                               List<TrackerClient.Endpoint> trackerEndpoints,
                               List<RendezvousEndpoint> rendezvousEndpoints,
                               java.util.function.Function<String,
                                       List<dev.nodera.protocol.content.ManifestHolding>> holdingsFor) {
        this(identity, capabilities, selfRoute,
                new TrackerClient(List.copyOf(trackerEndpoints), identity),
                rendezvousEndpoints, holdingsFor);
    }

    /**
     * As above, but announcing through the node's <b>one shared</b> {@link TrackerClient} rather
     * than a private instance.
     *
     * <p>This service, the archive lane, peer discovery and replication used to each hold their own
     * client — four announce cadences and four copies of the endpoint list describing a single
     * node. Sharing one client makes the tracker list a live setting
     * ({@link TrackerClient#setEndpoints}) instead of a restart-required one: this class reads
     * {@code tracker.endpoints()} on every announce and health probe, so a change is picked up on
     * the next cadence with no reconstruction.
     *
     * <p>The shared client is <b>not</b> closed by {@link #close()} — its owner (the worker main)
     * closes it once.
     */
    public WorldHostingService(NodeIdentity identity, NodeCapabilities capabilities,
                               Supplier<String> selfRoute,
                               TrackerClient sharedTracker,
                               List<RendezvousEndpoint> rendezvousEndpoints,
                               java.util.function.Function<String,
                                       List<dev.nodera.protocol.content.ManifestHolding>> holdingsFor) {
        this(identity, capabilities, selfRoute, sharedTracker, rendezvousEndpoints, holdingsFor, null);
    }

    /**
     * As above, plus the persisted world registry this service restores from and writes through to.
     *
     * @param registry the on-disk record of the worlds this peer shares and supports, or
     *                 {@code null} for a memory-only service. When present, every world it holds is
     *                 restored and re-announced here, at construction — which is what makes the
     *                 worker's promise ("your world stays on the network") survive the worker's own
     *                 restart.
     */
    public WorldHostingService(NodeIdentity identity, NodeCapabilities capabilities,
                               Supplier<String> selfRoute,
                               TrackerClient sharedTracker,
                               List<RendezvousEndpoint> rendezvousEndpoints,
                               java.util.function.Function<String,
                                       List<dev.nodera.protocol.content.ManifestHolding>> holdingsFor,
                               WorldRegistryStore registry) {
        this(identity, capabilities, selfRoute, sharedTracker, rendezvousEndpoints, holdingsFor,
                registry, null);
    }

    /**
     * As above, plus the world-key store that holds this node's administrator keys.
     *
     * @param registry the on-disk record of the worlds this peer shares and supports, or
     *                 {@code null} for a memory-only service. When present, every world it holds is
     *                 restored and re-announced here, at construction — which is what makes the
     *                 worker's promise ("your world stays on the network") survive the worker's own
     *                 restart.
     * @param keys     the private keys of the worlds this node created, or {@code null} when this
     *                 embedding keeps none. When present, a world whose registry row lost its
     *                 ownership record (a stop that removed the row, a corrupted claim) is
     *                 re-administered from its on-disk key, so ownership survives stop → re-seed
     *                 without re-deriving a second identity (W-DUP-2).
     */
    public WorldHostingService(NodeIdentity identity, NodeCapabilities capabilities,
                               Supplier<String> selfRoute,
                               TrackerClient sharedTracker,
                               List<RendezvousEndpoint> rendezvousEndpoints,
                               java.util.function.Function<String,
                                       List<dev.nodera.protocol.content.ManifestHolding>> holdingsFor,
                               WorldRegistryStore registry, WorldKeyStore keys) {
        this.registry = registry;
        this.keys = keys;
        this.identity = identity;
        this.capabilities = capabilities;
        this.selfRoute = selfRoute;
        this.holdingsFor = holdingsFor;
        this.rendezvousEndpoints = List.copyOf(rendezvousEndpoints);
        this.tracker = java.util.Objects.requireNonNull(sharedTracker, "tracker");
        this.rendezvous = new RendezvousClient(identity, Duration.ofSeconds(3), Duration.ofSeconds(5));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nodera-worker-hosting");
            t.setDaemon(true);
            return t;
        });
        // Refresh announces/registrations + endpoint health on a fixed cadence.
        int refresh = refreshIntervalSeconds();
        scheduler.scheduleWithFixedDelay(this::refreshAll, refresh, refresh, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::probeHealth, 0, HEALTH_PROBE_SECONDS, TimeUnit.SECONDS);
        restoreFromRegistry();
    }

    /**
     * Bind the test that decides whether a world has been deleted at its owner's request.
     *
     * <p>Both ways back onto the network pass through {@link #host} and {@link #seed} — the control
     * verbs a game or the app drives, and the replication lane adopting a world it was placed for.
     * Putting the check here rather than at each caller means a lane added later inherits it instead
     * of having to remember it, which matters because the failure mode is silent: a deleted world
     * quietly comes back and nothing logs that anything went wrong.
     *
     * @param isDeleted answers "has this world been deleted"; never null.
     */
    public void refuseDeletedWorlds(java.util.function.Predicate<String> isDeleted) {
        this.deleted = java.util.Objects.requireNonNull(isDeleted, "isDeleted");
    }

    /**
     * Bind the test that decides whether a world this node holds can still be <b>served</b>.
     *
     * <p>A world that fails this test (its save vanished, its content store emptied, its bytes
     * unrecoverable) is <b>suppressed from the announce set</b> on the next refresh cycle: the
     * worker stops telling trackers and rendezvous services about it, so a joiner is no longer sent
     * to a node that cannot honour the request. The row is <b>retained</b> — in the live set and in
     * the registry — so a repair (the save coming back, the content being re-fetched) reinstates it
     * under the same identity instead of minting a second one.
     *
     * <p>Without this, a registry row was re-announced on every worker start forever: nothing
     * reconciled a row against the world still being servable, so a stale entry outlived the save
     * it described (W-DUP-1).
     *
     * @param servable answers "can this node still serve this world"; never null.
     */
    public void bindServability(java.util.function.Predicate<String> servable) {
        this.servable = java.util.Objects.requireNonNull(servable, "servable");
    }

    /**
     * Run one announce-reconciliation pass immediately rather than waiting for the refresh cadence.
     *
     * <p>The same pass the scheduler runs on the worker's refresh interval: every world this node
     * holds is tested for servability, unservable ones are marked suppressed and skipped, and the
     * rest are re-announced. Exposed so a caller that knows servability changed (a save was moved, a
     * repair completed) can reconcile at that moment, and so a test does not have to race the
     * scheduler to observe the suppression.
     */
    public void reconcile() {
        refreshAll();
    }

    /**
     * Repopulate the live world set from the persisted registry and announce every world again.
     *
     * <p>The announce is scheduled rather than performed inline because construction happens on the
     * worker's main thread while the transport is still settling: {@code selfRoute} may not have a
     * value yet, and announcing an empty route would list this node as unreachable for its own
     * worlds. The hosting scheduler runs it a moment later, by which time the route is bound; the
     * regular heartbeat then keeps it fresh either way.
     */
    private void restoreFromRegistry() {
        if (registry == null) {
            return;
        }
        for (dev.nodera.storage.WorldRegistry.Entry entry : registry.entries()) {
            HostedWorld world = new HostedWorld(entry.worldIdHex(), entry.worldId(), entry.name(),
                    UUID.nameUUIDFromBytes(entry.worldId().toArray()),
                    entry.addedAtEpochMillis(), entry.updatedAtEpochMillis());
            world.seeding = entry.supporting();
            // Liveness is NOT restored: mcRoute stays null and players stays 0 until a running game
            // says otherwise. A restored world is "shared, game closed", which is the truth.
            bindOwnershipRecord(world, entry.ownershipRecord());
            // W-DUP-2: a row whose ownership record was lost (a stop that removed and re-seeded it,
            // a claim that stopped verifying) is re-administered from this node's on-disk key, so
            // ownership survives a restart without re-deriving a second identity for the save.
            if (rebindOwnershipFromKeyStore(world) && registry != null) {
                registry.put(entry.worldIdHex(), world.name, world.seeding, world.ownershipRecord);
            }
            worlds.put(entry.worldIdHex(), world);
        }
        if (worlds.isEmpty()) {
            return;
        }
        LOG.info("Resuming {} world(s) from the registry — {} administered by this node",
                worlds.size(),
                worlds.values().stream().filter(HostedWorld::owned).count());
        scheduler.execute(this::refreshAll);
    }

    /**
     * Begin hosting a world on the network: record it, announce STARTED to the trackers, and register
     * the host route with the rendezvous services. Idempotent — re-hosting the same id just refreshes.
     *
     * @param worldIdHex  the world identity, hex-encoded (as the mod sends it).
     * @param worldName   the display name honoured by the tracker (worker is the FULL_ARCHIVE host).
     * @param optionsJson the share options JSON (currently informational).
     * @return {@code null} on success, or a short error message.
     */
    public String host(String worldIdHex, String worldName, String optionsJson) {
        if (worldIdHex == null || worldIdHex.isBlank()) {
            return "missing worldId";
        }
        String id = key(worldIdHex);
        if (deleted.test(id)) {
            return "this world was deleted by its owner";
        }
        Bytes worldId;
        try {
            worldId = Bytes.fromHex(id);
        } catch (RuntimeException e) {
            return "malformed worldId";
        }
        String name = worldName == null || worldName.isBlank() ? id : worldName;
        HostedWorld world = worlds.compute(id, (key, existing) -> {
            if (existing == null) {
                return new HostedWorld(key, worldId, name, UUID.nameUUIDFromBytes(worldId.toArray()));
            }
            existing.name = name;
            // Hosting is the stronger claim: a world this node was merely seeding is promoted.
            existing.seeding = false;
            return existing;
        });
        // A re-HOST is the mod's refresh path: it updates the game endpoint (present while the
        // hosting player's game is open, absent once it closes) and the live player count. The
        // next announce/heartbeat carries the change to every tracker.
        world.mcRoute = jsonStringField(optionsJson, "mc");
        // Through the lease, not straight onto the field: a HOST is an observation by a node that
        // has the game open, and it stops being credible when that game stops refreshing it. The
        // hosting mod re-sends this on a cadence for exactly that reason.
        markPlayers(id, jsonLongField(optionsJson, "players"), PLAYERS_LEASE_SECONDS);
        rebindOwnershipFromKeyStore(world);
        record(world);
        announce(world, AnnounceEvent.STARTED);
        registerRendezvous(world, RegistrationEvent.REGISTER);
        LOG.info("Now hosting world '{}' ({}) on {} tracker(s) / {} rendezvous (game endpoint: {})",
                name, shortId(worldIdHex), tracker.endpoints().size(), rendezvousEndpoints.size(),
                world.mcRoute == null ? "offline" : world.mcRoute);
        return null;
    }

    /**
     * Begin <b>seeding</b> a world this node does not host: keep its content available to the
     * network and advertise the pieces held for it, without claiming to run the world.
     *
     * <p>This is what makes a shared world the <i>network's</i> rather than its author's. Hosting
     * announces "the game lives here"; seeding announces "the bytes live here too". A world with
     * only its host as a holder dies with that host's machine no matter how many peers can see it
     * listed — which is exactly the failure the archive lane exists to prevent.
     *
     * @param worldIdHex the world identity, hex-encoded.
     * @param worldName  the display name learned from the tracker directory (kept so a seeder's
     *                   announce does not blank a world's name for everyone).
     * @return {@code null} on success, or a short error message.
     * @Thread-context any thread.
     */
    public String seed(String worldIdHex, String worldName) {
        if (worldIdHex == null || worldIdHex.isBlank()) {
            return "missing worldId";
        }
        String id = key(worldIdHex);
        if (deleted.test(id)) {
            return "this world was deleted by its owner";
        }
        Bytes worldId;
        try {
            worldId = Bytes.fromHex(id);
        } catch (RuntimeException e) {
            return "malformed worldId";
        }
        boolean nameless = worldName == null || worldName.isBlank();
        String name = nameless ? id : worldName;
        HostedWorld world = worlds.compute(id, (key, existing) -> {
            if (existing == null) {
                HostedWorld created = new HostedWorld(key, worldId, name,
                        UUID.nameUUIDFromBytes(worldId.toArray()));
                created.seeding = true;
                return created;
            }
            // A world we already HOST is never demoted to a seeder by this call: hosting is the
            // stronger claim and carries the game endpoint.
            //
            // A caller with no name to offer does not get to erase the one we have. `name` falls
            // back to the world id when blank, so assigning it unconditionally renamed a world to
            // its own 64-character id — which is exactly what every nameless call did, and this
            // verb is now repeated on a cadence by every joined game.
            if (!nameless) {
                existing.name = name;
            }
            return existing;
        });
        rebindOwnershipFromKeyStore(world);
        record(world);
        announce(world, AnnounceEvent.STARTED);
        registerRendezvous(world, RegistrationEvent.REGISTER);
        LOG.info("Seeding world '{}' ({}) for the network on {} tracker(s)",
                world.name, shortId(worldIdHex), tracker.endpoints().size());
        return null;
    }

    /**
     * Stop hosting a world: announce STOPPED and unregister from rendezvous. Idempotent.
     *
     * @param worldIdHex the world identity, hex-encoded.
     * @return {@code null} on success (including "was not hosting").
     */
    public String stop(String worldIdHex) {
        if (worldIdHex == null) {
            return "missing worldId";
        }
        HostedWorld world = worlds.remove(key(worldIdHex));
        if (registry != null) {
            // Removed from the registry too: a stop the player asked for must not come back at the
            // next worker start. The world's KEY is kept — see WorldRegistryStore.remove.
            registry.remove(key(worldIdHex));
        }
        if (world == null) {
            return null;
        }
        announce(world, AnnounceEvent.STOPPED);
        registerRendezvous(world, RegistrationEvent.UNREGISTER);
        LOG.info("Stopped hosting world '{}' ({})", world.name, shortId(worldIdHex));
        return null;
    }

    /**
     * The cadence of this worker's own announce/registration heartbeat (worker L-41).
     *
     * <p>The tracker names the interval in its ack and this follows it, with a floor so a tracker
     * that asks for a punishing cadence cannot make this node announce in a tight loop. It is the
     * <b>worker's</b> timer in the sense that matters: it belongs to a process that outlives the
     * game, so a world stays listed and reachable while nobody is playing it.
     *
     * @return the refresh interval, in seconds.
     */
    public int refreshIntervalSeconds() {
        return Math.max(MINIMUM_REFRESH_SECONDS, tracker.announceIntervalSeconds());
    }

    /** @return an immutable snapshot of the worlds this worker currently hosts. */
    public Collection<HostedWorld> hostedWorlds() {
        return List.copyOf(worlds.values());
    }

    /**
     * Bind an ownership claim to a world — the moment this node becomes that world's provable
     * administrator.
     *
     * <p>Called when the worker mints a world identity, which is by construction the point at which
     * it is the world's author. The claim is persisted immediately, so administration survives a
     * restart even if the world is not shared until later.
     *
     * <p>A world that is not yet hosted or seeded is registered as supported rather than dropped:
     * a player can create and key a world before opening it to the network, and losing the claim in
     * between would mean minting a second key for the same world later.
     *
     * @param worldIdHex      the world.
     * @param ownershipRecord the canonical {@code WorldOwnership} bytes; ignored when empty.
     * @Thread-context any thread.
     */
    public void bindOwnership(String worldIdHex, Bytes ownershipRecord) {
        if (worldIdHex == null || worldIdHex.isBlank()
                || ownershipRecord == null || ownershipRecord.isEmpty()) {
            return;
        }
        String key = key(worldIdHex);
        // computeIfAbsent, not get: a world can be authored (and therefore keyed) before it is ever
        // opened to the network, and dropping it here would leave the claim visible only after the
        // next restart — the live world list would disagree with the file behind it.
        HostedWorld world = worlds.computeIfAbsent(key, hex -> {
            HostedWorld created = new HostedWorld(hex, Bytes.fromHex(hex), hex,
                    UUID.nameUUIDFromBytes(Bytes.fromHex(hex).toArray()));
            // Not hosting it: nothing has asked this node to serve the world yet. It is listed as
            // administered, which is the only thing that is true so far.
            created.seeding = true;
            return created;
        });
        bindOwnershipRecord(world, ownershipRecord);
        if (registry != null) {
            registry.put(key, world.name, world.seeding, ownershipRecord);
        }
    }

    /**
     * The value {@link HostedWorld#players()} returns when nobody in the world has reported.
     *
     * <p>Negative rather than zero, and carried as such all the way to the screen, because every
     * surface downstream had been treating "I cannot see" as "nobody is there".
     */
    public static final long PLAYERS_UNKNOWN = -1;

    /**
     * How long one player-count report stays credible without a refresh.
     *
     * <p>Three refresh cadences (the mod refreshes every 30 s from both the hosting server and each
     * joined client), so one missed report does not blank a live number, and a game that dies
     * silently stops vouching for its count within the window.
     */
    public static final long PLAYERS_LEASE_SECONDS = 90;

    /**
     * Record a player-count observation for a world, from a node that is <b>in</b> that world.
     *
     * <p>Only such a node may call this. A seeder has no game and therefore no opinion; letting it
     * report the zero it can see is precisely the bug this replaces — the supporting peers of a
     * busy world all reported "0 players" with total confidence.
     *
     * @param worldIdHex   the world.
     * @param players      players the caller can see in-world; negative is ignored rather than
     *                     stored, so an unsure caller cannot blank a good number.
     * @param leaseSeconds how long the observation stands without a refresh.
     * @Thread-context any thread.
     */
    public void markPlayers(String worldIdHex, long players, long leaseSeconds) {
        HostedWorld world = worldIdHex == null ? null : worlds.get(key(worldIdHex));
        if (world == null || players < 0) {
            return;
        }
        world.players = players;
        world.playersObservedUntilEpochMillis = leaseSeconds <= 0
                ? 0
                : System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(leaseSeconds);
    }

    /**
     * Renew this machine's "a player here is in that world" lease.
     *
     * <p>Called by {@code NODERA-JOIN}, which a joined game repeats on a cadence. Deliberately a
     * no-op for a world this node does not know: the lease describes a world in the list, and a
     * lease on nothing would be a row the UI could not render.
     *
     * @param worldIdHex   the world.
     * @param leaseSeconds how long the claim stays true without another renewal; {@code <= 0}
     *                     ends it now (a clean "I have left").
     * @Thread-context any thread.
     */
    public void markConnected(String worldIdHex, long leaseSeconds) {
        HostedWorld world = worldIdHex == null ? null : worlds.get(key(worldIdHex));
        if (world == null) {
            return;
        }
        world.connectedUntilEpochMillis = leaseSeconds <= 0
                ? 0
                : System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(leaseSeconds);
    }

    /**
     * Attach an ownership claim to a world and decide, once, whether it names this node.
     *
     * <p>The single place either half is written, so the flag and the bytes can never disagree —
     * and the single place the "does this claim name me?" question is asked, which is the question
     * "do I administer this world?" actually is. Storing the claim is how a peer can tell a player
     * who runs a world it merely keeps alive; that is not the same as running it.
     */
    private void bindOwnershipRecord(HostedWorld world, Bytes ownershipRecord) {
        world.ownershipRecord = ownershipRecord == null ? Bytes.empty() : ownershipRecord;
        world.administeredHere = administeredBySelf(world.ownershipRecord);
    }

    /**
     * Re-administer a world from its on-disk private key, when this node still holds it.
     *
     * <p>The single repair for {@code stop} removing the in-memory (and registry) ownership record
     * while the world's {@code .worldkey} survives on disk (W-DUP-2). The key's presence IS the
     * claim "this node administers this world" — keys are only ever minted locally for a world this
     * node authored, never accepted from the network — so a world this node is seeding or hosting
     * again, with no claim attached, is re-bound from the key it never stopped holding.
     *
     * <p>The re-minted claim carries a fresh {@code createdAtEpoch}: the original timestamp is not
     * recoverable from the key file, and it does not need to be. The claim still verifies (both
     * signatures are valid) and still names this node, which is what "owned" reads as. The world id
     * is pinned and never re-derived here.
     *
     * @return whether this call re-bound the ownership (the caller persists it).
     */
    private boolean rebindOwnershipFromKeyStore(HostedWorld world) {
        if (keys == null || !world.ownershipRecord.isEmpty()) {
            return false;
        }
        java.util.Optional<PersistedWorldKey> key = keys.load(world.worldIdHex);
        if (key.isEmpty()) {
            return false;
        }
        try {
            WorldOwnership claim = WorldOwnership.create(identity, key.get(),
                    System.currentTimeMillis());
            CanonicalWriter w = new CanonicalWriter();
            claim.encode(w);
            bindOwnershipRecord(world, w.toBytes());
            LOG.info("Re-administered world '{}' ({}) from its on-disk key after the ownership "
                    + "record was lost", world.name, shortId(world.worldIdHex));
            return true;
        } catch (RuntimeException e) {
            // A world that cannot be re-keyed is still a world: it reads as supported, not owned,
            // which is the same state it was in before this call. The key file is left untouched.
            LOG.warn("Could not re-bind ownership for world '{}' from its key: {}",
                    world.name, e.getMessage());
            return false;
        }
    }

    /**
     * @param ownershipRecord a canonical claim, possibly empty.
     * @return whether it verifies <b>and</b> names this node as the world's administrator.
     */
    private boolean administeredBySelf(Bytes ownershipRecord) {
        return WorldOwnershipService.decodeQuietly(ownershipRecord)
                .filter(dev.nodera.storage.WorldOwnership::verify)
                .map(claim -> claim.isOwner(identity.nodeId()))
                .orElse(false);
    }

    /**
     * @param worldIdHex the world.
     * @return whether this node administers it — holds a claim that verifies and names this node.
     */
    public boolean administers(String worldIdHex) {
        HostedWorld world = worldIdHex == null ? null : worlds.get(key(worldIdHex));
        if (world != null && world.owned()) {
            return true;
        }
        return registry != null && worldIdHex != null
                && registry.find(key(worldIdHex))
                        .map(e -> administeredBySelf(e.ownershipRecord())).orElse(false);
    }

    /** Write one world through to the persisted registry (no-op without one). */
    private void record(HostedWorld world) {
        if (registry != null) {
            dev.nodera.storage.WorldRegistry.Entry row =
                    registry.put(world.worldIdHex, world.name, world.seeding, world.ownershipRecord);
            // Adopt the stored date rather than the one this object minted: the row is what a
            // restart reads back, so it is the only value that can be reported twice and agree.
            world.addedAtEpochMillis = row.addedAtEpochMillis();
        }
    }

    /**
     * Pull the world public key out of an encoded ownership claim, verifying it first.
     *
     * @param ownershipRecord the canonical claim bytes, possibly empty.
     * @return the world's public key, or {@link Bytes#empty()} when there is no verifiable claim.
     */
    static Bytes worldPublicKeyOf(Bytes ownershipRecord) {
        if (ownershipRecord == null || ownershipRecord.isEmpty()) {
            return Bytes.empty();
        }
        try {
            dev.nodera.storage.WorldOwnership ownership = dev.nodera.storage.WorldOwnership
                    .decode(new dev.nodera.core.crypto.CanonicalReader(ownershipRecord));
            // Publishing the key out of an unverified record would let a corrupted or tampered file
            // put somebody else's key on this node's world.
            return ownership.verify() ? ownership.worldPublicKey() : Bytes.empty();
        } catch (RuntimeException undecodable) {
            return Bytes.empty();
        }
    }

    /**
     * Re-announce one hosted world immediately (fresh holdings after an archive seed) instead of
     * waiting for the next heartbeat. No-op for a world this worker is not hosting.
     *
     * @param worldIdHex the world.
     * @Thread-context any thread (the announce runs on the hosting scheduler).
     */
    public void refreshNow(String worldIdHex) {
        HostedWorld world = worldIdHex == null ? null : worlds.get(key(worldIdHex));
        if (world == null) {
            return;
        }
        // refreshNow is called immediately after a seed/re-key publishes new content, so it is the
        // honest "last updated" moment: the network can now fetch a newer version than before.
        world.updatedAtEpochMillis = System.currentTimeMillis();
        if (registry != null) {
            registry.touch(worldIdHex, world.updatedAtEpochMillis);
        }
        scheduler.execute(() -> {
            announce(world, AnnounceEvent.HEARTBEAT);
            registerRendezvous(world, RegistrationEvent.REFRESH);
        });
    }

    /** @return the configured tracker endpoints, each tagged with reachability + probe latency. */
    public List<EndpointHealth> trackerHealth() {
        List<EndpointHealth> out = new ArrayList<>(tracker.endpoints().size());
        for (TrackerClient.Endpoint e : tracker.endpoints()) {
            out.add(health(e.host(), e.port(), e.transport().scheme(), trackerReachable));
        }
        return out;
    }

    /** @return the configured rendezvous endpoints, each tagged with reachability + probe latency. */
    public List<EndpointHealth> rendezvousHealth() {
        List<EndpointHealth> out = new ArrayList<>(rendezvousEndpoints.size());
        for (RendezvousEndpoint e : rendezvousEndpoints) {
            out.add(health(e.host(), e.port(), "tcp", rendezvousReachable));
        }
        return out;
    }

    private static EndpointHealth health(String host, int port, String scheme,
                                         Map<String, dev.nodera.transport.Reachability.Probe> cache) {
        dev.nodera.transport.Reachability.Probe probe = cache.get(key(host, port));
        return new EndpointHealth(host, port, scheme,
                probe != null && probe.reachable(),
                probe == null ? -1 : probe.latencyMillis());
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        // Best-effort graceful teardown so a clean worker exit doesn't leave stale world listings.
        for (HostedWorld world : worlds.values()) {
            announce(world, AnnounceEvent.STOPPED);
            registerRendezvous(world, RegistrationEvent.UNREGISTER);
        }
        worlds.clear();
    }

    // --- internals -------------------------------------------------------------------------------

    /**
     * Re-announce HEARTBEAT + refresh rendezvous for every hosted world this node can still serve.
     *
     * <p>A world that fails the {@link #bindServability servability} test is suppressed within this
     * one cycle (W-DUP-1): it is marked and skipped, so a stale row stops being announced, and its
     * claim is retained for a repair to reinstate.
     */
    private void refreshAll() {
        for (HostedWorld world : worlds.values()) {
            // A world with a live game route is servable by definition — a running game is serving
            // it right now — whatever the content-plane test says about the bytes at rest.
            if (world.mcRoute == null && !servable.test(world.worldIdHex)) {
                world.announceSuppressed = true;
                continue;
            }
            world.announceSuppressed = false;
            announce(world, AnnounceEvent.HEARTBEAT);
            registerRendezvous(world, RegistrationEvent.REFRESH);
        }
    }

    /** Build + send a signed tracker announce for one world. Never throws. */
    private void announce(HostedWorld world, AnnounceEvent event) {
        if (tracker.endpoints().isEmpty()) {
            return;
        }
        try {
            String route = selfRoute.get();
            List<String> routes = new ArrayList<>(2);
            if (route != null && !route.isBlank()) {
                routes.add(route);
            }
            // The Minecraft game endpoint rides the announce as an extra route claim in the
            // frozen wire shape ("mc/host:port"); the tracker's routes query (tag 49) serves it
            // to joiners, while the single-route PeerEntry skips the mc/ form.
            String mc = world.mcRoute;
            if (mc != null && !mc.isBlank()) {
                routes.add(MC_ROUTE_PREFIX + mc);
            }
            var announce = tracker.buildAnnounce(world.worldId, event, routes, capabilities,
                    holdingsFor.apply(world.worldIdHex), world.name,
                    // L-38: the coordinated decommission countdown rides the announce — 0 while
                    // MONITORED (a live seeder exists), the earliest-deadline while counting down,
                    // so every tracker/UI surfaces the same network-visible deadline.
                    retention.state(world.worldId).deadlineEpochMillis(), 10_000,
                    // The population this node can actually see, which for a seeder is "I cannot".
                    // Carrying it on the announce is the only way a peer that is not in the world
                    // — or a tracker directory — ever learns a real player count: the tracker's own
                    // number counts announcing peers, not people.
                    world.players(),
                    System.currentTimeMillis());
            var acks = tracker.announce(announce);
            int asked = tracker.endpoints().size();
            int accepted = 0;
            List<String> refusals = new ArrayList<>(2);
            for (var ack : acks.entrySet()) {
                if (ack.getValue().accepted()) {
                    accepted++;
                } else {
                    refusals.add(ack.getKey() + ": " + ack.getValue().reason());
                }
            }
            world.listedOnTrackers = accepted;
            world.announcedToTrackers = asked;
            // A refusal, and silence, both used to log the same "N ack(s)" line at DEBUG as a
            // success. They are the two ways a world goes undiscoverable while this node believes
            // it is hosting, so they are the two things worth a warning: no other symptom appears
            // on this side at all — the failure surfaces on somebody else's screen, as a join that
            // cannot find a seeder.
            if (accepted == 0) {
                LOG.warn("world '{}' is on NO tracker — {} of {} answered{}; nobody can find it",
                        world.name, acks.size(), asked,
                        refusals.isEmpty() ? "" : ", refused: " + String.join(", ", refusals));
            } else {
                if (!refusals.isEmpty()) {
                    LOG.warn("tracker announce {} '{}' refused by {}",
                            event, world.name, String.join(", ", refusals));
                }
                LOG.debug("tracker announce {} '{}' → {} of {} tracker(s) listed it",
                        event, world.name, accepted, asked);
            }
        } catch (RuntimeException e) {
            LOG.warn("tracker announce {} for '{}' failed: {}", event, world.name, e.getMessage());
        }
    }

    /** Register / refresh / unregister one world's host record with every rendezvous. Never throws. */
    /**
     * Replace the rendezvous endpoints in use and re-register every hosted world immediately.
     *
     * <p>Re-registering here rather than waiting for the refresh tick is the point: after a relay
     * drains, the new relays hold no record of this host at all until it says so, and half a
     * registration TTL of invisibility is exactly the window a joiner would fail in.
     *
     * @param endpoints the endpoints to use from now on, best first; an empty list is ignored, because
     *                  having no relay is worse than keeping a stale one.
     * @throws IllegalArgumentException if {@code endpoints} is null.
     * @Thread-context any thread.
     */
    public void setRendezvousEndpoints(List<RendezvousEndpoint> endpoints) {
        java.util.Objects.requireNonNull(endpoints, "endpoints");
        if (endpoints.isEmpty() || endpoints.equals(rendezvousEndpoints)) {
            return;
        }
        this.rendezvousEndpoints = List.copyOf(endpoints);
        LOG.info("rendezvous endpoints are now {}", endpoints);
        for (HostedWorld world : worlds.values()) {
            registerRendezvous(world, RegistrationEvent.REGISTER);
        }
    }

    /** @return the rendezvous endpoints in use, best first. @Thread-context any thread. */
    public List<RendezvousEndpoint> rendezvousEndpoints() {
        return rendezvousEndpoints;
    }

    private void registerRendezvous(HostedWorld world, RegistrationEvent event) {
        List<RendezvousEndpoint> endpoints = rendezvousEndpoints;
        if (endpoints.isEmpty()) {
            return;
        }
        String route = selfRoute.get();
        List<PeerCandidate> candidates = route == null || route.isBlank()
                ? List.of()
                : List.of(new PeerCandidate(CandidateKind.HOST, route, 100));
        long now = System.currentTimeMillis();
        SignedRecord record = rendezvous.sign(world.networkId, world.worldId, event, candidates,
                capabilities, now, now + REGISTRATION_TTL.toMillis());
        for (RendezvousEndpoint endpoint : endpoints) {
            try {
                rendezvous.register(endpoint, record);
            } catch (Exception e) {
                LOG.warn("rendezvous {} {} for '{}' failed: {}", event, endpoint, world.name,
                        e.getMessage());
            }
        }
    }

    /** Refresh the cached reachability + latency of every tracker + rendezvous endpoint. */
    private void probeHealth() {
        for (TrackerClient.Endpoint e : tracker.endpoints()) {
            trackerReachable.put(key(e.host(), e.port()), measure(e.host(), e.port()));
        }
        for (RendezvousEndpoint e : rendezvousEndpoints) {
            rendezvousReachable.put(key(e.host(), e.port()), measure(e.host(), e.port()));
        }
    }

    private static dev.nodera.transport.Reachability.Probe measure(String host, int port) {
        return dev.nodera.transport.Reachability.measure(
                host, port, java.time.Duration.ofMillis(PROBE_TIMEOUT_MILLIS));
    }

    private static String key(String host, int port) {
        return host + ":" + port;
    }

    /**
     * The one spelling of a world id used as a map key.
     *
     * <p>{@code host} and {@code seed} keyed the live map on the caller's exact string while
     * {@code stop} trimmed it and {@link WorldRegistryStore} lower-cased it. A world id that arrived
     * padded or upper-cased therefore created an entry that {@code stop} could not remove and that
     * kept announcing until the process died — a duplicate that outlived the thing that made it. Hex
     * has no meaningful case, so normalising costs nothing.
     */
    private static String key(String worldIdHex) {
        return worldIdHex.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String shortId(String hex) {
        return hex.length() <= 12 ? hex : hex.substring(0, 12);
    }

    /** One world this worker is keeping discoverable on the network. */
    public static final class HostedWorld {
        final String worldIdHex;
        final Bytes worldId;
        volatile String name;
        final UUID networkId;
        /** The host's Minecraft game endpoint ({@code host:port}), or {@code null} while the
         *  hosting player's game is closed. Updated by every re-HOST. */
        volatile String mcRoute;
        /**
         * Players in-world, as last reported by a node that is actually in it.
         *
         * <p>Meaningless without {@link #playersObservedUntilEpochMillis} — see {@link #players()}.
         */
        volatile long players;
        /**
         * When the player count above stops being a claim anybody stands behind (epoch millis).
         *
         * <p>A count is an <b>observation</b>, not a property of the world, and only a node with a
         * game in that world can make one. Every other node has to say "I do not know", which the
         * old {@code long players = 0} could not: a peer supporting somebody else's world reported
         * zero players for a world with people in it, and so did the joiner standing in it, because
         * zero was also the value for "nothing has ever told me". Expiring the claim also repairs
         * the count when the reporting game dies without a final word.
         */
        volatile long playersObservedUntilEpochMillis;
        /**
         * When this world first entered the Nodera network from this node (the "Date added").
         *
         * <p>Not final: the persisted row is the authority. A fresh {@code HostedWorld} stamps a
         * provisional value, and {@link WorldHostingService#record} replaces it with whatever the
         * registry stored — otherwise the live object and the file disagree by however many
         * milliseconds elapsed between the two clock reads, and the date visibly moves at the next
         * restart.
         */
        volatile long addedAtEpochMillis;
        /** When this world's content last changed here — bumped by every seed (the "Last updated"). */
        volatile long updatedAtEpochMillis;
        /** {@code true} when this node only holds the world's bytes; {@code false} when it hosts it. */
        volatile boolean seeding;
        /**
         * When this machine's claim to be <i>playing in</i> this world runs out (epoch millis).
         *
         * <p>A lease rather than a boolean, and renewed by every {@code NODERA-JOIN}. A flag set on
         * join and cleared on leave is correct exactly until one "leave" goes missing — a crash, a
         * killed game, a lost control socket — and then reads "connected" forever, on the screen a
         * player uses to decide whether their node is doing anything. An expiring claim is wrong
         * for at most one lease and then repairs itself with no message at all.
         */
        volatile long connectedUntilEpochMillis;
        /**
         * The canonical {@code WorldOwnership} bytes for this world, or {@link Bytes#empty()} when
         * none is known. Held encoded because this class only ever moves it around; whoever needs
         * the claim decodes and verifies it.
         *
         * <p><b>Holding a claim is not administering the world.</b> A claim names its owner, and
         * that owner is very often somebody else — the registry persists the claim for every world
         * this node keeps available, precisely so the node can tell a player who runs it.
         */
        volatile Bytes ownershipRecord = Bytes.empty();
        /**
         * Whether the claim above names <b>this</b> node.
         *
         * <p>Computed once, where the record is bound, rather than derived from "is the record
         * non-empty" at every read — which is what it used to be, and which made every peer that
         * merely knew who administered a world report that it administered the world itself. Both
         * players in a two-player session saw "You administer this" on the same world, and both
         * were offered the administrator-only actions.
         */
        volatile boolean administeredHere;
        /**
         * How many trackers accepted this world's last announce, and how many were asked.
         *
         * <p>Held because <b>an announce that was sent is not an announce that was registered</b>,
         * and until this existed nothing on this node could tell the two apart. A tracker refuses
         * (quota, a stale clock, an announce body it cannot decode) or simply hangs up, and the
         * peer went on describing the world as hosted and joinable while no tracker had ever
         * listed it — so every other peer's seeder lookup answered "0 seeders" and every join
         * ended in "no routable seeder". This is the fact the UI needs to stop claiming reach it
         * does not have.
         */
        volatile int listedOnTrackers;
        /** Trackers asked at that last announce. {@code -1} means "never announced". */
        volatile int announcedToTrackers = -1;
        /**
         * Whether the last reconciliation pass suppressed this world from the announce set because
         * this node can no longer serve it (W-DUP-1).
         *
         * <p>A world marked here is <b>not announced</b> (no HEARTBEAT, no rendezvous refresh) but
         * its row is kept, so a repair reinstates it under the same identity rather than minting a
         * second one. Cleared the moment the world is servable again.
         */
        volatile boolean announceSuppressed;

        HostedWorld(String worldIdHex, Bytes worldId, String name, UUID networkId) {
            this(worldIdHex, worldId, name, networkId, System.currentTimeMillis(),
                    System.currentTimeMillis());
        }

        HostedWorld(String worldIdHex, Bytes worldId, String name, UUID networkId,
                    long addedAtEpochMillis, long updatedAtEpochMillis) {
            this.worldIdHex = worldIdHex;
            this.worldId = worldId;
            this.name = name;
            this.networkId = networkId;
            this.addedAtEpochMillis = addedAtEpochMillis;
            this.updatedAtEpochMillis = updatedAtEpochMillis;
        }

        /** @return whether this node holds this world's private key and administers it. */
        public boolean owned() {
            return administeredHere;
        }

        /**
         * @return whether a player on this machine is in this world right now — an unexpired
         *         {@code NODERA-JOIN} lease. Independent of {@link #owned()} and {@link #seeding}:
         *         you can be playing in a world you administer, in one you merely support, or in
         *         one you have only just started downloading.
         */
        public boolean connected() {
            return System.currentTimeMillis() < connectedUntilEpochMillis;
        }

        /** @return the canonical ownership-claim bytes, empty when no claim is known here. */
        public Bytes ownershipRecord() {
            return ownershipRecord;
        }

        /**
         * @return the world's own public key — its administrative root — or {@link Bytes#empty()}
         *         when this node has no ownership claim for it.
         */
        public Bytes worldPublicKey() {
            return dev.nodera.headless.WorldHostingService.worldPublicKeyOf(ownershipRecord);
        }

        public String worldIdHex() {
            return worldIdHex;
        }

        public String name() {
            return name;
        }

        /** @return epoch millis when this world was added to the network from this node. */
        public long addedAtEpochMillis() {
            return addedAtEpochMillis;
        }

        /** @return epoch millis of this world's most recent content update on this node. */
        public long updatedAtEpochMillis() {
            return updatedAtEpochMillis;
        }

        /**
         * @return {@code true} when this node is only keeping the world's content alive for the
         *         network, {@code false} when it is the world's host.
         */
        public boolean seeding() {
            return seeding;
        }

        /** @return the game endpoint joiners connect to, or {@code null} (host's game closed). */
        public String mcRoute() {
            return mcRoute;
        }

        /**
         * @return how many trackers accepted this world's most recent announce. {@code 0} with a
         *         positive {@link #announcedToTrackers()} means the world is <b>not discoverable</b>
         *         however healthy this node looks: nobody can look it up.
         */
        public int listedOnTrackers() {
            return listedOnTrackers;
        }

        /**
         * @return how many trackers were asked at the most recent announce, or {@code -1} when this
         *         world has never been announced (no trackers configured, or not yet hosted).
         */
        public int announcedToTrackers() {
            return announcedToTrackers;
        }

        /**
         * @return whether the last reconciliation pass suppressed this world from the announce set
         *         because this node can no longer serve it (W-DUP-1). The row is retained; this flag
         *         only says the worker is no longer telling the network about it.
         */
        public boolean announceSuppressed() {
            return announceSuppressed;
        }

        /**
         * @return players in-world, or {@link #PLAYERS_UNKNOWN} when no node currently in the world
         *         has reported recently. <b>Never conflate the two:</b> "nobody is playing" and
         *         "nothing here can see who is playing" are different answers, and rendering the
         *         second as {@code 0} is what made this number wrong on every peer but the host.
         */
        public long players() {
            return System.currentTimeMillis() < playersObservedUntilEpochMillis
                    ? players
                    : PLAYERS_UNKNOWN;
        }
    }

    /** Extract one top-level string field from a flat options-JSON object, or {@code null}. */
    static String jsonStringField(String json, String field) {
        if (json == null) {
            return null;
        }
        var m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        if (!m.find()) {
            return null;
        }
        String value = m.group(1);
        return value.isBlank() ? null : value;
    }

    /** Extract one top-level non-negative number field from a flat options-JSON object, or 0. */
    static long jsonLongField(String json, String field) {
        if (json == null) {
            return 0;
        }
        var m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*(\\d+)")
                .matcher(json);
        if (!m.find()) {
            return 0;
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * A configured discovery endpoint plus its last probe, for the dashboard.
     *
     * @param scheme        how the endpoint is reached ({@code tcp} / {@code udp}), so the UI can
     *                      render the source as a URI rather than guessing the protocol.
     * @param reachable     whether the last probe completed.
     * @param latencyMillis last handshake round-trip, or {@code -1} when unreachable / unprobed.
     */
    public record EndpointHealth(String host, int port, String scheme, boolean reachable,
                                 long latencyMillis) {
    }
}
