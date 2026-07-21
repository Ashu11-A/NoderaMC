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
    public static final String TITLE = "nodera.multiplayer.tab.trackers";

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
            rows.add(Row.of(Cell.of("No trackers configured", Semantic.SECONDARY)));
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
        cells.add(Cell.bold(e.endpoint(), health));
        cells.add(Cell.of(e.reachable() ? "online" : "offline", health));
        if (e.worldsIndexed() >= 0) {
            cells.add(Cell.of(e.worldsIndexed() + " worlds", Semantic.SECONDARY));
        }
        cells.add(Cell.of(formatAck(e.secondsSinceAck()), Semantic.SECONDARY));
        return new Row(cells);
    }

    /** Seconds → {@code "ack 12s ago"} / {@code "no ack"}. */
    static String formatAck(long seconds) {
        if (seconds < 0) {
            return "no ack";
        }
        if (seconds < 60) {
            return "ack " + seconds + "s ago";
        }
        return "ack " + (seconds / 60) + "m ago";
    }
}
