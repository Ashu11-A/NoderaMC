package dev.nodera.diagnostics.view;

import dev.nodera.diagnostics.state.Semantic;

import java.util.List;

/**
 * The pure view model behind the Task 31b single-player world-list indicator: a world that has been
 * shared to Nodera is <em>public</em>, so its row carries a badge showing that it is shared and how
 * many peers are currently connected to it. This class holds only the policy + text formatting; the
 * mod-side {@code SelectWorldScreenAddon} feeds it the local host state (from {@code NoderaPeerService})
 * and the live peer count (from a cached {@code TrackerClient} query) and renders the resulting cell.
 *
 * <p>Layering: {@code diagnostics} → {@code core} only; no Minecraft types. Stateless static
 * functions over immutable inputs, so it is unit-testable on the gate with no GUI env — the same
 * discipline as {@link TorrentWorldListView}.
 *
 * @Thread-context stateless; any thread.
 */
public final class PublicWorldBadgeView {

    /** Lang key for the badge shown on a shared world's row. */
    public static final String BADGE_PUBLIC = "nodera.worldlist.public";
    /** Lang key for the badge with a known player count ({@code %s} = count). */
    public static final String BADGE_ONLINE = "nodera.worldlist.public_online";
    /** Lang key for the screen-level summary of one shared world. */
    public static final String SUMMARY_ONE = "nodera.worldlist.shared_summary_one";
    /** Lang key for the screen-level summary of several shared worlds ({@code %s} = count). */
    public static final String SUMMARY_MANY = "nodera.worldlist.shared_summary_many";

    private PublicWorldBadgeView() {
    }

    /**
     * A single world's shared status as the client knows it.
     *
     * @param saveName         the local save's folder/display name (the join key to the tracker
     *                         directory entry — interim until the genesis hash, Task 9/30c).
     * @param shared           whether this save is currently shared to the network.
     * @param connectedPlayers peers currently connected to the shared world; negative when unknown
     *                         (e.g. the first render before the tracker query returns).
     */
    public record PublicWorldStatus(String saveName, boolean shared, long connectedPlayers) {
        public PublicWorldStatus {
            if (saveName == null) {
                throw new IllegalArgumentException("saveName must not be null");
            }
        }

        /** @return an unshared status for a save not on the network. */
        public static PublicWorldStatus notShared(String saveName) {
            return new PublicWorldStatus(saveName, false, -1);
        }
    }

    /**
     * The badge cell for one world row, or {@code null} when the world is not shared (no badge).
     * Public worlds are coloured with the healthy world-health semantic; the count reads
     * {@code "● 3 online"}, or {@code "● Public"} while the count is still unknown.
     */
    public static Cell badge(PublicWorldStatus status) {
        if (status == null || !status.shared()) {
            return null;
        }
        return badgeCell(status.connectedPlayers());
    }

    /**
     * The badge cell for a connected-player count: {@link #BADGE_ONLINE} with the count, or
     * {@link #BADGE_PUBLIC} while the count is still unknown. Key + argument, never a phrase.
     */
    public static Cell badgeCell(long connectedPlayers) {
        if (connectedPlayers < 0) {
            return Cell.tr(BADGE_PUBLIC, Semantic.WORLD_HEALTHY);
        }
        return Cell.tr(BADGE_ONLINE, Semantic.WORLD_HEALTHY, connectedPlayers);
    }

    /** @return how many of the given worlds are currently shared. */
    public static long sharedCount(List<PublicWorldStatus> worlds) {
        if (worlds == null) {
            return 0;
        }
        return worlds.stream().filter(PublicWorldStatus::shared).count();
    }

    /**
     * A screen-level summary of shared worlds as a key + argument, or {@code null} when none are
     * shared (nothing to annotate). Plural selection is a key choice, not string concatenation —
     * a language whose plural rules differ from English can now express it.
     */
    public static Cell summaryCell(List<PublicWorldStatus> worlds) {
        long shared = sharedCount(worlds);
        if (shared == 0) {
            return null;
        }
        return shared == 1
                ? Cell.tr(SUMMARY_ONE, Semantic.WORLD_HEALTHY)
                : Cell.tr(SUMMARY_MANY, Semantic.WORLD_HEALTHY, shared);
    }
}
