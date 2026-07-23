package dev.nodera.mod.client.multiplayer;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.WorldHealth;
import dev.nodera.diagnostics.view.TorrentWorldListView.TorrentWorldEntry;
import dev.nodera.mod.common.CompanionLink;
import dev.nodera.mod.common.NoderaConfig;
import dev.nodera.mod.common.WorkerStateParser;
import dev.nodera.mod.common.WorkerStateParser.HostedWorldInfo;
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
    private static ScheduledExecutorService scheduler;
    private static volatile TrackerClient tracker;

    private MultiplayerWorldFeed() {
    }

    /** The merged Worlds-tab snapshot (own worlds first, then the rest of the network). */
    public static List<TorrentWorldEntry> snapshot() {
        List<TorrentWorldEntry> own = ownWorlds;
        List<TorrentWorldEntry> network = networkWorlds;
        Map<String, TorrentWorldEntry> merged = new LinkedHashMap<>();
        for (TorrentWorldEntry entry : own) {
            merged.put(keyOf(entry), entry);
        }
        for (TorrentWorldEntry entry : network) {
            merged.putIfAbsent(keyOf(entry), entry);
        }
        return List.copyOf(merged.values());
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
            ownWorlds = buildEntries(workerState(), localPlayerName());
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
    public static List<TorrentWorldEntry> buildEntries(String workerStateJson, String owner) {
        List<HostedWorldInfo> hosted = WorkerStateParser.connectedWorlds(workerStateJson);
        List<TorrentWorldEntry> out = new ArrayList<>(hosted.size());
        for (HostedWorldInfo world : hosted) {
            String name = world.name().isBlank() ? world.worldId() : world.name();
            out.add(new TorrentWorldEntry(
                    name,
                    world.players(),
                    0,                       // stored chunks: real count arrives with the content plane
                    10_000,                  // a world you host is fully reliable from your own node
                    WorldHealth.HEALTHY,
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
