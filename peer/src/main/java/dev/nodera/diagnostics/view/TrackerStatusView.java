package dev.nodera.diagnostics.view;

import dev.nodera.diagnostics.state.Semantic;

import java.util.ArrayList;
import java.util.List;

/**
 * Task 31c: the pure view model behind the multiplayer screen's <b>Trackers</b> tab — one row per
 * configured {@code nodera-tracker} endpoint (Task 28), showing whether it is reachable, when it last
 * acked an announce, and how many worlds it indexes. Reachable endpoints are green, unreachable red.
 *
 * <p>Layering: {@code diagnostics} → {@code core} only; the mod-side tab unpacks the live
 * {@code TrackerClient} state into {@link TrackerEndpointStatus} before calling in. Stateless static
 * functions over immutable inputs — unit-testable on the gate.
 *
 * @Thread-context stateless; any thread.
 */
public final class TrackerStatusView {

    /** Lang key for the Trackers tab title. */
    public static final String TITLE = "nodera.multiplayer.panel.trackers";
    /** Lang key for the "no trackers configured" placeholder row. */
    public static final String NONE_CONFIGURED = "nodera.tracker.none_configured";
    /** Lang key for a reachable endpoint. */
    public static final String ONLINE = "nodera.tracker.online";
    /** Lang key for an unreachable endpoint. */
    public static final String OFFLINE = "nodera.tracker.offline";
    /** Lang key for the indexed-world count ({@code %s} = count). */
    public static final String WORLDS_INDEXED = "nodera.tracker.worlds_indexed";
    /** Lang key for "acked N seconds ago" ({@code %s} = seconds). */
    public static final String ACK_SECONDS = "nodera.tracker.ack_seconds";
    /** Lang key for "acked N minutes ago" ({@code %s} = minutes). */
    public static final String ACK_MINUTES = "nodera.tracker.ack_minutes";
    /** Lang key for "never acked". */
    public static final String ACK_NEVER = "nodera.tracker.ack_never";

    /**
     * One tracker endpoint's live status.
     *
     * @param endpoint          {@code host:port} of the tracker.
     * @param reachable         whether the last announce/query succeeded.
     * @param worldsIndexed     worlds this tracker currently lists (negative = unknown).
     * @param secondsSinceAck   seconds since the last successful ack (negative = never).
     */
    public record TrackerEndpointStatus(
            String endpoint, boolean reachable, int worldsIndexed, long secondsSinceAck) {
        public TrackerEndpointStatus {
            if (endpoint == null) {
                throw new IllegalArgumentException("endpoint must not be null");
            }
        }
    }

    private TrackerStatusView() {
    }

    /** Build the Trackers tab panel from the live endpoint statuses, in configured order. */
    public static Panel panel(List<TrackerEndpointStatus> endpoints) {
        List<Row> rows = new ArrayList<>();
        if (endpoints == null || endpoints.isEmpty()) {
            rows.add(Row.of(Cell.tr(NONE_CONFIGURED, Semantic.SECONDARY)));
            return Panel.titled(TITLE, Semantic.HEADING, rows);
        }
        for (TrackerEndpointStatus e : endpoints) {
            rows.add(rowOf(e));
        }
        return Panel.titled(TITLE, Semantic.HEADING, rows);
    }

    static Row rowOf(TrackerEndpointStatus e) {
        Semantic health = e.reachable() ? Semantic.HEALTHY : Semantic.CRITICAL;
        List<Cell> cells = new ArrayList<>(4);
        cells.add(Cell.boldRaw(e.endpoint(), health));
        cells.add(Cell.tr(e.reachable() ? ONLINE : OFFLINE, health));
        if (e.worldsIndexed() >= 0) {
            cells.add(Cell.tr(WORLDS_INDEXED, Semantic.SECONDARY, e.worldsIndexed()));
        }
        cells.add(ackCell(e.secondsSinceAck()));
        return new Row(cells);
    }

    /** Seconds since the last ack → a key + argument; never an assembled phrase (MC-GUI-5). */
    static Cell ackCell(long seconds) {
        if (seconds < 0) {
            return Cell.tr(ACK_NEVER, Semantic.SECONDARY);
        }
        if (seconds < 60) {
            return Cell.tr(ACK_SECONDS, Semantic.SECONDARY, seconds);
        }
        return Cell.tr(ACK_MINUTES, Semantic.SECONDARY, seconds / 60);
    }
}
