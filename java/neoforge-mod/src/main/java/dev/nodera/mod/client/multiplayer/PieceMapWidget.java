package dev.nodera.mod.client.multiplayer;

import dev.nodera.diagnostics.view.PieceMapView;
import dev.nodera.diagnostics.view.PieceMapView.PieceCell;
import dev.nodera.diagnostics.view.PieceMapView.PieceMap;
import dev.nodera.diagnostics.view.PieceMapView.PieceState;
import dev.nodera.mod.debug.render.Palette;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * Task 31d: the torrent-client-style <em>piece map</em> — a grid of coloured cells, one per content
 * piece of a world, so a player can see how much of the world they hold (green = held) and how
 * synchronised the swarm is. Pure render adapter over {@link PieceMapView}: colour policy lives in
 * the view + {@link Palette}; this only tiles the cells, draws the legend, and the aggregates line.
 *
 * <p>Thread-context: client (render) thread only.
 */
public final class PieceMapWidget extends AbstractWidget {

    private static final int CELL = 8;      // px per piece
    private static final int GAP = 1;       // px between cells
    private static final int LEGEND_STATES = 5;
    private static final int HEADER_HEIGHT = 14;
    private static final int FOOTER_HEIGHT = 12;

    private final Supplier<PieceMap> mapSupplier;

    /** First visible grid row. A large world has far more pieces than fit; the grid scrolls. */
    private int scrollRow;

    public PieceMapWidget(int x, int y, int width, int height, Supplier<PieceMap> mapSupplier) {
        super(x, y, width, height, Component.translatable(PieceMapView.TITLE));
        this.mapSupplier = mapSupplier;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        PieceMap map = mapSupplier.get();
        var font = Minecraft.getInstance().font;

        // Aggregates header line: % held, pieces, seeders, and how many peers share this world.
        graphics.drawString(font, PieceMapView.aggregates(map), getX(), getY(),
                colorOf(dev.nodera.diagnostics.state.Semantic.HEADING));

        int gridTop = getY() + HEADER_HEIGHT;
        int perRow = piecesPerRow();
        int visibleRows = visibleRows();
        int totalRows = PieceMapView.rowsFor(map.total(), perRow);
        // Clamp every frame rather than only on scroll: the map is live, so a world whose piece
        // count shrank (a new, smaller manifest) must not leave the view scrolled past the end.
        scrollRow = Math.max(0, Math.min(scrollRow, Math.max(0, totalRows - visibleRows)));

        int first = scrollRow * perRow;
        int last = Math.min(map.cells().size(), first + visibleRows * perRow);
        int hovered = -1;
        for (int i = first; i < last; i++) {
            PieceCell cell = map.cells().get(i);
            int offset = i - first;
            int cx = getX() + (offset % perRow) * (CELL + GAP);
            int cy = gridTop + (offset / perRow) * (CELL + GAP);
            graphics.fill(cx, cy, cx + CELL, cy + CELL, fillColor(cell.state()));
            if (mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL) {
                hovered = i;
            }
        }

        // Legend + hover tooltip along the bottom.
        int legendY = getY() + getHeight() - 10;
        int lx = getX();
        PieceState[] legend = {PieceState.HELD, PieceState.SYNCING, PieceState.MISSING,
                PieceState.LOCKED, PieceState.RARE};
        for (int i = 0; i < Math.min(LEGEND_STATES, legend.length); i++) {
            PieceState s = legend[i];
            graphics.fill(lx, legendY, lx + 7, legendY + 7, fillColor(s));
            String name = s.name().toLowerCase(java.util.Locale.ROOT);
            graphics.drawString(font, name, lx + 10, legendY, 0xFFAAAAAA);
            lx += 10 + font.width(name) + 10;
        }
        if (totalRows > visibleRows) {
            String more = "rows " + (scrollRow + 1) + "-"
                    + Math.min(totalRows, scrollRow + visibleRows) + " of " + totalRows
                    + " (scroll)";
            graphics.drawString(font, more, getX() + getWidth() - font.width(more), legendY,
                    0xFF888888);
        }
        if (hovered >= 0) {
            PieceCell c = map.cells().get(hovered);
            graphics.drawString(font, "#" + c.index() + " " + c.state(), getX(), getY() + 7,
                    0xFFFFFFFF);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        // One wheel notch moves one grid row; scrollY is positive when scrolling up.
        scrollRow = Math.max(0, scrollRow - (int) Math.signum(scrollY));
        return true;
    }

    /** Cells that fit across the widget's width — at least one, however narrow the screen. */
    private int piecesPerRow() {
        return Math.max(1, (getWidth() + GAP) / (CELL + GAP));
    }

    /** Grid rows that fit between the header line and the legend — at least one. */
    private int visibleRows() {
        int usable = getHeight() - HEADER_HEIGHT - FOOTER_HEIGHT;
        return Math.max(1, (usable + GAP) / (CELL + GAP));
    }

    private static int fillColor(PieceState state) {
        Integer rgb = Palette.chat(PieceMapView.semanticOf(state)).getColor();
        return 0xFF000000 | (rgb == null ? 0x888888 : rgb);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }

    private static int colorOf(dev.nodera.diagnostics.state.Semantic semantic) {
        ChatFormatting formatting = Palette.chat(semantic);
        Integer color = formatting.getColor();
        return color == null ? 0xFFFFFF : color;
    }
}
