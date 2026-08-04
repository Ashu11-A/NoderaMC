package dev.nodera.mod.client.worldlist;

import dev.nodera.diagnostics.view.PublicWorldBadgeView;
import dev.nodera.endpoint.world.NoderaWorldStore;
import dev.nodera.mod.client.multiplayer.MultiplayerWorldFeed;
import dev.nodera.diagnostics.view.Cell;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What to draw on one row of the singleplayer world list, for a world that is on the network.
 *
 * <h2>Why this is a cache and not a lookup</h2>
 *
 * <p>A row renders every frame, and the only thing that connects a save folder to a Nodera world is
 * a file inside it — {@code nodera-world.dat}. Reading that per row per frame would put sixty file
 * reads a second per visible world on the render thread, for a value that changes when somebody
 * shares a world and at no other time.
 *
 * <p>So the save→world mapping is read once per save and kept. The <i>player count</i> is not
 * cached here: it comes from {@link MultiplayerWorldFeed}, which already refreshes on its own
 * background cadence, and reading it is a map lookup.
 *
 * <p><b>Unknown is never zero.</b> A world nobody has reported a count for publishes {@code -1}, and
 * the badge says "shared" rather than "0 online" for it — the same discipline the rest of this
 * codebase now holds, having got it wrong on three separate surfaces.
 *
 * @Thread-context render thread; the map is concurrent so a refresh from elsewhere is safe.
 */
public final class WorldRowBadges {

    /** Save folder name → the world id it carries, or {@code ""} when it carries none. */
    private static final Map<String, String> WORLD_IDS = new ConcurrentHashMap<>();

    private WorldRowBadges() {
    }

    /**
     * The badge for one save, or {@code null} when that save is not on the network.
     *
     * @param levelId the save folder name, from {@code LevelSummary.getLevelId()}.
     * @return the cell to render, or {@code null} to draw nothing.
     * @Thread-context render thread.
     */
    public static Cell badgeFor(String levelId) {
        String worldId = worldIdOf(levelId);
        if (worldId.isEmpty()) {
            return null;
        }
        Long players = MultiplayerWorldFeed.playersInHostedWorld(worldId);
        if (players == null) {
            return null; // this install does not currently share it
        }
        return PublicWorldBadgeView.badgeCell(players);
    }

    /**
     * The world id a save carries, read once and remembered.
     *
     * <p>A save with no {@code nodera-world.dat} caches the empty string rather than nothing, so a
     * world that has never been shared costs exactly one failed read for the life of the process
     * instead of one per frame.
     */
    private static String worldIdOf(String levelId) {
        if (levelId == null || levelId.isBlank()) {
            return "";
        }
        return WORLD_IDS.computeIfAbsent(levelId, id -> {
            try {
                Path save = Minecraft.getInstance().getLevelSource().getLevelPath(id);
                return NoderaWorldStore.read(save)
                        .map(identity -> identity.worldId().toHex())
                        .orElse("");
            } catch (RuntimeException unreadable) {
                return "";
            }
        });
    }

    /**
     * Forget the save→world mapping.
     *
     * <p>Called when the world list is (re)built, because that is when a save may have been shared,
     * unshared or deleted since the mapping was taken — and a screen open is rare enough that
     * re-reading a handful of small files on it costs nothing.
     *
     * @Thread-context render thread.
     */
    public static void invalidate() {
        WORLD_IDS.clear();
    }
}
