package dev.nodera.mod.client;

import dev.nodera.mod.common.NoderaPeerService;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.ApiStatus;

/**
 * Client-only wiring (loaded only via {@link dev.nodera.mod.NoderaClientMod}, {@code Dist.CLIENT}).
 *
 * <p>The client joins the Nodera peer mesh when it receives the server's {@link
 * dev.nodera.mod.common.NoderaSessionPayload} (handled in {@code ModNetworking}); here we only
 * handle the other end of the lifecycle — leaving the mesh when the player disconnects from the
 * vanilla server, via {@link ClientPlayerNetworkEvent.LoggingOut}. (The peer mesh itself outlives
 * the vanilla connection while the player is in a session; this fires on an explicit disconnect.)
 *
 * <p>Thread context: {@code register} runs on the mod-loading thread on the client; the event
 * fires on the client thread. This class must only be reachable on {@code Dist.CLIENT}.
 */
@ApiStatus.Internal
public final class ClientBootstrap {

    private ClientBootstrap() {
    }

    /** Called from {@link dev.nodera.mod.NoderaClientMod} ({@code Dist.CLIENT} only). */
    public static void register(IEventBus modBus, ModContainer container) {
        NeoForge.EVENT_BUS.addListener(ClientBootstrap::onLoggingOut);
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        NoderaPeerService.get().stopClient();
    }
}
