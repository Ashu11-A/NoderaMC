package dev.nodera.mod.client.multiplayer;

import dev.nodera.diagnostics.view.TorrentWorldListView;
import dev.nodera.diagnostics.view.TorrentWorldListView.TorrentWorldEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The scrollable world list on the Nodera multiplayer screen (Task 31 redesign of the flat
 * {@code TorrentWorldListWidget}): one two-line row per world — name + owner on the first line,
 * players / chunks / reliability / joinability on the second — with the health colour policy from
 * {@link TorrentWorldListView} and vanilla's own selection/scrolling behaviour.
 *
 * <p>Thread-context: client (render) thread only.
 */
public final class NoderaWorldList extends ObjectSelectionList<NoderaWorldList.Row> {

    private static final int ROW_HEIGHT = 36;

    private List<TorrentWorldEntry> entries = List.of();
    private String search = "";

    public NoderaWorldList(Minecraft minecraft, int width, int height, int y) {
        super(minecraft, width, height, y, ROW_HEIGHT);
    }

    /** Replace the feed data and re-filter, preserving the selection by world id if possible. */
    public void setEntries(List<TorrentWorldEntry> entries) {
        this.entries = List.copyOf(entries);
        rebuild();
    }

    /** Update the search filter (wired to the search box). */
    public void setSearch(String search) {
        this.search = search == null ? "" : search;
        rebuild();
    }

    /** @return the currently selected world, or {@code null}. */
    public TorrentWorldEntry selectedWorld() {
        Row row = getSelected();
        return row == null ? null : row.entry;
    }

    private void rebuild() {
        String selectedId = getSelected() == null ? null : getSelected().entry.worldIdHex();
        clearEntries();
        for (TorrentWorldEntry entry : TorrentWorldListView.panelEntries(entries, search)) {
            Row row = new Row(entry);
            addEntry(row);
            if (selectedId != null && !selectedId.isBlank() && selectedId.equals(entry.worldIdHex())) {
                setSelected(row);
            }
        }
    }

    @Override
    public int getRowWidth() {
        return this.width - 24;
    }

    /** One world row. */
    public final class Row extends ObjectSelectionList.Entry<Row> {
        final TorrentWorldEntry entry;

        Row(TorrentWorldEntry entry) {
            this.entry = entry;
        }

        @Override
        public Component getNarration() {
            return Component.literal(entry.name());
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width,
                           int height, int mouseX, int mouseY, boolean hovering, float partialTick) {
            var font = Minecraft.getInstance().font;

            // Health dot + name + owner.
            graphics.fill(left + 2, top + 6, left + 8, top + 12, dotColor());
            graphics.drawString(font, entry.name(), left + 14, top + 4, 0xFFFFFF);
            if (entry.hasHost()) {
                int nameWidth = font.width(entry.name());
                graphics.drawString(font,
                        Component.translatable("nodera.multiplayer.row.by", entry.hostName()),
                        left + 20 + nameWidth, top + 4, 0x8C8C9C);
            }

            // Second line: population + storage + reliability.
            String stats = entry.playerCount() + " online"
                    + (entry.storedChunks() > 0 ? " · " + entry.storedChunks() + " chunks" : "")
                    + " · " + (entry.reliabilityBps() / 100) + "% reliable";
            graphics.drawString(font, stats, left + 14, top + 18, 0x9C9CA8);

            // Right-aligned joinability / health badge.
            Component badge = badge();
            int badgeWidth = font.width(badge);
            graphics.drawString(font, badge, left + width - badgeWidth - 6, top + 11, badgeColor());
        }

        private int dotColor() {
            return switch (entry.health()) {
                case HEALTHY -> 0xFF4CBB6C;
                case DEGRADED -> 0xFFE05C5C;
                case DEAD -> 0xFF6E6E78;
            };
        }

        private Component badge() {
            if (!entry.mcRoute().isBlank()) {
                return Component.translatable("nodera.multiplayer.row.joinable");
            }
            if (entry.retentionSecondsRemaining() >= 0) {
                return Component.translatable("nodera.multiplayer.row.countdown",
                        formatCountdown(entry.retentionSecondsRemaining()));
            }
            return switch (entry.health()) {
                case HEALTHY -> Component.translatable("nodera.multiplayer.row.online");
                case DEGRADED -> Component.translatable("nodera.multiplayer.row.degraded");
                case DEAD -> Component.translatable("nodera.multiplayer.row.offline");
            };
        }

        private int badgeColor() {
            if (!entry.mcRoute().isBlank()) {
                return 0x4CBB6C;
            }
            return switch (entry.health()) {
                case HEALTHY -> 0x4CBB6C;
                case DEGRADED -> 0xE05C5C;
                case DEAD -> 0x8C8C9C;
            };
        }

        private String formatCountdown(long seconds) {
            long minutes = seconds / 60;
            return minutes == 0 ? "<1m" : (minutes / 60) + "h" + (minutes % 60) + "m";
        }
    }
}
