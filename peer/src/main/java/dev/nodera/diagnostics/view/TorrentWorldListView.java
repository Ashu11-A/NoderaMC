package dev.nodera.diagnostics.view;

import dev.nodera.core.identity.WorldHealth;
import dev.nodera.diagnostics.state.Semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The pure view model behind the Task 26 multiplayer page: tracker data → one {@link Panel} of
 * torrent-world {@link Row}s (name, players, stored chunks, reliability, health, retention
 * countdown). Health drives colour through {@link Semantic}'s dedicated world-health values —
 * DEGRADED worlds are red, DEAD worlds gray — exactly the Task 18 pattern: the view model decides
 * policy, the mod-side {@code Palette} decides colour, the widget only renders cells.
 *
 * <p>Layering: consumes plain values plus the {@code core} {@link WorldHealth} enum only
 * ({@code diagnostics} → {@code core}, nothing else); the mod-side {@code MultiplayerWorldFeed}
 * unpacks tracker catalog data before handing rows over.
 *
 * @Thread-context stateless static functions over immutable inputs; any thread.
 */
public final class TorrentWorldListView {

    /** The panel title key. */
    public static final String PANEL_TITLE = "nodera.multiplayer.torrent_worlds";
    /** Lang key for the "by &lt;host&gt;" cell ({@code %s} = host name). */
    public static final String KEY_BY_HOST = "nodera.multiplayer.row.by";
    /** Lang key for a known population ({@code %s} = count). */
    public static final String KEY_PLAYERS = "nodera.world.players";
    /** Lang key for an unreported population — nothing that can see into the world has said. */
    public static final String KEY_PLAYERS_UNKNOWN = "nodera.world.players_unknown";
    /** Lang key for the stored-piece count ({@code %s} = count). */
    public static final String KEY_CHUNKS = "nodera.world.chunks";
    /** Lang key for the reliability cell ({@code %s} = formatted percentage). */
    public static final String KEY_RELIABILITY = "nodera.world.reliability";
    /** Lang key prefix for {@link WorldHealth}; the enum name (lower case) completes it. */
    public static final String HEALTH_PREFIX = "nodera.world.health.";
    /** Lang key for the retention countdown ({@code %s} = formatted duration). */
    public static final String KEY_COUNTDOWN = "nodera.multiplayer.row.countdown";

    /**
     * One torrent world as the tracker reports it.
     *
     * @param name                      the display name from the tracker directory (Task 20).
     * @param playerCount               players currently online in the world, or <b>negative when
     *                                  nothing that can see into the world has reported</b>. Only a
     *                                  node with a game in the world can count them; a tracker
     *                                  cannot, and a seeder cannot. Rendering "unknown" as
     *                                  {@code 0 players} is what made this number wrong on every
     *                                  row that did not come from the world's own host.
     * @param storedChunks              distinct pieces held network-wide (Task 19/21).
     * @param reliabilityBps            mean network reliability in basis points (Task 22).
     * @param health                    the tracker's {@link WorldHealth} classification.
     * @param retentionSecondsRemaining seconds left on the 24 h decommission countdown (Task 22);
     *                                  negative when no countdown is running.
     * @param hostName                  the world owner/host's display name (the author who shared it);
     *                                  empty when unknown (e.g. a bare tracker answer with no owner).
     * @param worldIdHex                the world's id (genesis hash, hex) — the key the join flow
     *                                  resolves through the tracker; empty when unknown.
     * @param mcRoute                   the host's known Minecraft game endpoint ({@code host:port});
     *                                  empty when not known locally (resolved at join time).
     */
    public record TorrentWorldEntry(
            String name,
            long playerCount,
            long storedChunks,
            int reliabilityBps,
            WorldHealth health,
            long retentionSecondsRemaining,
            String hostName,
            String worldIdHex,
            String mcRoute
    ) {
        public TorrentWorldEntry {
            if (name == null) {
                throw new IllegalArgumentException("name must not be null");
            }
            if (health == null) {
                throw new IllegalArgumentException("health must not be null");
            }
            hostName = hostName == null ? "" : hostName;
            worldIdHex = worldIdHex == null ? "" : worldIdHex;
            mcRoute = mcRoute == null ? "" : mcRoute;
        }

        /** Pre-join-flow shape: no world id / game endpoint known. */
        public TorrentWorldEntry(String name, long playerCount, long storedChunks,
                                 int reliabilityBps, WorldHealth health,
                                 long retentionSecondsRemaining, String hostName) {
            this(name, playerCount, storedChunks, reliabilityBps, health,
                    retentionSecondsRemaining, hostName, "", "");
        }

        /**
         * Whether this world can be entered right now — the one place that decides it (MC-JOIN-1).
         *
         * <p>A row used to be offered as joinable because it existed: a world this install hosts was
         * stamped {@code HEALTHY} with full reliability even when its game was closed, and the join
         * button armed itself from "a row is selected". So a world nobody could enter was offered
         * exactly as confidently as one that could, and the player learned the difference from a
         * connection timeout.
         *
         * <p>The rule: a {@link WorldHealth#DEAD} world is not joinable — for one of this install's
         * own worlds that is precisely "the host game is closed", because the feed derives health
         * from whether the worker reports a live game endpoint — and neither is a world with no
         * handle to reach it by (no game route AND no world id for the join flow to resolve).
         *
         * @return whether the join flow may be offered for this world.
         */
        public boolean joinable() {
            // A DEAD world used to be un-joinable, which made the continuity lane's
            // "the host is gone but the world is not" path unreachable from the one screen it was
            // written for: the flow that materialises a world from the network needs only an id,
            // and refusing to offer it left players staring at a world the swarm was holding for
            // them. What is genuinely un-joinable is a world with no handle at all.
            return !(mcRoute.isBlank() && worldIdHex.isBlank());
        }

        /** @return whether joining will connect to a live host rather than materialise the world. */
        public boolean joinableNow() {
            return health != WorldHealth.DEAD && !mcRoute.isBlank();
        }

        /** @return whether an owner/host name is known for this world. */
        public boolean hasHost() {
            return !hostName.isBlank();
        }

        /** @return whether anything that can see into the world has reported its player count. */
        public boolean playersKnown() {
            return playerCount >= 0;
        }

        /**
         * @return the population as a translation key + argument: {@link #KEY_PLAYERS} with the
         *         count, or {@link #KEY_PLAYERS_UNKNOWN} when nothing that can see into the world
         *         has reported.
         */
        public Cell playersCell() {
            return TorrentWorldListView.playersCell(playerCount, Semantic.NEUTRAL);
        }
    }

    /**
     * Format a player count for display — <b>the</b> place that decides how one is rendered.
     *
     * <p>Centralised because it was not, and the cost of that was visible: the sentinel for "nobody
     * that can see into this world has reported" reached a screen as the literal string
     * {@code "-1 online"}, next to worlds that read {@code "0 online"} — so a player comparing two
     * rows saw a negative population and a real one and had no way to tell which was a measurement.
     * Every surface that prints a population goes through here.
     *
     * @param playerCount players in-world, or negative when nobody could count.
     * @return the population cell (key + argument, never a finished phrase).
     */
    public static Cell playersCell(long playerCount, Semantic semantic) {
        return playerCount < 0
                ? Cell.tr(KEY_PLAYERS_UNKNOWN, Semantic.SECONDARY)
                : Cell.tr(KEY_PLAYERS, semantic, playerCount);
    }

    private static final Comparator<TorrentWorldEntry> NAME_ORDER =
            Comparator.comparing((TorrentWorldEntry e) -> e.name().toLowerCase(Locale.ROOT))
                    .thenComparing(TorrentWorldEntry::name);

    private TorrentWorldListView() {
    }

    /**
     * Build the multiplayer-page panel: entries whose name contains {@code search}
     * (case-insensitive; blank matches all), in deterministic name order.
     */
    public static Panel panel(List<TorrentWorldEntry> entries, String search) {
        List<TorrentWorldEntry> matching = panelEntries(entries, search);
        List<Row> rows = new ArrayList<>(matching.size());
        for (TorrentWorldEntry entry : matching) {
            rows.add(rowOf(entry));
        }
        return Panel.titled(PANEL_TITLE, Semantic.HEADING, rows);
    }

    /**
     * The filtered (case-insensitive {@code search}; blank matches all) + deterministically
     * name-ordered entries that back {@link #panel} — exposed so a widget can map a clicked row index
     * back to its {@link TorrentWorldEntry} (selection).
     */
    public static List<TorrentWorldEntry> panelEntries(List<TorrentWorldEntry> entries, String search) {
        String needle = search == null ? "" : search.toLowerCase(Locale.ROOT).trim();
        List<TorrentWorldEntry> matching = new ArrayList<>();
        for (TorrentWorldEntry entry : entries) {
            if (needle.isEmpty() || entry.name().toLowerCase(Locale.ROOT).contains(needle)) {
                matching.add(entry);
            }
        }
        matching.sort(NAME_ORDER);
        return matching;
    }

    /** One world row: name · players · chunks · reliability · health (+ countdown when active). */
    static Row rowOf(TorrentWorldEntry entry) {
        Semantic health = semanticOf(entry.health());
        List<Cell> cells = new ArrayList<>(7);
        cells.add(Cell.boldRaw(entry.name(), health));
        if (entry.hasHost()) {
            cells.add(Cell.tr(KEY_BY_HOST, Semantic.SECONDARY, entry.hostName()));
        }
        // Said only when somebody could actually count. A directory row comes from a tracker, which
        // sees announcing peers and not players, so it has no honest number to put here — and the
        // cell it used to fill said "3 players" about three seeders of an empty world.
        cells.add(entry.playersCell());
        cells.add(Cell.tr(KEY_CHUNKS, Semantic.SECONDARY, entry.storedChunks()));
        cells.add(Cell.tr(KEY_RELIABILITY, Semantic.SECONDARY,
                formatReliability(entry.reliabilityBps())));
        cells.add(Cell.tr(healthKey(entry.health()), health));
        if (entry.retentionSecondsRemaining() >= 0) {
            cells.add(Cell.tr(KEY_COUNTDOWN, Semantic.WORLD_DEGRADED,
                    formatCountdown(entry.retentionSecondsRemaining())));
        }
        return new Row(cells);
    }

    /** @return the lang key naming this health state — the word itself lives in the lang file. */
    public static String healthKey(WorldHealth health) {
        return HEALTH_PREFIX + health.name().toLowerCase(Locale.ROOT);
    }

    /** The colour policy for a {@link WorldHealth} — the Task 26 red/gray rule. */
    public static Semantic semanticOf(WorldHealth health) {
        return switch (health) {
            case HEALTHY -> Semantic.WORLD_HEALTHY;
            case DEGRADED -> Semantic.WORLD_DEGRADED;
            case DEAD -> Semantic.WORLD_DEAD;
        };
    }

    /** Basis points → {@code "97.5%"} (pure integer math; one decimal). */
    static String formatReliability(int bps) {
        int clamped = Math.max(0, Math.min(bps, 10_000));
        return (clamped / 100) + "." + (clamped % 100) / 10 + "%";
    }

    /** Seconds → {@code "23h59m"} (floors to the minute; sub-minute shows {@code "<1m"}). */
    static String formatCountdown(long seconds) {
        long minutes = seconds / 60;
        if (minutes == 0) {
            return "<1m";
        }
        return (minutes / 60) + "h" + (minutes % 60) + "m";
    }
}
