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

    private final Supplier<PieceMap> mapSupplier;

    public PieceMapWidget(int x, int y, int width, int height, Supplier<PieceMap> mapSupplier) {
        super(x, y, width, height, Component.translatable(PieceMapView.TITLE));
        this.mapSupplier = mapSupplier;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        PieceMap map = mapSupplier.get();
        var font = Minecraft.getInstance().font;

        // Aggregates header line.
        graphics.drawString(font, PieceMapView.aggregates(map), getX(), getY(),
                colorOf(dev.nodera.diagnostics.state.Semantic.HEADING));

        int gridTop = getY() + 14;
        int perRow = Math.max(1, (getWidth() + GAP) / (CELL + GAP));
        int hovered = -1;
        for (int i = 0; i < map.cells().size(); i++) {
            PieceCell cell = map.cells().get(i);
            int col = i % perRow;
            int row = i / perRow;
            int cx = getX() + col * (CELL + GAP);
            int cy = gridTop + row * (CELL + GAP);
            if (cy + CELL > getY() + getHeight() - 12) {
                break; // ran out of vertical room; scrolling arrives with the live GUI pass
            }
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
        if (hovered >= 0) {
            PieceCell c = map.cells().get(hovered);
            graphics.drawString(font, "#" + c.index() + " " + c.state(), getX(), getY() + 7,
                    0xFFFFFFFF);
        }
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
