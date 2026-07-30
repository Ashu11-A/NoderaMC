package dev.nodera.mod.client.multiplayer;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.WorldHealth;
import dev.nodera.diagnostics.view.TorrentWorldListView.TorrentWorldEntry;
import dev.nodera.endpoint.control.CompanionLink;
import dev.nodera.mod.common.NoderaConfig;
import dev.nodera.endpoint.state.WorkerStateParser;
import dev.nodera.endpoint.state.WorkerStateParser.HostedWorldInfo;
import dev.nodera.peer.discovery.TrackerClient;
import dev.nodera.protocol.discovery.TrackerCatalogEntry;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The live feed behind the multiplayer screen's <b>Worlds</b> tab — the union of two sources:
 *
 * <ol>
 *   <li><b>Your worlds</b> — the always-on peer worker's {@code NODERA-STATE}
 *       ({@code connected_worlds}): worlds this install shares, with the local player as owner and
 *       the live game endpoint ({@code mc_route}) as the joinability signal. The worker outlives
 *       the game (Task 32), so these rows survive closing a hosted world.</li>
 *   <li><b>The network</b> — the tracker directory ({@code TrackerCatalogQuery}, tag 44): every
 *       world other players share publicly, with players/chunks/reliability/health/countdown from
 *       the tracker's registry.</li>
 * </ol>
 *
 * <p>Merged by world id — a world you host wins over its own tracker row (the owner label and the
 * locally-known game endpoint are better data). Every entry carries its {@code worldIdHex}, which
 * is what {@link NoderaJoinFlow} resolves through the tracker when the player presses Join.
 *
 * <p>Thread-context: {@link #start} at client setup; polls run on a daemon scheduler; {@link
 * #snapshot} is read on the render thread (cached, volatile).
 */
public final class MultiplayerWorldFeed {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaWorlds");

    /** Worker-poll cadence (loopback, cheap). */
    private static final int WORKER_POLL_SECONDS = 3;
    /** Tracker-directory cadence (network). */
    private static final int CATALOG_POLL_SECONDS = 10;

    private static volatile List<TorrentWorldEntry> ownWorlds = List.of();
    private static volatile List<TorrentWorldEntry> networkWorlds = List.of();
    /**
     * World ids this node <b>hosts</b>, as opposed to the ones it only keeps alive for others.
     *
     * <p>The set is what {@link #merge} uses to decide whose answer about liveness counts. Kept
     * beside the rows rather than inside them because it is a fact about this install, not about
     * the world, and every other consumer of a row would have to ignore it.
     */
    private static volatile java.util.Set<String> authoritative = java.util.Set.of();
    private static ScheduledExecutorService scheduler;
    private static volatile TrackerClient tracker;

    private MultiplayerWorldFeed() {
    }

    /** The merged Worlds-tab snapshot (own worlds first, then the rest of the network). */
    /**
     * The single-player world-list badge feed (L-46): this install's worker-hosted worlds as
     * {@code PublicWorldStatus} rows — save name, shared, live player count — so the select-world
     * screen shows the shared summary from the SAME worker STATE the multiplayer tab reads.
     */
    public static List<dev.nodera.diagnostics.view.PublicWorldBadgeView.PublicWorldStatus>
            ownWorldStatuses() {
        java.util.List<dev.nodera.diagnostics.view.PublicWorldBadgeView.PublicWorldStatus> out =
                new java.util.ArrayList<>();
        for (TorrentWorldEntry entry : ownWorlds) {
            out.add(new dev.nodera.diagnostics.view.PublicWorldBadgeView.PublicWorldStatus(
                    entry.name(), true, entry.playerCount()));
        }
        return out;
    }

    public static List<TorrentWorldEntry> snapshot() {
        return merge(ownWorlds, networkWorlds, authoritative);
    }

    /**
     * Merge this node's own rows with the tracker's, world by world.
     *
     * <p>This used to be "own rows win, {@code putIfAbsent} for the rest", and that one line made a
     * live world unjoinable from every peer that was helping to host it. The worker's
     * {@code connected_worlds} lists <b>every</b> world in the local registry, including the ones
     * this node only stores for somebody else; {@link #buildEntries} derives health from whether
     * <i>this</i> install has a game endpoint open for it; and a supported world never does. So the
     * peer stamped {@code DEAD} on somebody else's live world, that row displaced the tracker row
     * that said {@code HEALTHY · 1 player}, and {@code joinable()} — which refuses DEAD — greyed out
     * the Join button. Seen live: "Hello by Dev · players unknown · 0% reliable · Offline" on the
     * peer, beside "Joinable now · 1 in this world" on the host, same world, same minute.
     *
     * <p>The rule is about <b>who is entitled to answer which question</b>. Identity — the world's
     * name and who owns it — is local knowledge and stays local. Liveness is the network's answer
     * unless this node hosts the world, in which case its own game endpoint is the better one.
     *
     * @param own           rows from this node's worker.
     * @param network       rows from the tracker directory.
     * @param hostedHere    world ids this node actually hosts (not merely supports).
     * @return the merged list: own worlds first, then the rest of the network.
     */
    static List<TorrentWorldEntry> merge(List<TorrentWorldEntry> own,
                                         List<TorrentWorldEntry> network,
                                         java.util.Set<String> hostedHere) {
        Map<String, TorrentWorldEntry> merged = new LinkedHashMap<>();
        for (TorrentWorldEntry entry : own) {
            merged.put(keyOf(entry), entry);
        }
        for (TorrentWorldEntry entry : network) {
            String key = keyOf(entry);
            TorrentWorldEntry local = merged.get(key);
            if (local == null) {
                merged.put(key, entry);
            } else if (!hostedHere.contains(local.worldIdHex())) {
                merged.put(key, withLocalIdentity(local, entry));
            }
        }
        return List.copyOf(merged.values());
    }

    /** The network's row, keeping the name and owner this node knows locally. */
    private static TorrentWorldEntry withLocalIdentity(TorrentWorldEntry local,
                                                       TorrentWorldEntry network) {
        return new TorrentWorldEntry(
                local.name().isBlank() ? network.name() : local.name(),
                network.playerCount(),
                network.storedChunks(),
                network.reliabilityBps(),
                network.health(),
                network.retentionSecondsRemaining(),
                local.hostName().isBlank() ? network.hostName() : local.hostName(),
                network.worldIdHex(),
                // Kept if this node happens to know one — it is a better handle than the catalog's
                // (empty) one, and a stale endpoint costs a failed dial, not a hidden world.
                local.mcRoute().isBlank() ? network.mcRoute() : local.mcRoute());
    }

    private static String keyOf(TorrentWorldEntry entry) {
        return entry.worldIdHex().isBlank() ? "name:" + entry.name() : entry.worldIdHex();
    }

    /** Start the background polls. Idempotent; builds the first snapshots immediately. */
    public static synchronized void start() {
        if (scheduler != null) {
            return;
        }
        refreshOwn();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nodera-multiplayer-worlds");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(
                MultiplayerWorldFeed::refreshOwn, WORKER_POLL_SECONDS, WORKER_POLL_SECONDS,
                TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(
                MultiplayerWorldFeed::refreshNetwork, 1, CATALOG_POLL_SECONDS, TimeUnit.SECONDS);
    }

    /** Force a directory re-fetch now (the screen's Refresh button). Safe from the render thread. */
    public static void requestRefresh() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.execute(() -> {
                refreshOwn();
                refreshNetwork();
            });
        }
    }

    private static void refreshOwn() {
        try {
            String state = workerState();
            ownWorlds = buildEntries(state, localPlayerName());
            authoritative = hostedHere(state);
        } catch (RuntimeException e) {
            // Never let a feed error blank the tab or kill the daemon thread; keep the last snapshot.
        }
    }

    private static void refreshNetwork() {
        try {
            TrackerClient client = trackerClient();
            if (client == null || client.endpoints().isEmpty()) {
                return;
            }
            networkWorlds = buildNetworkEntries(client.catalog(0));
        } catch (RuntimeException e) {
            LOG.debug("tracker catalog refresh failed: {}", e.toString());
        }
    }

    /** The lazily-built client-side tracker client (ephemeral identity; queries are unsigned). */
    private static TrackerClient trackerClient() {
        TrackerClient client = tracker;
        if (client != null) {
            return client;
        }
        synchronized (MultiplayerWorldFeed.class) {
            if (tracker == null) {
                List<TrackerClient.Endpoint> endpoints = new ArrayList<>();
                for (String route : NoderaConfig.CLIENT_TRACKER_ENDPOINTS.get()) {
                    try {
                        endpoints.add(TrackerClient.Endpoint.parse(route));
                    } catch (IllegalArgumentException e) {
                        LOG.warn("Ignoring malformed tracker endpoint '{}': {}", route, e.getMessage());
                    }
                }
                tracker = new TrackerClient(endpoints, NodeIdentity.generate());
            }
            return tracker;
        }
    }

    /** The client-side tracker client used by the join flow too (shared instance). */
    public static TrackerClient sharedTracker() {
        return trackerClient();
    }

    /** @return the worker's raw STATE reply, or {@code null} when no worker is linked / it is silent. */
    private static String workerState() {
        if (!CompanionLink.isPresent()) {
            return null;
        }
        return CompanionLink.client().state().orElse(null);
    }

    /** @return the local player's display name (the owner of everything this personal worker hosts). */
    private static String localPlayerName() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getUser() == null ? "" : mc.getUser().getName();
    }

    /**
     * Map the worker's hosted worlds to view-model entries. Pure over its inputs (no Minecraft
     * state), so it is unit-testable — {@link #refreshOwn} supplies the live worker reply + name.
     *
     * @param workerStateJson the worker STATE reply (may be {@code null}).
     * @param owner           the owner display name to stamp on each hosted world.
     * @return the own-worlds entries.
     */
    /**
     * The world ids this install hosts, from a worker STATE reply — the ones whose liveness this
     * node is entitled to answer for. Pure over its input, like {@link #buildEntries}.
     *
     * @param workerStateJson the worker STATE reply (may be {@code null}).
     * @return hosted world ids; supported-only worlds are excluded.
     */
    public static java.util.Set<String> hostedHere(String workerStateJson) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (HostedWorldInfo world : WorkerStateParser.connectedWorlds(workerStateJson)) {
            if (world.hostedHere()) {
                ids.add(world.worldId());
            }
        }
        return java.util.Set.copyOf(ids);
    }

    public static List<TorrentWorldEntry> buildEntries(String workerStateJson, String owner) {
        List<HostedWorldInfo> hosted = WorkerStateParser.connectedWorlds(workerStateJson);
        List<TorrentWorldEntry> out = new ArrayList<>(hosted.size());
        for (HostedWorldInfo world : hosted) {
            String name = world.name().isBlank() ? world.worldId() : world.name();
            // MC-JOIN-1: readiness is a measurement, not an assumption. The worker publishes
            // `mc_route` only while a game is actually listening for this world, so its absence is
            // the one honest signal this node has that the world is seeded but not enterable —
            // hosting it says nothing about whether anyone can walk into it. Stamping HEALTHY and
            // full reliability regardless is what made a closed world look exactly like a live one.
            boolean live = !world.mcRoute().isBlank();
            out.add(new TorrentWorldEntry(
                    name,
                    world.players(),
                    0,                       // stored chunks: real count arrives with the content plane
                    live ? 10_000 : 0,       // a world you host is fully reliable while it is up
                    live ? WorldHealth.HEALTHY : WorldHealth.DEAD,
                    -1,                      // no retention countdown for a live-hosted world
                    owner == null ? "" : owner,
                    world.worldId(),
                    world.mcRoute()));
        }
        return out;
    }

    /**
     * Map tracker directory entries to view-model entries. Pure and unit-testable.
     *
     * @param catalog the merged tracker catalog.
     * @return the network entries (game endpoint resolved lazily at join time).
     */
    public static List<TorrentWorldEntry> buildNetworkEntries(List<TrackerCatalogEntry> catalog) {
        List<TorrentWorldEntry> out = new ArrayList<>(catalog.size());
        long now = System.currentTimeMillis();
        for (TrackerCatalogEntry entry : catalog) {
            String name = entry.worldName().isBlank()
                    ? shortId(entry.genesisHash()) : entry.worldName();
            long deadline = entry.retentionDeadlineEpochMillis();
            long countdown = deadline <= 0 ? -1 : Math.max(0, (deadline - now) / 1000);
            out.add(new TorrentWorldEntry(
                    name,
                    // A real population now: the count rides each host's tracker announce (only a
                    // node with a game in the world reports one), and the tracker takes the maximum
                    // across the peers that could see rather than counting peers. It reads 0 for a
                    // world whose observers have all left — which is the honest floor, since the
                    // catalog field cannot carry "unknown".
                    entry.worldPlayerCount(),
                    entry.storedChunks(),
                    entry.reliabilityBps(),
                    entry.health(),
                    countdown,
                    "",                       // owner names arrive with the identity gossip (L-49)
                    entry.genesisHash().toHex(),
                    ""));
        }
        return out;
    }

    private static String shortId(Bytes genesisHash) {
        String hex = genesisHash.toHex();
        return hex.length() <= 12 ? hex : hex.substring(0, 12) + "…";
    }
}
