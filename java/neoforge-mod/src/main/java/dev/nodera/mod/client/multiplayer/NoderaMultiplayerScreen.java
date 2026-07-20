package dev.nodera.mod.client.multiplayer;

import dev.nodera.diagnostics.view.PieceMapView;
import dev.nodera.diagnostics.view.PieceMapView.PieceMap;
import dev.nodera.diagnostics.view.RendezvousStatusView;
import dev.nodera.diagnostics.view.RendezvousStatusView.RendezvousEndpointStatus;
import dev.nodera.diagnostics.view.TorrentWorldListView.TorrentWorldEntry;
import dev.nodera.diagnostics.view.TrackerStatusView;
import dev.nodera.diagnostics.view.TrackerStatusView.TrackerEndpointStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Task 31c: the <b>Nodera-only</b> multiplayer screen. Replaces vanilla's {@code JoinMultiplayerScreen}
 * (Direct Connect / Add Server / server list) entirely — the screen is a tabbed Nodera surface:
 *
 * <ul>
 *   <li><b>Worlds</b> — the torrent-world list ({@link TorrentWorldListWidget}) with search,
 *       including the player's own shared worlds; select a world to Join or View pieces (31d).</li>
 *   <li><b>Trackers</b> — live {@code nodera-tracker} endpoint status ({@link TrackerStatusView}).</li>
 *   <li><b>Rendezvous</b> — live {@code nodera-rendezvous} endpoint status
 *       ({@link RendezvousStatusView}).</li>
 * </ul>
 *
 * <p><b>Data-source seams.</b> Every feed is a pluggable supplier (defaults empty), fed by the live
 * wiring — the Task 28 {@code TrackerClient}, the Task 29 {@code RendezvousPeerTransport} metrics, and
 * (post-Task-32) the always-on companion daemon. This is the same "compile-clean now, live feed
 * deferred" pattern as the original {@code MultiplayerScreenAddon.worldSupplier}.
 *
 * <p>Thread-context: client render thread; suppliers must be safe to call from it.
 */
public final class NoderaMultiplayerScreen extends Screen {

    private enum Tab { WORLDS, TRACKERS, RENDEZVOUS }

    private static volatile Supplier<List<TorrentWorldEntry>> worldSupplier = List::of;
    private static volatile Supplier<List<TrackerEndpointStatus>> trackerSupplier = List::of;
    private static volatile Supplier<List<RendezvousEndpointStatus>> rendezvousSupplier = List::of;
    private static volatile Function<String, PieceMap> pieceMapSource =
            name -> PieceMapView.map(name, List.of(), 0);
    private static volatile java.util.function.Consumer<TorrentWorldEntry> joinHandler = w -> {};

    private final Screen parent;
    private Tab active = Tab.WORLDS;

    public NoderaMultiplayerScreen(Screen parent) {
        super(Component.translatable("nodera.multiplayer.title"));
        this.parent = parent;
    }

    /** Install the live torrent-world feed (own + friends' + recent worlds). */
    public static void setWorldSupplier(Supplier<List<TorrentWorldEntry>> s) {
        worldSupplier = s == null ? List::of : s;
    }

    /** Install the live tracker-endpoint status feed. */
    public static void setTrackerSupplier(Supplier<List<TrackerEndpointStatus>> s) {
        trackerSupplier = s == null ? List::of : s;
    }

    /** Install the live rendezvous-endpoint status feed. */
    public static void setRendezvousSupplier(Supplier<List<RendezvousEndpointStatus>> s) {
        rendezvousSupplier = s == null ? List::of : s;
    }

    /** Install the per-world piece-map source (world name → live {@link PieceMap}). */
    public static void setPieceMapSource(Function<String, PieceMap> s) {
        pieceMapSource = s == null ? (name -> PieceMapView.map(name, List.of(), 0)) : s;
    }

    /** Install the join handler (resolve route via tracker → dial via rendezvous/socket). */
    public static void setJoinHandler(java.util.function.Consumer<TorrentWorldEntry> h) {
        joinHandler = h == null ? w -> {} : h;
    }

    @Override
    protected void init() {
        // --- tab bar ---
        int tabW = 100;
        int tabY = 28;
        int tabX = this.width / 2 - (tabW * 3 + 8) / 2;
        addTabButton(tabX, tabY, tabW, "nodera.multiplayer.tab.worlds", Tab.WORLDS);
        addTabButton(tabX + tabW + 4, tabY, tabW, "nodera.multiplayer.tab.trackers", Tab.TRACKERS);
        addTabButton(tabX + (tabW + 4) * 2, tabY, tabW, "nodera.multiplayer.tab.rendezvous",
                Tab.RENDEZVOUS);

        int bodyTop = 56;
        int bodyH = this.height - bodyTop - 60;
        int margin = 16;
        int bodyW = this.width - 2 * margin;

        switch (active) {
            case WORLDS -> initWorldsTab(margin, bodyTop, bodyW, bodyH);
            case TRACKERS -> addRenderableWidget(new PanelWidget(margin, bodyTop, bodyW, bodyH,
                    Component.translatable(TrackerStatusView.TITLE),
                    () -> TrackerStatusView.panel(trackerSupplier.get())));
            case RENDEZVOUS -> addRenderableWidget(new PanelWidget(margin, bodyTop, bodyW, bodyH,
                    Component.translatable(RendezvousStatusView.TITLE),
                    () -> RendezvousStatusView.panel(rendezvousSupplier.get())));
        }

        // --- back ---
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20)
                .build());
    }

    private void initWorldsTab(int x, int y, int w, int h) {
        TorrentWorldListWidget list = new TorrentWorldListWidget(x, y + 24, w, h - 24);
        list.setEntries(worldSupplier.get());
        WorldSearchBox search = new WorldSearchBox(this.font, x, y, Math.min(240, w), 20,
                list::setSearch);
        addRenderableWidget(search);
        addRenderableWidget(list);

        int btnY = this.height - 52;
        addRenderableWidget(Button.builder(
                Component.translatable("nodera.multiplayer.join"), b -> {
                    TorrentWorldEntry sel = list.selected();
                    if (sel != null) {
                        joinHandler.accept(sel);
                    }
                }).bounds(this.width / 2 - 154, btnY, 150, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("nodera.multiplayer.view_pieces"), b -> {
                    TorrentWorldEntry sel = list.selected();
                    if (sel != null && this.minecraft != null) {
                        String name = sel.name();
                        this.minecraft.setScreen(new PieceMapScreen(this,
                                () -> pieceMapSource.apply(name)));
                    }
                }).bounds(this.width / 2 + 4, btnY, 150, 20).build());
    }

    private void addTabButton(int x, int y, int w, String key, Tab tab) {
        Button b = Button.builder(Component.translatable(key), btn -> {
            if (active != tab) {
                active = tab;
                rebuildWidgets();
            }
        }).bounds(x, y, w, 20).build();
        b.active = active != tab; // the current tab reads as selected (disabled)
        addRenderableWidget(b);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        Minecraft mc = this.minecraft == null ? Minecraft.getInstance() : this.minecraft;
        mc.setScreen(parent != null ? parent : new net.minecraft.client.gui.screens.TitleScreen());
    }
}
