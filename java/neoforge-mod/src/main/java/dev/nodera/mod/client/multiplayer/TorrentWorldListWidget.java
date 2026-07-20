package dev.nodera.mod.client.multiplayer;

import dev.nodera.diagnostics.view.Cell;
import dev.nodera.diagnostics.view.Panel;
import dev.nodera.diagnostics.view.Row;
import dev.nodera.diagnostics.view.TorrentWorldListView;
import dev.nodera.diagnostics.view.TorrentWorldListView.TorrentWorldEntry;
import dev.nodera.mod.debug.render.Palette;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The torrent-world list on the multiplayer page (Task 26). Pure render adapter: it holds tracker
 * entries + the current search query, asks {@code TorrentWorldListView} for the {@link Panel}, and
 * paints each {@link Cell} in the colour {@link Palette} assigns its semantic — red for worlds
 * that lost data, gray for dead ones, countdown cell while the 24 h clock runs. No layout or
 * colour decisions live here.
 *
 * <p>Thread-context: client (render) thread only.
 */
public final class TorrentWorldListWidget extends AbstractWidget {

    private static final int ROW_HEIGHT = 12;
    private static final int CELL_GAP = 8;

    private List<TorrentWorldEntry> entries = List.of();
    private String search = "";
    private int selectedIndex = -1;

    public TorrentWorldListWidget(int x, int y, int width, int height) {
        super(x, y, width, height, Component.translatable("nodera.multiplayer.torrent_worlds"));
    }

    /** Replace the tracker data (called when a tracker answer arrives). */
    public void setEntries(List<TorrentWorldEntry> entries) {
        this.entries = List.copyOf(entries);
        this.selectedIndex = -1;
    }

    /** Update the search filter (wired to {@link WorldSearchBox}). */
    public void setSearch(String search) {
        this.search = search == null ? "" : search;
        this.selectedIndex = -1;
    }

    /** @return the currently selected world (post-search/order), or {@code null}. */
    public TorrentWorldEntry selected() {
        List<TorrentWorldEntry> visible = TorrentWorldListView.panelEntries(entries, search);
        if (selectedIndex < 0 || selectedIndex >= visible.size()) {
            return null;
        }
        return visible.get(selectedIndex);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        // Header occupies the first ROW_HEIGHT; rows follow.
        int row = (int) ((mouseY - getY()) / ROW_HEIGHT) - 1;
        int count = TorrentWorldListView.panelEntries(entries, search).size();
        this.selectedIndex = (row >= 0 && row < count) ? row : -1;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Panel panel = TorrentWorldListView.panel(entries, search);
        var font = Minecraft.getInstance().font;
        int y = getY();
        graphics.drawString(font, getMessage().getString(), getX(), y,
                colorOf(Palette.chat(dev.nodera.diagnostics.state.Semantic.HEADING)));
        y += ROW_HEIGHT;
        int rowIndex = 0;
        for (Row row : panel.rows()) {
            if (y + ROW_HEIGHT > getY() + getHeight()) {
                break; // clipped; scrolling arrives with the live GUI pass
            }
            if (rowIndex == selectedIndex) {
                graphics.fill(getX() - 2, y - 1, getX() + getWidth(), y + ROW_HEIGHT - 1, 0x553B82F6);
            }
            int x = getX();
            for (Cell cell : row.cells()) {
                graphics.drawString(font, cell.text(), x, y, colorOf(Palette.chat(cell.semantic())));
                x += font.width(cell.text()) + CELL_GAP;
            }
            y += ROW_HEIGHT;
            rowIndex++;
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
