package dev.nodera.diagnostics.view;

import dev.nodera.diagnostics.state.Semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The pure layout pass behind any {@link Panel} widget (MC-GUI-2).
 *
 * <p>The defect this exists to remove: the panel widget measured nothing against its own right
 * edge, called no scissor, and dropped every row past the bottom with no scrollbar. A tracker row
 * is about 234 px of text against a 224 px column at GUI width 480, so the last cell of every
 * status row ran off the panel and over whatever was beside it — and a fourth tracker simply did
 * not exist as far as the screen was concerned.
 *
 * <p>Two guarantees, and they are what the tests assert rather than a screenshot:
 *
 * <ol>
 *   <li><b>Nothing is drawn outside the panel horizontally.</b> A cell that does not fit on the
 *       current line wraps to a continuation line; a cell that cannot fit on a line of its own is
 *       truncated with an ellipsis until it does. Both decisions are made against the caller's own
 *       text measurement, so they hold at every GUI scale rather than at the one the author had
 *       open.</li>
 *   <li><b>Every row is reachable.</b> Content taller than the panel produces a positive
 *       {@link Laid#maxScroll()}, and at that offset the last line is inside the viewport. The
 *       widget turns that into a scrollbar and a wheel handler; a caller that ignores it still gets
 *       an honest "there is more" signal instead of silence.</li>
 * </ol>
 *
 * <p>Minecraft-free on purpose: the measurement arrives as a {@link ToIntFunction} (the font's
 * width in the game, a stub in tests), which is what makes the rule testable at all.
 *
 * @Thread-context stateless static functions over immutable inputs; any thread.
 */
public final class PanelLayout {

    /** The ellipsis appended to a cell that had to be cut to fit. */
    public static final String ELLIPSIS = "…";

    private PanelLayout() {
    }

    /**
     * One piece of text placed inside the panel's local coordinate space.
     *
     * @param text     the text to draw — already truncated if it had to be.
     * @param x        left edge, relative to the panel's left edge (never negative).
     * @param y        top edge, relative to the panel's top edge; may be negative or beyond the
     *                 panel's height when the caller scissors instead of dropping.
     * @param width    the measured width of {@code text}; {@code x + width} never exceeds the
     *                 panel width.
     * @param semantic the colour policy the view model assigned.
     * @param bold     whether the cell asked to be bold.
     */
    public record Placed(String text, int x, int y, int width, Semantic semantic, boolean bold) {
    }

    /**
     * A laid-out panel.
     *
     * @param placements   every visible piece of text, in draw order.
     * @param contentLines the number of text lines the panel's content occupies (title included).
     * @param maxScroll    the largest useful scroll offset in pixels; 0 when everything fits.
     */
    public record Laid(List<Placed> placements, int contentLines, int maxScroll) {

        /** @return whether the content is taller than the viewport (the scrollbar's condition). */
        public boolean scrollable() {
            return maxScroll > 0;
        }
    }

    /**
     * Lay a panel out.
     *
     * @param title      the panel's title line (drawn first); {@code null} or blank draws no title.
     * @param panel      the panel whose rows to place.
     * @param width      the panel's width in pixels.
     * @param height     the panel's height in pixels.
     * @param rowHeight  the line height in pixels.
     * @param cellGap    the horizontal gap between two cells on the same line.
     * @param scroll     the current scroll offset in pixels (clamped into range).
     * @param measure    text → width in pixels (the font, or a stub).
     * @return the placements plus the scroll extent.
     */
    public static Laid lay(String title, Panel panel, int width, int height, int rowHeight,
                           int cellGap, int scroll, ToIntFunction<String> measure) {
        List<Placed> out = new ArrayList<>();
        int line = 0;
        if (title != null && !title.isBlank()) {
            String fitted = fit(title, width, measure);
            out.add(new Placed(fitted, 0, 0, measure.applyAsInt(fitted), Semantic.HEADING, true));
            line = 1;
        }
        for (Row row : panel.rows()) {
            int x = 0;
            boolean firstOnLine = true;
            for (Cell cell : row.cells()) {
                String fitted = fit(cell.text(), width, measure);
                int w = measure.applyAsInt(fitted);
                if (!firstOnLine && x + w > width) {
                    // Wrap rather than overflow: a status row is several short cells, and losing
                    // the last one off the right edge is exactly the bug.
                    line++;
                    x = 0;
                    firstOnLine = true;
                }
                out.add(new Placed(fitted, x, line * rowHeight, w, cell.semantic(), cell.bold()));
                x += w + cellGap;
                firstOnLine = false;
            }
            line++;
        }
        int contentHeight = line * rowHeight;
        int maxScroll = Math.max(0, contentHeight - height);
        int clamped = Math.max(0, Math.min(scroll, maxScroll));
        if (clamped != 0) {
            List<Placed> shifted = new ArrayList<>(out.size());
            for (Placed p : out) {
                shifted.add(new Placed(p.text(), p.x(), p.y() - clamped, p.width(), p.semantic(),
                        p.bold()));
            }
            out = shifted;
        }
        return new Laid(List.copyOf(out), line, maxScroll);
    }

    /**
     * Cut {@code text} until it fits inside {@code width}, ellipsis included.
     *
     * <p>Measured, not counted in characters: proportional fonts make a character budget wrong for
     * every string that is not all {@code m}s, and the whole point of this class is that the fit is
     * true at the width the player actually has.
     *
     * @param text    the text.
     * @param width   the available width in pixels.
     * @param measure text → width.
     * @return {@code text}, or a truncated form ending in {@link #ELLIPSIS}.
     */
    public static String fit(String text, int width, ToIntFunction<String> measure) {
        if (text == null || text.isEmpty() || measure.applyAsInt(text) <= width) {
            return text == null ? "" : text;
        }
        int end = text.length();
        while (end > 0 && measure.applyAsInt(text.substring(0, end) + ELLIPSIS) > width) {
            end--;
        }
        return end == 0 ? "" : text.substring(0, end) + ELLIPSIS;
    }
}
