package dev.nodera.mod.dedicated;

import dev.nodera.mod.common.NoderaConfig;
import dev.nodera.mod.common.NoderaPeerService;
import dev.nodera.mod.common.NoderaSessionPayload;
import dev.nodera.mod.debug.DiagnosticsService;
import dev.nodera.mod.debug.command.NoderaCommand;
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
 * Dedicated-server wiring for the Nodera bootstrap peer + the in-game diagnostics HUD (Task 18).
 *
 * <p>On {@link ServerStartedEvent} it starts the bootstrap {@code PeerRuntime} (the "server acting
 * as a peer") and its {@link DiagnosticsService}; on {@link PlayerEvent.PlayerLoggedInEvent} it
 * hands each joining player the P2P bootstrap route; on {@link ServerTickEvent.Post} it samples the
 * HUD and renders tab/boss-bar surfaces; on {@link PlayerTickEvent.Post} it fires zone-edge alerts;
 * on {@link ServerStoppingEvent} it tears everything down. Command registration delegates to
 * {@link NoderaCommand} (the redesigned declarative {@code /nodera} tree; {@code status}/{@code peers}
 * remain as aliases).
 *
 * <p>Thread context: {@code register} runs on the mod-loading thread; the subscribed events fire on
 * the server main thread.
 */
@ApiStatus.Internal
public final class ServerBootstrap {

    private ServerBootstrap() {
    }

    /** Called from {@link dev.nodera.mod.NoderaMod} only when {@code dist == DEDICATED_SERVER}. */
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
        NoderaPeerService.get().startBootstrap(
                NoderaConfig.P2P_BIND_HOST.get(),
                NoderaConfig.P2P_PORT.get(),
                NoderaConfig.P2P_ADVERTISE_HOST.get());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        DiagnosticsService d = NoderaPeerService.get().serverDiagnostics();
        if (d != null) {
            d.onServerStopping();
        }
        NoderaPeerService.get().stopServer();
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        String route = NoderaPeerService.get().bootstrapRoute();
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
