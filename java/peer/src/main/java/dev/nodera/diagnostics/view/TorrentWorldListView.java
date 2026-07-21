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
 * ({@code diagnostics} → {@code core}, nothing else); the mod-side {@code TrackerDataSource}
 * unpacks {@code TrackerResponse} before handing data over.
 *
 * @Thread-context stateless static functions over immutable inputs; any thread.
 */
public final class TorrentWorldListView {

    /** The panel title key. */
    public static final String PANEL_TITLE = "nodera.multiplayer.torrent_worlds";

    /**
     * One torrent world as the tracker reports it.
     *
     * @param name                      the display name from the tracker directory (Task 20).
     * @param playerCount               players currently online in the world.
     * @param storedChunks              distinct pieces held network-wide (Task 19/21).
     * @param reliabilityBps            mean network reliability in basis points (Task 22).
     * @param health                    the tracker's {@link WorldHealth} classification.
     * @param retentionSecondsRemaining seconds left on the 24 h decommission countdown (Task 22);
     *                                  negative when no countdown is running.
     * @param hostName                  the world owner/host's display name (the author who shared it);
     *                                  empty when unknown (e.g. a bare tracker answer with no owner).
     */
    public record TorrentWorldEntry(
            String name,
            long playerCount,
            long storedChunks,
            int reliabilityBps,
            WorldHealth health,
            long retentionSecondsRemaining,
            String hostName
    ) {
        public TorrentWorldEntry {
            if (name == null) {
                throw new IllegalArgumentException("name must not be null");
            }
            if (health == null) {
                throw new IllegalArgumentException("health must not be null");
            }
            hostName = hostName == null ? "" : hostName;
        }

        /** @return whether an owner/host name is known for this world. */
        public boolean hasHost() {
            return !hostName.isBlank();
        }
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
        cells.add(Cell.bold(entry.name(), health));
        if (entry.hasHost()) {
            cells.add(Cell.of("by " + entry.hostName(), Semantic.SECONDARY));
        }
        cells.add(Cell.of(entry.playerCount() + " players", Semantic.NEUTRAL));
        cells.add(Cell.of(entry.storedChunks() + " chunks", Semantic.SECONDARY));
        cells.add(Cell.of(formatReliability(entry.reliabilityBps()), Semantic.SECONDARY));
        cells.add(Cell.of(entry.health().name(), health));
        if (entry.retentionSecondsRemaining() >= 0) {
            cells.add(Cell.of("drops in " + formatCountdown(entry.retentionSecondsRemaining()),
                    Semantic.WORLD_DEGRADED));
        }
        return new Row(cells);
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
