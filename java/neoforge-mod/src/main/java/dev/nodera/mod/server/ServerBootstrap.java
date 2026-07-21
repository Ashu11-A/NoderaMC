package dev.nodera.mod.server;

import dev.nodera.mod.common.NoderaConfig;
import dev.nodera.mod.common.NoderaHost;
import dev.nodera.mod.common.NoderaPeerService;
import dev.nodera.mod.common.NoderaSessionPayload;
import dev.nodera.mod.common.ShareOptions;
import dev.nodera.mod.debug.DiagnosticsService;
import dev.nodera.mod.debug.command.NoderaCommand;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.ApiStatus;

/**
 * Server-side wiring for the Nodera host peer + the in-game diagnostics HUD (Task 18). Registered on
 * <b>both</b> dists (Task 30): the <i>integrated</i> server of a player who presses "Share" is a host
 * exactly like a dedicated server — "the server is special in capacity and availability, not in
 * authority" (Plan Invariants 1–2). The dist no longer decides whether Nodera runs; the host role
 * does.
 *
 * <p>On {@link ServerStartedEvent} it starts the host {@link dev.nodera.peer.PeerRuntime} <i>only</i>
 * for a dedicated server configured to auto-share (a plain always-on {@code FULL_ARCHIVE} seeder); a
 * private singleplayer/LAN world is <b>never</b> auto-broadcast — it goes on the network only when
 * the player uses the pause-menu {@code Share} action (Task 30b), which calls
 * {@link NoderaHost#activate}. On {@link PlayerEvent.PlayerLoggedInEvent} it hands each joining
 * player the P2P host route (when hosting); on {@link ServerTickEvent.Post} it samples the HUD; on
 * {@link PlayerTickEvent.Post} it fires zone-edge alerts; on {@link ServerStoppingEvent} it tears
 * everything down. Command registration delegates to {@link NoderaCommand}.
 *
 * <p>Thread context: {@code register} runs on the mod-loading thread; the subscribed events fire on
 * the server main thread.
 */
@ApiStatus.Internal
public final class ServerBootstrap {

    private ServerBootstrap() {
    }

    /** Called from {@link dev.nodera.mod.NoderaMod} on every dist (Task 30). */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(ServerBootstrap::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerBootstrap::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerBootstrap::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(ServerBootstrap::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(ServerBootstrap::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(ServerBootstrap::onPlayerTickPost);
        NeoForge.EVENT_BUS.addListener(ServerBootstrap::onRegisterCommands);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        // A dedicated server may auto-host (an always-on FULL_ARCHIVE peer). An integrated server
        // never auto-broadcasts a private world — it waits for the pause-menu "Share" action so
        // singleplayer stays private by default (Task 30a).
        if (server.isDedicatedServer() && NoderaConfig.HOST_AUTO_SHARE.get()) {
            NoderaHost.activate(server, ShareOptions.dedicatedDefault());
            return;
        }
        // Task 33: a world previously "Opened to Nodera" auto-re-shares on load, so the original host
        // always restores its shared status when returning to the world (no need to press Share again).
        java.nio.file.Path saveRoot = server.getWorldPath(
                net.minecraft.world.level.storage.LevelResource.ROOT);
        if (dev.nodera.mod.common.NoderaWorldStore.isShared(saveRoot)) {
            var id = dev.nodera.mod.common.NoderaWorldStore.read(saveRoot);
            ShareOptions restored = id.map(w -> new ShareOptions(
                            "", true, w.listedOnTracker(), 5))
                    .orElse(ShareOptions.playerDefault());
            NoderaHost.activate(server, restored);
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        DiagnosticsService d = NoderaPeerService.get().serverDiagnostics();
        if (d != null) {
            d.onServerStopping();
        }
        NoderaPeerService.get().stopHosting();
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        String route = NoderaPeerService.get().hostRoute();
        if (route != null && event.getEntity() instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(player, new NoderaSessionPayload(route));
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        DiagnosticsService d = NoderaPeerService.get().serverDiagnostics();
        if (d != null) {
            d.onPlayerLoggedOut(event.getEntity());
        }
    }

    private static void onServerTickPost(ServerTickEvent.Post event) {
        DiagnosticsService d = NoderaPeerService.get().serverDiagnostics();
        if (d != null) {
            d.onServerTickPost(event);
        }
    }

    private static void onPlayerTickPost(PlayerTickEvent.Post event) {
        DiagnosticsService d = NoderaPeerService.get().serverDiagnostics();
        if (d != null && event.getEntity() instanceof ServerPlayer player) {
            d.onPlayerTickPost(player);
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        NoderaCommand.register(event.getDispatcher(), () -> NoderaPeerService.get().serverDiagnostics());
    }
}
