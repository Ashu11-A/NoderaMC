package dev.nodera.mod.client.multiplayer;

import dev.nodera.diagnostics.view.Panel;
import dev.nodera.diagnostics.view.PanelLayout;
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
 * live {@link Panel} from a supplier each frame and paints each cell in the colour
 * {@link Palette} assigns its semantic. No layout or colour decisions live here.
 *
 * <p>Layout (fit, wrap, truncate, scroll extent) belongs to {@link PanelLayout}; this widget
 * paints what it is given, scissors the viewport, and turns a wheel into a scroll offset.
 *
 * <p>Thread-context: client (render) thread only.
 */
public final class PanelWidget extends AbstractWidget {

    private static final int ROW_HEIGHT = 12;
    private static final int CELL_GAP = 8;
    private static final int SCROLLBAR_WIDTH = 3;

    private final Supplier<Panel> panelSupplier;
    /** Current scroll offset in pixels, and the extent the last layout pass reported. */
    private int scroll;
    private int maxScroll;

    public PanelWidget(int x, int y, int width, int height, Component title,
                       Supplier<Panel> panelSupplier) {
        super(x, y, width, height, title);
        this.panelSupplier = panelSupplier;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Panel panel = panelSupplier.get();
        var font = Minecraft.getInstance().font;
        // MC-GUI-2: every placement decision — fit, wrap, truncate, scroll extent — is made by the
        // Minecraft-free PanelLayout against this font's own measurement, so it is true at whatever
        // GUI scale the player has rather than at the author's. This widget only paints.
        PanelLayout.Laid laid = PanelLayout.lay(getMessage().getString(), panel,
                contentWidth(), getHeight(), ROW_HEIGHT, CELL_GAP, scroll, font::width);
        this.maxScroll = laid.maxScroll();
        if (scroll > maxScroll) {
            scroll = maxScroll;
        }
        // Scissor, because wrapping bounds the panel horizontally but a scrolled line still
        // straddles the top and bottom edges by design.
        graphics.enableScissor(getX(), getY(), getX() + getWidth(), getY() + getHeight());
        for (PanelLayout.Placed placed : laid.placements()) {
            if (placed.y() + ROW_HEIGHT < 0 || placed.y() > getHeight()) {
                continue;
            }
            graphics.drawString(font, placed.text(), getX() + placed.x(), getY() + placed.y(),
                    colorOf(Palette.chat(placed.semantic())));
        }
        graphics.disableScissor();
        renderScrollbar(graphics, laid);
    }

    /** The text column: the full width less the scrollbar gutter when one is needed. */
    private int contentWidth() {
        return Math.max(1, getWidth() - (maxScroll > 0 ? SCROLLBAR_WIDTH + 2 : 0));
    }

    /** A proportional thumb — the "there is more below" signal the panel never had. */
    private void renderScrollbar(GuiGraphics graphics, PanelLayout.Laid laid) {
        if (laid.maxScroll() <= 0) {
            return;
        }
        int trackX = getX() + getWidth() - SCROLLBAR_WIDTH;
        int content = laid.contentLines() * ROW_HEIGHT;
        int thumbHeight = Math.max(8, getHeight() * getHeight() / Math.max(1, content));
        int travel = getHeight() - thumbHeight;
        int thumbY = getY() + (int) ((long) travel * scroll / laid.maxScroll());
        graphics.fill(trackX, getY(), trackX + SCROLLBAR_WIDTH, getY() + getHeight(), 0x40000000);
        graphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight, 0xFF8C8C9C);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (maxScroll <= 0 || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (deltaY * ROW_HEIGHT)));
        return true;
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
