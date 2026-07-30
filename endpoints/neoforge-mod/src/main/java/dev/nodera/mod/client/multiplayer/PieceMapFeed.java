package dev.nodera.mod.client.multiplayer;

import dev.nodera.diagnostics.view.PieceMapView;
import dev.nodera.diagnostics.view.PieceMapView.PieceMap;
import dev.nodera.diagnostics.view.PieceMapView.PieceState;
import dev.nodera.diagnostics.view.TorrentWorldListView.TorrentWorldEntry;
import dev.nodera.endpoint.control.CompanionLink;
import dev.nodera.endpoint.state.WorkerPiecesParser;
import dev.nodera.endpoint.state.WorkerPiecesParser.PieceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The live feed behind the client's piece map.
 *
 * <p>{@link NoderaMultiplayerScreen} has always had a "View pieces" button and a
 * {@code setPieceMapSource} seam — but nothing ever installed a source, so the grid was
 * unconditionally empty and the screen reported every world as holding zero pieces. This is the
 * missing half: it polls the always-on worker's {@code NODERA-PIECES} verb for the world the player
 * selected and turns the reply into the {@link PieceMap} the widget renders.
 *
 * <h2>Polling belongs off the render thread</h2>
 *
 * <p>The widget asks for a map every frame. The worker exchange is a loopback socket round trip —
 * cheap, but not 60-times-a-second cheap, and never something to do on the thread drawing the
 * screen. So the render path only ever reads a cached snapshot, and a daemon scheduler refreshes
 * the world currently being looked at.
 *
 * <p>Thread-context: {@link #start} at client setup; {@link #mapFor} is safe from the render
 * thread; refreshes run on the scheduler.
 */
public final class PieceMapFeed {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaPieces");

    /** Refresh cadence for the selected world (loopback, cheap, but not per-frame). */
    private static final int POLL_SECONDS = 2;

    /** worldIdHex → the last piece map built for it. */
    private static final Map<String, PieceMap> CACHE = new ConcurrentHashMap<>();

    /** The world the piece screen is currently showing; only this one is polled. */
    private static volatile String watchedWorldId = "";
    private static volatile String watchedWorldName = "";

    private static ScheduledExecutorService scheduler;

    private PieceMapFeed() {
    }

    /**
     * Start the feed and install it as the screen's piece-map source. Idempotent.
     *
     * @Thread-context client setup.
     */
    public static synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nodera-piece-map");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(PieceMapFeed::refreshWatched, 0, POLL_SECONDS,
                TimeUnit.SECONDS);
        NoderaMultiplayerScreen.setPieceMapSource(byName());
    }

    /** Stop polling (client shutdown). Idempotent. */
    public static synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        CACHE.clear();
    }

    /**
     * Point the feed at a world, so the next refresh fetches it and the screen stops showing the
     * previous selection's grid. Triggers one immediate refresh.
     *
     * @param worldIdHex the world to watch; blank clears the watch.
     * @param worldName  its display name, stamped on the map's header line.
     * @Thread-context any thread (typically the render thread, on button press).
     */
    public static void watch(String worldIdHex, String worldName) {
        watchedWorldId = worldIdHex == null ? "" : worldIdHex;
        watchedWorldName = worldName == null ? "" : worldName;
        ScheduledExecutorService s = scheduler;
        if (s != null && !watchedWorldId.isBlank()) {
            s.execute(PieceMapFeed::refreshWatched);
        }
    }

    /**
     * The cached map for a world, or an empty one while the first reply is in flight.
     *
     * @param worldIdHex the world.
     * @param worldName  its display name, used for the empty placeholder's header.
     * @Thread-context any thread, including the render thread.
     */
    public static PieceMap mapFor(String worldIdHex, String worldName) {
        PieceMap cached = worldIdHex == null ? null : CACHE.get(worldIdHex);
        return cached != null ? cached : PieceMapView.map(worldName, List.of(), 0, 0);
    }

    /**
     * A by-display-name source for {@link NoderaMultiplayerScreen#setPieceMapSource}, which passes
     * the selected world's name rather than its id. The watched id is what was actually fetched, so
     * the lookup resolves through it and the name is only presentation.
     */
    private static Function<String, PieceMap> byName() {
        return name -> mapFor(watchedWorldId, name);
    }

    /** Fetch and cache the watched world's piece picture. Never throws. */
    private static void refreshWatched() {
        String worldId = watchedWorldId;
        if (worldId.isBlank() || !CompanionLink.isPresent()) {
            return;
        }
        try {
            String reply = CompanionLink.client().pieces(worldId).orElse(null);
            PieceInfo info = WorkerPiecesParser.parse(reply);
            CACHE.put(worldId, toMap(info, watchedWorldName));
        } catch (RuntimeException e) {
            // Keep the last good snapshot: a momentarily unreachable worker should not blank a
            // grid the player is reading.
            LOG.debug("piece refresh for {} failed: {}", worldId, e.toString());
        }
    }

    /**
     * Turn a worker report into the view model.
     *
     * <p>The worker reports exactly what it can prove: a piece is either verified present locally
     * or it is not. Everything richer the view model can express — SYNCING, VERIFYING, RARE — is a
     * claim about in-flight work the worker does not currently publish, and inventing it would put
     * colours on the screen that mean nothing. So a held bit is HELD and everything else is
     * MISSING, and the states stay available for when the download lane reports progress.
     *
     * <p>Package-private and pure so it is unit-testable without a worker or a GUI.
     *
     * @param info      the parsed worker report.
     * @param worldName the display name for the header line.
     * @return the piece map to render.
     */
    static PieceMap toMap(PieceInfo info, String worldName) {
        if (info == null || info.isEmpty()) {
            return PieceMapView.map(worldName, List.of(), 0, 0);
        }
        List<PieceState> states = new ArrayList<>(info.pieceCount());
        for (int i = 0; i < info.pieceCount(); i++) {
            states.add(info.held().get(i) ? PieceState.HELD : PieceState.MISSING);
        }
        // A holder with every piece is a seeder. This node's own complete copy counts: it is
        // serving the swarm exactly like any other seeder.
        boolean selfComplete = info.heldCount() >= info.pieceCount();
        int holders = info.holders().size() + (selfComplete ? 1 : 0);
        int seeders = selfComplete ? 1 : 0;
        return PieceMapView.map(worldName, states, seeders, Math.max(holders, seeders));
    }

    /**
     * Watch the world an entry describes — the call the "View pieces" button makes before opening
     * the screen, so the first frame already has a fetch in flight.
     *
     * @param entry the selected world row.
     * @Thread-context render thread.
     */
    public static void watch(TorrentWorldEntry entry) {
        if (entry != null) {
            watch(entry.worldIdHex(), entry.name());
        }
    }
}
