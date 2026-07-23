package dev.nodera.mod.client.multiplayer;

import dev.nodera.core.Bytes;
import dev.nodera.diagnostics.view.TorrentWorldListView.TorrentWorldEntry;
import dev.nodera.protocol.discovery.TrackerRoutesResponse;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * The join flow (Task 5d, the L-43 exit path): world row → a live Minecraft connection.
 *
 * <ol>
 *   <li>If the row already knows the host's game endpoint (your own worker-hosted world, whose
 *       {@code mc_route} rides the worker STATE), connect straight to it.</li>
 *   <li>Otherwise resolve through the tracker: {@code TrackerRoutesQuery} (tag 49) returns every
 *       live peer's full claimed dial routes; the {@code "mc/host:port"} claim is the host's open
 *       game server. No claim ⇒ the host's game is closed — the world is archived on the network
 *       but not currently playable (until the P2P content plane joins worlds hostlessly).</li>
 *   <li>Connect with vanilla's own {@link ConnectScreen} machinery; the Nodera session payload
 *       then meshes the client automatically on login ({@code ModNetworking}).</li>
 * </ol>
 *
 * <p>Resolution runs off-thread (tracker sockets); the connect hop returns to the render thread.
 *
 * <p>Thread-context: {@link #join} from the render thread only.
 */
public final class NoderaJoinFlow {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaJoin");

    /** The announce route-claim prefix marking a Minecraft game endpoint. */
    public static final String MC_ROUTE_PREFIX = "mc/";

    private NoderaJoinFlow() {
    }

    /** Resolve + connect to a world from the multiplayer list. Never blocks the render thread. */
    public static void join(TorrentWorldEntry entry, Screen parent) {
        if (entry == null) {
            return;
        }
        if (!entry.mcRoute().isBlank()) {
            NoderaContinuity.onJoining(entry.worldIdHex(), entry.name());
            connect(parent, entry.name(), entry.mcRoute());
            return;
        }
        if (entry.worldIdHex().isBlank()) {
            fail(parent, entry.name(), Component.translatable("nodera.join.error.no_id"));
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new JoinResolvingScreen(parent, entry.name()));
        Thread.ofPlatform().name("nodera-join-resolve").daemon().start(() -> {
            Optional<String> route = resolveGameRoute(entry.worldIdHex());
            mc.execute(() -> {
                if (!(mc.screen instanceof JoinResolvingScreen)) {
                    return; // the player navigated away while we resolved
                }
                if (route.isPresent()) {
                    NoderaContinuity.onJoining(entry.worldIdHex(), entry.name());
                    connect(parent, entry.name(), route.get());
                } else if (!NoderaContinuity.openFromNetwork(entry.worldIdHex(), entry.name())) {
                    // No live game endpoint AND no worker to materialize from the network.
                    fail(parent, entry.name(),
                            Component.translatable("nodera.join.error.host_offline"));
                }
            });
        });
    }

    /** Tracker resolution: the world's first live {@code mc/} route claim. Blocking; off-thread. */
    private static Optional<String> resolveGameRoute(String worldIdHex) {
        try {
            TrackerRoutesResponse routes = MultiplayerWorldFeed.sharedTracker()
                    .routes(Bytes.fromHex(worldIdHex));
            return routes.firstRouteWithPrefix(MC_ROUTE_PREFIX);
        } catch (RuntimeException e) {
            LOG.warn("Nodera join: route resolution failed for {}: {}", worldIdHex, e.toString());
            return Optional.empty();
        }
    }

    private static void connect(Screen parent, String worldName, String hostPort) {
        if (!ServerAddress.isValidAddress(hostPort)) {
            fail(parent, worldName, Component.translatable("nodera.join.error.bad_route", hostPort));
            return;
        }
        LOG.info("Nodera join: connecting to '{}' at {}", worldName, hostPort);
        Minecraft mc = Minecraft.getInstance();
        ServerData data = new ServerData(worldName, hostPort, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(hostPort), data,
                false, null);
    }

    private static void fail(Screen parent, String worldName, Component reason) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new JoinFailedScreen(parent, worldName, reason)));
    }

    /** Interstitial "Locating host…" screen shown while the tracker resolves the game route. */
    static final class JoinResolvingScreen extends Screen {
        private final Screen parent;
        private final String worldName;

        JoinResolvingScreen(Screen parent, String worldName) {
            super(Component.translatable("nodera.join.resolving.title"));
            this.parent = parent;
            this.worldName = worldName;
        }

        @Override
        protected void init() {
            addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
                            net.minecraft.network.chat.CommonComponents.GUI_CANCEL, b -> onClose())
                    .bounds(this.width / 2 - 100, this.height / 2 + 40, 200, 20).build());
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY,
                           float partialTick) {
            super.render(graphics, mouseX, mouseY, partialTick);
            graphics.drawCenteredString(this.font, this.title, this.width / 2,
                    this.height / 2 - 24, 0xFFFFFF);
            graphics.drawCenteredString(this.font,
                    Component.translatable("nodera.join.resolving.detail", worldName),
                    this.width / 2, this.height / 2 - 8, 0xA0A0A0);
        }

        @Override
        public void onClose() {
            if (this.minecraft != null) {
                this.minecraft.setScreen(parent);
            }
        }
    }

    /** Terminal join-failure screen with the actionable reason. */
    static final class JoinFailedScreen extends Screen {
        private final Screen parent;
        private final String worldName;
        private final Component reason;

        JoinFailedScreen(Screen parent, String worldName, Component reason) {
            super(Component.translatable("nodera.join.failed.title"));
            this.parent = parent;
            this.worldName = worldName;
            this.reason = reason;
        }

        @Override
        protected void init() {
            addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
                            net.minecraft.network.chat.CommonComponents.GUI_BACK, b -> onClose())
                    .bounds(this.width / 2 - 100, this.height / 2 + 40, 200, 20).build());
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY,
                           float partialTick) {
            super.render(graphics, mouseX, mouseY, partialTick);
            graphics.drawCenteredString(this.font, this.title, this.width / 2,
                    this.height / 2 - 36, 0xFF6666);
            graphics.drawCenteredString(this.font, worldName, this.width / 2,
                    this.height / 2 - 20, 0xFFFFFF);
            graphics.drawCenteredString(this.font, reason, this.width / 2,
                    this.height / 2 - 4, 0xA0A0A0);
        }

        @Override
        public void onClose() {
            if (this.minecraft != null) {
                this.minecraft.setScreen(parent);
            }
        }
    }
}
