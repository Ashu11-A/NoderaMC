package dev.nodera.mod.client;

import dev.nodera.peer.control.CompanionLink;
import dev.nodera.peer.control.CompanionUnavailableException;
import dev.nodera.mod.client.multiplayer.MultiplayerScreenAddon;
import dev.nodera.mod.client.share.PauseScreenShareAddon;
import dev.nodera.mod.client.worldlist.SelectWorldScreenAddon;
import dev.nodera.peer.control.CompanionClient;
import dev.nodera.peer.control.CompanionGate;
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
        NeoForge.EVENT_BUS.addListener(ClientBootstrap::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(ClientBootstrap::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(ClientBootstrap::onRegisterClientCommands);
        // Diagnostic only, and inert unless -Dnodera.spark.profile=<seconds> is set: lets a
        // player-hosted world be profiled, which RCON cannot reach (docs/minecraft/spark/).
        dev.nodera.mod.debug.SparkProfileBridge.register(NeoForge.EVENT_BUS);
        NeoForge.EVENT_BUS.addListener(MultiplayerScreenAddon::onScreenInit);
        // Nothing is allowed to put a screen in front of a player whose world is being re-opened
        // underneath them. The disconnect itself is caught earlier (ClientDisconnectMixin, at the
        // head of onDisconnect, before vanilla tears the level down); this is the second half —
        // the world-open flow's own progress screens, suppressed for the length of the takeover
        // and left completely alone outside one.
        NeoForge.EVENT_BUS.addListener(ClientBootstrap::onScreenOpening);
        dev.nodera.mod.common.ModNetworking.setSessionWorldListener(
                dev.nodera.mod.client.multiplayer.NoderaContinuity::onJoining);
        // L-52: answer a host's live-join password challenge from the passwords this player typed,
        // and offer the prompt on the disconnect screen when a join is refused for lack of one.
        dev.nodera.mod.common.ModNetworking.setJoinProofProvider(
                dev.nodera.endpoint.client.ClientJoinPasswords::answer);
        NeoForge.EVENT_BUS.addListener(
                dev.nodera.mod.client.multiplayer.JoinPasswordScreen::onScreenInit);
        // No-host ownership: every plan broadcast re-derives this player's own region set and
        // starts/refreshes the client-side validation lane (re-execute + vote over the mesh).
        dev.nodera.mod.common.ModNetworking.setPlanListener(
                dev.nodera.mod.client.entity.ClientValidationLane::apply);
        NeoForge.EVENT_BUS.addListener(PauseScreenShareAddon::onScreenInit);  // Task 31a: pause-menu "Open to Nodera"
        NeoForge.EVENT_BUS.addListener(
                dev.nodera.mod.client.share.SaveScreenSeedOverlay::onScreenRender); // #43 exit-flush progress
        NeoForge.EVENT_BUS.addListener(SelectWorldScreenAddon::onScreenRender);  // Task 31b: public-world badge
        NeoForge.EVENT_BUS.addListener(
                dev.nodera.mod.client.title.TitleScreenAddon::onScreenInit);   // Realms slot → Nodera Network
        NeoForge.EVENT_BUS.addListener(
                dev.nodera.mod.client.create.CreateWorldNoderaAddon::onScreenInit); // create-world share options
        // Says WHICH screen a client is stuck on when it never reaches a world — the fact the
        // scripted live suites (L-45) have been missing every time they fail in CI.
        ClientStallReporter.register();
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
        dev.nodera.endpoint.client.MultiplayerStatusFeed.start();
        dev.nodera.mod.client.multiplayer.NoderaMultiplayerScreen.setTrackerSupplier(
                dev.nodera.endpoint.client.MultiplayerStatusFeed::trackers);
        dev.nodera.mod.client.multiplayer.NoderaMultiplayerScreen.setRendezvousSupplier(
                dev.nodera.endpoint.client.MultiplayerStatusFeed::rendezvous);
        // Worlds tab: the union of this install's worker-hosted worlds (owner = the local player,
        // live joinability) and the tracker directory (other players' public worlds). The Refresh
        // button re-pulls both on demand; Join rides the default NoderaJoinFlow handler.
        dev.nodera.mod.client.multiplayer.MultiplayerWorldFeed.start();
        dev.nodera.mod.client.multiplayer.NoderaMultiplayerScreen.setWorldSupplier(
                dev.nodera.mod.client.multiplayer.MultiplayerWorldFeed::snapshot);
        dev.nodera.mod.client.multiplayer.NoderaMultiplayerScreen.setRefreshHandler(
                dev.nodera.mod.client.multiplayer.MultiplayerWorldFeed::requestRefresh);
        // Piece map: the last feed with a seam but no source. "View pieces" opened an
        // unconditionally empty grid because setPieceMapSource was never called; this installs the
        // worker-backed feed (NODERA-PIECES) that fills it.
        dev.nodera.mod.client.multiplayer.PieceMapFeed.start();
        // L-46: the single-player world-list badge was the last feed defaulting empty — feed it
        // from the same worker-backed world feed (shared summary; per-row placement is the
        // WorldSelectionListEntryMixin GUI-pass work).
        NeoForge.EVENT_BUS.addListener(
                dev.nodera.mod.client.worldlist.SelectWorldScreenAddon::onScreenInit);
        dev.nodera.mod.client.worldlist.SelectWorldScreenAddon.setStatusSupplier(
                dev.nodera.mod.client.multiplayer.MultiplayerWorldFeed::ownWorldStatuses);

        String endpoint = NoderaConfig.resolveControlEndpoint(
                NoderaConfig.COMPANION_CONTROL_ENDPOINT.get());
        boolean required = NoderaConfig.COMPANION_REQUIRED.get();
        CompanionClient client;
        try {
            client = CompanionClient.parse(endpoint);
        } catch (IllegalArgumentException e) {
            LOG.warn("Nodera companion endpoint '{}' is malformed: {}", endpoint, e.getMessage());
            if (required) {
                throw new dev.nodera.peer.control.CompanionUnavailableException(
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
        // Bind the telemetry façade to whatever worker the link holds. Registration is idempotent
        // and happens before the link is recorded, so the first link is not missed.
        dev.nodera.endpoint.telemetry.ModTelemetry.followCompanionLink();
        client.probe().ifPresent(info -> {
            dev.nodera.peer.control.CompanionLink.set(client, info);
            LOG.info("Nodera worker linked: protocol {}, version {}",
                    info.protocolVersion(), info.daemonVersion());
        });
    }

    /**
     * The local player now exists — the signal {@code NoderaHost.tickGamePublish} waits for
     * before opening the integrated server (vanilla's publish path dereferences
     * {@code minecraft.player}). Without this, a world shared before login — every
     * auto-re-share — stays listed but never joinable.
     */
    private static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        dev.nodera.mod.common.NoderaHost.setClientPlayerReady(true);
        // Being here means the gate, if there was one, let us through. Clearing the marker is what
        // keeps a later, genuine host loss from being mistaken for a password refusal.
        dev.nodera.endpoint.client.ClientJoinPasswords.passedGate();
        // The takeover ends HERE, not when openWorld returns. `WorldOpenFlows.openWorld` chains
        // through `thenAcceptAsync(..., minecraft)`, so it returns while `doWorldLoad` is still
        // queued — clearing the flag around that call cleared it before the load had shown a single
        // screen. This event fires inside `handleLogin`, with the level built and the player in it.
        dev.nodera.mod.client.multiplayer.SeamlessTakeover.finish();
    }

    /**
     * Suppress the DISCONNECT screen while a seamless takeover is running.
     *
     * <p>Cancelling {@code Opening} means the screen is never set, so whatever the player was
     * looking at stays — and for the length of a takeover that is the world they are standing in.
     * Outside a takeover this handler does nothing: a mod that hides a disconnect screen
     * unconditionally is a mod that hides why somebody was kicked.
     */
    private static void onScreenOpening(net.neoforged.neoforge.client.event.ScreenEvent.Opening event) {
        if (dev.nodera.mod.client.multiplayer.SeamlessTakeover.shouldSuppress(event.getNewScreen())) {
            event.setCanceled(true);
        }
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        dev.nodera.mod.common.NoderaHost.setClientPlayerReady(false);
        dev.nodera.mod.client.entity.ClientValidationLane.stop();
        NoderaPeerService.get().stopClient();
    }

    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        NoderaClientCommand.register(event.getDispatcher());
    }
}
