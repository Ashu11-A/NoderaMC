package dev.nodera.diagnostics.view;

import java.util.List;

/**
 * A titled block of rows — renders as one chat table, one tab-list section, or one boss-bar group
 * (Task 18). {@link ViewBuilder} produces panels from a {@link dev.nodera.diagnostics.model.TelemetrySnapshot}.
 *
 * @param title        the panel heading text.
 * @param titleSemantic the heading's colour policy.
 * @param rows         the panel rows.
 * @Thread-context immutable record, any thread.
 */
public record Panel(String title, dev.nodera.diagnostics.state.Semantic titleSemantic, List<Row> rows) {

    /** Compact constructor copies the rows into an immutable list. */
    public Panel {
        if (title == null) {
            title = "";
        }
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /**
     * @param key a panel key produced by {@link ViewBuilder}.
     * @return a panel with the given title key, heading semantic, and rows.
     */
    public static Panel titled(String key, dev.nodera.diagnostics.state.Semantic heading, List<Row> rows) {
        return new Panel(key, heading, rows);
    }
}
