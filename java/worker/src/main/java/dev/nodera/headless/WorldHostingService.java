package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.peer.discovery.TrackerClient;
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
    private static final int PROBE_TIMEOUT_MILLIS = 400;

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
     * Worlds this node must refuse to serve, whatever asks it to (see
     * {@link #refuseDeletedWorlds}). Defaults to refusing nothing.
     */
    private volatile java.util.function.Predicate<String> deleted = worldIdHex -> false;

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
        this.registry = registry;
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
            world.ownershipRecord = entry.ownershipRecord();
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
        if (deleted.test(worldIdHex.trim())) {
            return "this world was deleted by its owner";
        }
        Bytes worldId;
        try {
            worldId = Bytes.fromHex(worldIdHex.trim());
        } catch (RuntimeException e) {
            return "malformed worldId";
        }
        String name = worldName == null || worldName.isBlank() ? worldIdHex : worldName;
        HostedWorld world = worlds.compute(worldIdHex, (key, existing) -> {
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
        world.players = jsonLongField(optionsJson, "players");
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
        if (deleted.test(worldIdHex.trim())) {
            return "this world was deleted by its owner";
        }
        Bytes worldId;
        try {
            worldId = Bytes.fromHex(worldIdHex.trim());
        } catch (RuntimeException e) {
            return "malformed worldId";
        }
        String name = worldName == null || worldName.isBlank() ? worldIdHex : worldName;
        HostedWorld world = worlds.compute(worldIdHex, (key, existing) -> {
            if (existing == null) {
                HostedWorld created = new HostedWorld(key, worldId, name,
                        UUID.nameUUIDFromBytes(worldId.toArray()));
                created.seeding = true;
                return created;
            }
            // A world we already HOST is never demoted to a seeder by this call: hosting is the
            // stronger claim and carries the game endpoint.
            existing.name = name;
            return existing;
        });
        record(world);
        announce(world, AnnounceEvent.STARTED);
        registerRendezvous(world, RegistrationEvent.REGISTER);
        LOG.info("Seeding world '{}' ({}) for the network on {} tracker(s)",
                name, shortId(worldIdHex), tracker.endpoints().size());
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
        HostedWorld world = worlds.remove(worldIdHex.trim());
        if (registry != null) {
            // Removed from the registry too: a stop the player asked for must not come back at the
            // next worker start. The world's KEY is kept — see WorldRegistryStore.remove.
            registry.remove(worldIdHex.trim());
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
        String key = worldIdHex.trim();
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
        world.ownershipRecord = ownershipRecord;
        if (registry != null) {
            registry.put(key, world.name, world.seeding, ownershipRecord);
        }
    }

    /**
     * @param worldIdHex the world.
     * @return whether this node holds an ownership claim for it.
     */
    public boolean administers(String worldIdHex) {
        HostedWorld world = worldIdHex == null ? null : worlds.get(worldIdHex.trim());
        if (world != null && world.owned()) {
            return true;
        }
        return registry != null && worldIdHex != null
                && registry.find(worldIdHex.trim()).map(e -> e.owned()).orElse(false);
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
        HostedWorld world = worlds.get(worldIdHex);
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

    /** Re-announce HEARTBEAT + refresh rendezvous for every hosted world (keeps listings alive). */
    private void refreshAll() {
        for (HostedWorld world : worlds.values()) {
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
                    System.currentTimeMillis());
            int acks = tracker.announce(announce).size();
            LOG.debug("tracker announce {} '{}' → {} ack(s)", event, world.name, acks);
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
        /** Players currently online in-world, as last reported by the mod. */
        volatile long players;
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
         * The canonical {@code WorldOwnership} bytes when this node administers the world, or
         * {@link Bytes#empty()} when it does not. Held encoded because this class only ever moves
         * it around; whoever needs the claim decodes and verifies it.
         */
        volatile Bytes ownershipRecord = Bytes.empty();

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
            return !ownershipRecord.isEmpty();
        }

        /** @return the canonical ownership-claim bytes, empty when this node does not administer it. */
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

        /** @return players currently online in-world, as last reported by the mod. */
        public long players() {
            return players;
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
