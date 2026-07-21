package dev.nodera.diagnostics.view;

import dev.nodera.diagnostics.state.Semantic;

/**
 * One coloured text fragment of a {@link Row} (Task 18).
 *
 * <p>The {@link Semantic} carries the colour <b>policy</b>; the renderer maps it to a concrete
 * colour in exactly one place ({@code dev.nodera.mod.debug.render.Palette}).
 *
 * @param text    the cell text (already formatted by {@link ViewBuilder}).
 * @param semantic the colour policy.
 * @param bold    whether to render bold.
 * @Thread-context immutable record, any thread.
 */
public record Cell(String text, Semantic semantic, boolean bold) {

    /** @return a neutral, non-bold cell. */
    public static Cell of(String text) {
        return new Cell(text, Semantic.NEUTRAL, false);
    }

    /** @return a non-bold cell with the given semantic. */
    public static Cell of(String text, Semantic semantic) {
        return new Cell(text, semantic, false);
    }

    /** @return a bold cell with the given semantic. */
    public static Cell bold(String text, Semantic semantic) {
        return new Cell(text, semantic, true);
    }
}
