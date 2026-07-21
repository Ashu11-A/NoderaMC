package dev.nodera.mod.client.multiplayer;

import dev.nodera.diagnostics.state.Semantic;
import dev.nodera.diagnostics.view.Cell;
import dev.nodera.diagnostics.view.Panel;
import dev.nodera.diagnostics.view.Row;
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
 * Task 31c: a generic render adapter for any {@link Panel} — used by the multiplayer screen's
 * <b>Trackers</b> and <b>Rendezvous</b> tabs, whose {@code diagnostics} view models
 * ({@code TrackerStatusView}/{@code RendezvousStatusView}) already decide policy + colour. Pulls the
 * live {@link Panel} from a supplier each frame and paints each {@link Cell} in the colour
 * {@link Palette} assigns its semantic. No layout or colour decisions live here.
 *
 * <p>Thread-context: client (render) thread only.
 */
public final class PanelWidget extends AbstractWidget {

    private static final int ROW_HEIGHT = 12;
    private static final int CELL_GAP = 8;

    private final Supplier<Panel> panelSupplier;

    public PanelWidget(int x, int y, int width, int height, Component title,
                       Supplier<Panel> panelSupplier) {
        super(x, y, width, height, title);
        this.panelSupplier = panelSupplier;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Panel panel = panelSupplier.get();
        var font = Minecraft.getInstance().font;
        int y = getY();
        graphics.drawString(font, getMessage().getString(), getX(), y,
                colorOf(Palette.chat(Semantic.HEADING)));
        y += ROW_HEIGHT;
        for (Row row : panel.rows()) {
            if (y + ROW_HEIGHT > getY() + getHeight()) {
                break; // clipped; scrolling arrives with the live GUI pass
            }
            int x = getX();
            for (Cell cell : row.cells()) {
                graphics.drawString(font, cell.text(), x, y, colorOf(Palette.chat(cell.semantic())));
                x += font.width(cell.text()) + CELL_GAP;
            }
            y += ROW_HEIGHT;
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }

    private static int colorOf(ChatFormatting formatting) {
        Integer color = formatting.getColor();
        return color == null ? 0xFFFFFF : color;
    }
}
