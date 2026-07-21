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

    /** Endpoint-health probe cadence + how stale a reachability reading may be. */
    private static final int HEALTH_PROBE_SECONDS = 15;
    private static final int PROBE_TIMEOUT_MILLIS = 400;

    private final NodeIdentity identity;
    private final NodeCapabilities capabilities;
    /** Supplies the worker's currently-advertised P2P route (may change as the runtime settles). */
    private final Supplier<String> selfRoute;

    private final List<TrackerClient.Endpoint> trackerEndpoints;
    private final List<RendezvousEndpoint> rendezvousEndpoints;
    private final TrackerClient tracker;
    private final RendezvousClient rendezvous;

    private final Map<String, HostedWorld> worlds = new ConcurrentHashMap<>();
    private final Map<String, Boolean> trackerReachable = new ConcurrentHashMap<>();
    private final Map<String, Boolean> rendezvousReachable = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;

    public WorldHostingService(NodeIdentity identity, NodeCapabilities capabilities,
                               Supplier<String> selfRoute,
                               List<TrackerClient.Endpoint> trackerEndpoints,
                               List<RendezvousEndpoint> rendezvousEndpoints) {
        this.identity = identity;
        this.capabilities = capabilities;
        this.selfRoute = selfRoute;
        this.trackerEndpoints = List.copyOf(trackerEndpoints);
        this.rendezvousEndpoints = List.copyOf(rendezvousEndpoints);
        this.tracker = new TrackerClient(this.trackerEndpoints, identity);
        this.rendezvous = new RendezvousClient(identity, Duration.ofSeconds(3), Duration.ofSeconds(5));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nodera-worker-hosting");
            t.setDaemon(true);
            return t;
        });
        // Refresh announces/registrations + endpoint health on a fixed cadence.
        int refresh = Math.max(15, tracker.announceIntervalSeconds());
        scheduler.scheduleWithFixedDelay(this::refreshAll, refresh, refresh, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::probeHealth, 0, HEALTH_PROBE_SECONDS, TimeUnit.SECONDS);
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
        Bytes worldId;
        try {
            worldId = Bytes.fromHex(worldIdHex.trim());
        } catch (RuntimeException e) {
            return "malformed worldId";
        }
        String name = worldName == null || worldName.isBlank() ? worldIdHex : worldName;
        HostedWorld world = new HostedWorld(worldIdHex, worldId, name,
                UUID.nameUUIDFromBytes(worldId.toArray()));
        worlds.put(worldIdHex, world);
        announce(world, AnnounceEvent.STARTED);
        registerRendezvous(world, RegistrationEvent.REGISTER);
        LOG.info("Now hosting world '{}' ({}) on {} tracker(s) / {} rendezvous",
                name, shortId(worldIdHex), trackerEndpoints.size(), rendezvousEndpoints.size());
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
        if (world == null) {
            return null;
        }
        announce(world, AnnounceEvent.STOPPED);
        registerRendezvous(world, RegistrationEvent.UNREGISTER);
        LOG.info("Stopped hosting world '{}' ({})", world.name, shortId(worldIdHex));
        return null;
    }

    /** @return an immutable snapshot of the worlds this worker currently hosts. */
    public Collection<HostedWorld> hostedWorlds() {
        return List.copyOf(worlds.values());
    }

    /** @return the configured tracker endpoints, each tagged with its last-probed reachability. */
    public List<EndpointHealth> trackerHealth() {
        List<EndpointHealth> out = new ArrayList<>(trackerEndpoints.size());
        for (TrackerClient.Endpoint e : trackerEndpoints) {
            out.add(new EndpointHealth(e.host(), e.port(),
                    trackerReachable.getOrDefault(key(e.host(), e.port()), false)));
        }
        return out;
    }

    /** @return the configured rendezvous endpoints, each tagged with its last-probed reachability. */
    public List<EndpointHealth> rendezvousHealth() {
        List<EndpointHealth> out = new ArrayList<>(rendezvousEndpoints.size());
        for (RendezvousEndpoint e : rendezvousEndpoints) {
            out.add(new EndpointHealth(e.host(), e.port(),
                    rendezvousReachable.getOrDefault(key(e.host(), e.port()), false)));
        }
        return out;
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
        if (trackerEndpoints.isEmpty()) {
            return;
        }
        try {
            String route = selfRoute.get();
            List<String> routes = route == null || route.isBlank() ? List.of() : List.of(route);
            var announce = tracker.buildAnnounce(world.worldId, event, routes, capabilities,
                    List.of(), world.name, 0L, 10_000, System.currentTimeMillis());
            int acks = tracker.announce(announce).size();
            LOG.debug("tracker announce {} '{}' → {} ack(s)", event, world.name, acks);
        } catch (RuntimeException e) {
            LOG.warn("tracker announce {} for '{}' failed: {}", event, world.name, e.getMessage());
        }
    }

    /** Register / refresh / unregister one world's host record with every rendezvous. Never throws. */
    private void registerRendezvous(HostedWorld world, RegistrationEvent event) {
        if (rendezvousEndpoints.isEmpty()) {
            return;
        }
        String route = selfRoute.get();
        List<PeerCandidate> candidates = route == null || route.isBlank()
                ? List.of()
                : List.of(new PeerCandidate(CandidateKind.HOST, route, 100));
        long now = System.currentTimeMillis();
        SignedRecord record = rendezvous.sign(world.networkId, world.worldId, event, candidates,
                capabilities, now, now + REGISTRATION_TTL.toMillis());
        for (RendezvousEndpoint endpoint : rendezvousEndpoints) {
            try {
                rendezvous.register(endpoint, record);
            } catch (Exception e) {
                LOG.warn("rendezvous {} {} for '{}' failed: {}", event, endpoint, world.name,
                        e.getMessage());
            }
        }
    }

    /** Refresh the cached reachability of every tracker + rendezvous endpoint (dashboard health). */
    private void probeHealth() {
        for (TrackerClient.Endpoint e : trackerEndpoints) {
            trackerReachable.put(key(e.host(), e.port()), reachable(e.host(), e.port()));
        }
        for (RendezvousEndpoint e : rendezvousEndpoints) {
            rendezvousReachable.put(key(e.host(), e.port()), reachable(e.host(), e.port()));
        }
    }

    private static boolean reachable(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), PROBE_TIMEOUT_MILLIS);
            return true;
        } catch (Exception e) {
            return false;
        }
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
        final String name;
        final UUID networkId;

        HostedWorld(String worldIdHex, Bytes worldId, String name, UUID networkId) {
            this.worldIdHex = worldIdHex;
            this.worldId = worldId;
            this.name = name;
            this.networkId = networkId;
        }

        public String worldIdHex() {
            return worldIdHex;
        }

        public String name() {
            return name;
        }
    }

    /** A configured discovery endpoint plus its last-probed reachability, for the dashboard. */
    public record EndpointHealth(String host, int port, boolean reachable) {
    }
}
