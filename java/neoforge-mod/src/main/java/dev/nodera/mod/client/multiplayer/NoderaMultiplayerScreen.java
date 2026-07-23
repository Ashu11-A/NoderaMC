package dev.nodera.mod.client.multiplayer;

import dev.nodera.diagnostics.view.PieceMapView;
import dev.nodera.diagnostics.view.PieceMapView.PieceMap;
import dev.nodera.diagnostics.view.RendezvousStatusView;
import dev.nodera.diagnostics.view.RendezvousStatusView.RendezvousEndpointStatus;
import dev.nodera.diagnostics.view.TorrentWorldListView.TorrentWorldEntry;
import dev.nodera.diagnostics.view.TrackerStatusView;
import dev.nodera.diagnostics.view.TrackerStatusView.TrackerEndpointStatus;
import dev.nodera.mod.common.CompanionLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The <b>Nodera-only</b> multiplayer screen (Task 31c redesign) — the surface that replaces
 * vanilla's {@code JoinMultiplayerScreen} (and its Direct Connect / Add Server / Realms world)
 * entirely:
 *
 * <ul>
 *   <li><b>Worlds</b> — every world on the network: your own shared worlds (fed by the always-on
 *       worker, marked with you as owner and live joinability) merged with the tracker directory
 *       (other players' public worlds). Search, select, <b>Join</b> (the {@link NoderaJoinFlow}
 *       resolve-and-connect path), or open the piece map.</li>
 *   <li><b>Network</b> — the infrastructure view: live tracker + rendezvous endpoint status.</li>
 * </ul>
 *
 * <p>A persistent footer shows the node's health at a glance: worker link, tracker reachability,
 * rendezvous reachability.
 *
 * <p><b>Data-source seams.</b> Every feed is a pluggable supplier (defaults empty) so the screen
 * stays headless-testable; {@code ClientBootstrap} installs the live feeds at client setup.
 *
 * <p>Thread-context: client render thread; suppliers must be safe to call from it.
 */
public final class NoderaMultiplayerScreen extends Screen {

    private enum Tab { WORLDS, NETWORK }

    private static volatile Supplier<List<TorrentWorldEntry>> worldSupplier = List::of;
    private static volatile Supplier<List<TrackerEndpointStatus>> trackerSupplier = List::of;
    private static volatile Supplier<List<RendezvousEndpointStatus>> rendezvousSupplier = List::of;
    private static volatile Function<String, PieceMap> pieceMapSource =
            name -> PieceMapView.map(name, List.of(), 0);
    private static volatile BiConsumer<TorrentWorldEntry, Screen> joinHandler =
            NoderaJoinFlow::join;
    private static volatile Runnable refreshHandler = () -> {};

    private final Screen parent;
    private Tab active = Tab.WORLDS;

    private NoderaWorldList worldList;
    private Button joinButton;
    private Button piecesButton;
    private long lastFeedPullMs;

    public NoderaMultiplayerScreen(Screen parent) {
        super(Component.translatable("nodera.multiplayer.title"));
        this.parent = parent;
    }

    /** Install the live world feed (own worker-hosted + the tracker directory). */
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

    /** Install the join handler (defaults to the real {@link NoderaJoinFlow}). */
    public static void setJoinHandler(BiConsumer<TorrentWorldEntry, Screen> h) {
        joinHandler = h == null ? NoderaJoinFlow::join : h;
    }

    /** Install the Refresh-button action (defaults to a no-op until the live feed installs). */
    public static void setRefreshHandler(Runnable r) {
        refreshHandler = r == null ? () -> {} : r;
    }

    @Override
    protected void init() {
        int tabW = 110;
        int tabY = 32;
        int tabX = this.width / 2 - (tabW * 2 + 6) / 2;
        addTabButton(tabX, tabY, tabW, "nodera.multiplayer.tab.worlds", Tab.WORLDS);
        addTabButton(tabX + tabW + 6, tabY, tabW, "nodera.multiplayer.tab.network", Tab.NETWORK);

        int bodyTop = 58;
        int bodyBottom = this.height - 64;
        int margin = 12;
        int bodyW = this.width - 2 * margin;

        switch (active) {
            case WORLDS -> initWorldsTab(margin, bodyTop, bodyW, bodyBottom);
            case NETWORK -> initNetworkTab(margin, bodyTop, bodyW, bodyBottom - bodyTop);
        }

        // --- bottom action rows ---
        int row1 = this.height - 54;
        int row2 = this.height - 30;
        if (active == Tab.WORLDS) {
            joinButton = addRenderableWidget(Button.builder(
                    Component.translatable("nodera.multiplayer.join"), b -> {
                        TorrentWorldEntry sel = worldList == null ? null : worldList.selectedWorld();
                        if (sel != null) {
                            joinHandler.accept(sel, this);
                        }
                    }).bounds(this.width / 2 - 154, row1, 150, 20).build());
            piecesButton = addRenderableWidget(Button.builder(
                    Component.translatable("nodera.multiplayer.view_pieces"), b -> {
                        TorrentWorldEntry sel = worldList == null ? null : worldList.selectedWorld();
                        if (sel != null && this.minecraft != null) {
                            String name = sel.name();
                            this.minecraft.setScreen(new PieceMapScreen(this,
                                    () -> pieceMapSource.apply(name)));
                        }
                    }).bounds(this.width / 2 + 4, row1, 150, 20).build());
            joinButton.active = false;
            piecesButton.active = false;
            addRenderableWidget(Button.builder(
                    Component.translatable("nodera.multiplayer.refresh"), b -> refreshHandler.run())
                    .bounds(this.width / 2 - 154, row2, 150, 20).build());
            addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                    .bounds(this.width / 2 + 4, row2, 150, 20).build());
        } else {
            addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                    .bounds(this.width / 2 - 75, row2, 150, 20).build());
        }
    }

    private void initWorldsTab(int x, int y, int w, int bottom) {
        WorldSearchBox search = new WorldSearchBox(this.font, x, y, Math.min(260, w), 18,
                text -> {
                    if (worldList != null) {
                        worldList.setSearch(text);
                    }
                });
        addRenderableWidget(search);

        int listTop = y + 24;
        worldList = new NoderaWorldList(
                Minecraft.getInstance(), this.width, bottom - listTop, listTop);
        worldList.setEntries(worldSupplier.get());
        lastFeedPullMs = System.currentTimeMillis();
        addRenderableWidget(worldList);
    }

    private void initNetworkTab(int x, int y, int w, int h) {
        int half = (w - 8) / 2;
        addRenderableWidget(new PanelWidget(x, y, half, h,
                Component.translatable(TrackerStatusView.TITLE),
                () -> TrackerStatusView.panel(trackerSupplier.get())));
        addRenderableWidget(new PanelWidget(x + half + 8, y, half, h,
                Component.translatable(RendezvousStatusView.TITLE),
                () -> RendezvousStatusView.panel(rendezvousSupplier.get())));
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
    public void tick() {
        // Keep the list in step with the background feeds without rebuilding the whole screen.
        if (active == Tab.WORLDS && worldList != null
                && System.currentTimeMillis() - lastFeedPullMs > 1000) {
            worldList.setEntries(worldSupplier.get());
            lastFeedPullMs = System.currentTimeMillis();
        }
        if (joinButton != null) {
            boolean selected = worldList != null && worldList.selectedWorld() != null;
            joinButton.active = selected;
            piecesButton.active = selected;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        renderStatusFooter(graphics);
    }

    /** One-line node status: worker link · trackers reachable · rendezvous reachable. */
    private void renderStatusFooter(GuiGraphics graphics) {
        boolean worker = CompanionLink.isPresent();
        List<TrackerEndpointStatus> trackers = trackerSupplier.get();
        List<RendezvousEndpointStatus> rendezvous = rendezvousSupplier.get();
        long trackersUp = trackers.stream().filter(TrackerEndpointStatus::reachable).count();
        long rendezvousUp = rendezvous.stream().filter(RendezvousEndpointStatus::registered).count();

        Component status = Component.translatable("nodera.multiplayer.footer",
                Component.translatable(worker
                        ? "nodera.multiplayer.footer.worker.on"
                        : "nodera.multiplayer.footer.worker.off"),
                trackersUp + "/" + trackers.size(),
                rendezvousUp + "/" + rendezvous.size());
        graphics.drawCenteredString(this.font, status, this.width / 2, this.height - 66,
                worker && trackersUp > 0 ? 0x6FBF73 : 0xC9814E);
    }

    @Override
    public void onClose() {
        Minecraft mc = this.minecraft == null ? Minecraft.getInstance() : this.minecraft;
        mc.setScreen(parent != null ? parent : new net.minecraft.client.gui.screens.TitleScreen());
    }
}
