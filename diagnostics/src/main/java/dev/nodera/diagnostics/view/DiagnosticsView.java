package dev.nodera.diagnostics.view;

import java.util.List;

/**
 * An ordered list of {@link Panel}s — the MC-free presentation intermediate (Task 18).
 *
 * <p>Built by {@link ViewBuilder} from a {@link dev.nodera.diagnostics.model.TelemetrySnapshot}; the
 * thin Minecraft renderers turn each panel into a chat table / tab-list section / boss bar. This is
 * the type every {@code /nodera} subcommand executor returns, so command output is uniform and
 * colour-coded by construction.
 *
 * @param panels the ordered panels.
 * @Thread-context immutable record, any thread.
 */
public record DiagnosticsView(List<Panel> panels) {

    /** Compact constructor copies the list into an immutable one. */
    public DiagnosticsView {
        panels = panels == null ? List.of() : List.copyOf(panels);
    }

    /** @return a view from a vararg of panels. */
    public static DiagnosticsView of(Panel... panels) {
        return new DiagnosticsView(List.of(panels));
    }

    /** @return {@code true} if no panel has any row (the empty-render guard). */
    public boolean isEmpty() {
        for (Panel p : panels) {
            if (!p.rows().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
