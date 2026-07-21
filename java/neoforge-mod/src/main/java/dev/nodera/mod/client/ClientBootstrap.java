package dev.nodera.mod.client;

import dev.nodera.mod.client.multiplayer.MultiplayerScreenAddon;
import dev.nodera.mod.client.share.PauseScreenShareAddon;
import dev.nodera.mod.client.worldlist.SelectWorldScreenAddon;
import dev.nodera.mod.common.CompanionClient;
import dev.nodera.mod.common.CompanionGate;
import dev.nodera.mod.common.NoderaConfig;
import dev.nodera.mod.common.NoderaPeerService;
import dev.nodera.mod.debug.command.NoderaClientCommand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger("NoderaCompanion");

    private ClientBootstrap() {
    }

    /** Called from {@link dev.nodera.mod.NoderaClientMod} ({@code Dist.CLIENT} only). */
    public static void register(IEventBus modBus, ModContainer container) {
        modBus.addListener(ClientBootstrap::onClientSetup);  // Task 32: companion presence gate
        NeoForge.EVENT_BUS.addListener(ClientBootstrap::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(ClientBootstrap::onRegisterClientCommands);
        NeoForge.EVENT_BUS.addListener(MultiplayerScreenAddon::onScreenInit);
        NeoForge.EVENT_BUS.addListener(PauseScreenShareAddon::onScreenInit);  // Task 31a: pause-menu "Open to Nodera"
        NeoForge.EVENT_BUS.addListener(SelectWorldScreenAddon::onScreenRender);  // Task 31b: public-world badge
    }

    /**
     * Task 32 presence gate: probe the local Nodera companion daemon. When enforced
     * ({@code companion.required}), a missing/incompatible daemon aborts startup with an actionable
     * error (NeoForge surfaces the thrown exception as a mod-loading error). Enforcement defaults to
     * off until the companion app ships — until then this only logs, so a working install is not
     * bricked. Config reads are safe here (config is loaded by setup time).
     */
    private static void onClientSetup(FMLClientSetupEvent event) {
        // Task 31/33 fix: feed the multiplayer Trackers/Rendezvous tabs from the configured endpoints
        // (they were never wired, so they always said "No … configured").
        dev.nodera.mod.client.multiplayer.MultiplayerStatusFeed.start();
        dev.nodera.mod.client.multiplayer.NoderaMultiplayerScreen.setTrackerSupplier(
                dev.nodera.mod.client.multiplayer.MultiplayerStatusFeed::trackers);
        dev.nodera.mod.client.multiplayer.NoderaMultiplayerScreen.setRendezvousSupplier(
                dev.nodera.mod.client.multiplayer.MultiplayerStatusFeed::rendezvous);

        String endpoint = NoderaConfig.COMPANION_CONTROL_ENDPOINT.get();
        boolean required = NoderaConfig.COMPANION_REQUIRED.get();
        CompanionClient client;
        try {
            client = CompanionClient.parse(endpoint);
        } catch (IllegalArgumentException e) {
            LOG.warn("Nodera companion endpoint '{}' is malformed: {}", endpoint, e.getMessage());
            if (required) {
                throw new dev.nodera.mod.common.CompanionUnavailableException(
                        "Nodera companion endpoint '" + endpoint + "' is malformed: " + e.getMessage());
            }
            return;
        }
        if (required) {
            CompanionGate.GateResult result = CompanionGate.requireRunning(client); // throws if absent
            LOG.info("Nodera companion gate: {}", result.message());
            linkWorker(client);
        } else {
            CompanionGate.GateResult result = CompanionGate.evaluate(client);
            if (result.ok()) {
                LOG.info("Nodera companion gate: {}", result.message());
                linkWorker(client);
            } else {
                LOG.warn("Nodera companion gate (not enforced): {}", result.message());
            }
        }
    }

    /** Record the verified worker so the rest of the mod talks to the always-on node through it. */
    private static void linkWorker(CompanionClient client) {
        client.probe().ifPresent(info -> {
            dev.nodera.mod.common.CompanionLink.set(client, info);
            LOG.info("Nodera worker linked: protocol {}, version {}",
                    info.protocolVersion(), info.daemonVersion());
        });
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        NoderaPeerService.get().stopClient();
    }

    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        NoderaClientCommand.register(event.getDispatcher());
    }
}
