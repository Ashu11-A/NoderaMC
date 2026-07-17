package dev.nodera.diagnostics.view;

import java.util.List;

/**
 * A row of {@link Cell}s — the columns of a chat table / a boss-bar segment line (Task 18).
 *
 * @param cells the ordered cells.
 * @Thread-context immutable record, any thread.
 */
public record Row(List<Cell> cells) {

    /** Compact constructor copies the list into an immutable one. */
    public Row {
        cells = cells == null ? List.of() : List.copyOf(cells);
    }

    /** @return a row from a vararg of cells. */
    public static Row of(Cell... cells) {
        return new Row(List.of(cells));
    }
}
