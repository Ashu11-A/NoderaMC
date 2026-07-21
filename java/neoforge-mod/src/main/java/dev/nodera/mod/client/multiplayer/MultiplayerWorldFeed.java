package dev.nodera.mod.client.multiplayer;

import dev.nodera.core.identity.WorldHealth;
import dev.nodera.diagnostics.view.TorrentWorldListView.TorrentWorldEntry;
import dev.nodera.mod.common.CompanionLink;
import dev.nodera.mod.common.WorkerStateParser;
import dev.nodera.mod.common.WorkerStateParser.HostedWorldInfo;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The live feed behind the multiplayer screen's <b>Worlds</b> tab — the fix for "I share a world but
 * it never appears in the Multiplayer list". Previously {@code setWorldSupplier} had no live caller,
 * so the Worlds tab was always empty.
 *
 * <p>Because the Multiplayer screen opens from the title screen (the integrated server may be closed),
 * the source of truth is the <b>always-on peer worker</b>, not the in-game server: the worker keeps
 * hosting after the game closes (Task 32). This feed polls the worker's {@code NODERA-STATE} on a
 * daemon cadence, reads its {@code connected_worlds} ({@link WorkerStateParser}), and maps each to a
 * {@link TorrentWorldEntry} with the local player as the owner (a personal worker only hosts this
 * player's worlds). The screen's supplier returns the cached snapshot, never blocking the render
 * thread on a socket round-trip.
 *
 * <p>Remote/other players' public worlds are added once the tracker directory query lands; this feed
 * is the seam that merges them (see {@link #snapshot}).
 *
 * <p>Thread-context: {@link #start} at client setup; the refresh runs on a daemon; {@link #snapshot}
 * is read on the render thread (cached, volatile).
 */
public final class MultiplayerWorldFeed {

    private static volatile List<TorrentWorldEntry> worlds = List.of();
    private static ScheduledExecutorService scheduler;

    private MultiplayerWorldFeed() {
    }

    /** The cached hosted-world snapshot the screen's Worlds-tab supplier returns. */
    public static List<TorrentWorldEntry> snapshot() {
        return worlds;
    }

    /** Start the background worker-state poll. Idempotent; builds the first snapshot immediately. */
    public static synchronized void start() {
        if (scheduler != null) {
            return;
        }
        refresh(); // immediate first snapshot so the tab is populated on first open
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nodera-multiplayer-worlds");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(MultiplayerWorldFeed::refresh, 3, 3, TimeUnit.SECONDS);
    }

    private static void refresh() {
        try {
            worlds = buildEntries(workerState(), localPlayerName());
        } catch (RuntimeException e) {
            // Never let a feed error blank the tab or kill the daemon thread; keep the last snapshot.
        }
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
     * Map the worker's hosted worlds to view-model entries. Pure over its inputs (no Minecraft state),
     * so it is unit-testable — {@link #refresh} supplies the live worker reply + player name.
     *
     * @param workerStateJson the worker STATE reply (may be {@code null}).
     * @param owner           the owner display name to stamp on each hosted world.
     * @return the Worlds-tab entries.
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
                    owner == null ? "" : owner));
        }
        return out;
    }
}
