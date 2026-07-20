package dev.nodera.mod.client;

import dev.nodera.mod.client.multiplayer.MultiplayerScreenAddon;
import dev.nodera.mod.client.share.PauseScreenShareAddon;
import dev.nodera.mod.common.NoderaPeerService;
import dev.nodera.mod.debug.command.NoderaClientCommand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.ApiStatus;

/**
 * Client-only wiring (loaded only via {@link dev.nodera.mod.NoderaClientMod}, {@code Dist.CLIENT}).
 *
 * <p>The client joins the Nodera peer mesh when it receives the server's {@link
 * dev.nodera.mod.common.NoderaSessionPayload} (handled in {@code ModNetworking}); here we register
 * the {@code /noderac} command tree (Task 18) and handle the other end of the lifecycle — leaving
 * the mesh when the player disconnects from the vanilla server, via
 * {@link ClientPlayerNetworkEvent.LoggingOut}. (The peer mesh itself outlives the vanilla connection
 * while the player is in a session; this fires on an explicit disconnect.)
 *
 * <p>Thread context: {@code register} runs on the mod-loading thread on the client; the events fire
 * on the client thread. This class must only be reachable on {@code Dist.CLIENT}.
 */
@ApiStatus.Internal
public final class ClientBootstrap {

    private ClientBootstrap() {
    }

    /** Called from {@link dev.nodera.mod.NoderaClientMod} ({@code Dist.CLIENT} only). */
    public static void register(IEventBus modBus, ModContainer container) {
        NeoForge.EVENT_BUS.addListener(ClientBootstrap::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(ClientBootstrap::onRegisterClientCommands);
        NeoForge.EVENT_BUS.addListener(MultiplayerScreenAddon::onScreenInit);
        NeoForge.EVENT_BUS.addListener(PauseScreenShareAddon::onScreenInit);  // Task 30b: pause-menu "Share"
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        NoderaPeerService.get().stopClient();
    }

    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        NoderaClientCommand.register(event.getDispatcher());
    }
}
