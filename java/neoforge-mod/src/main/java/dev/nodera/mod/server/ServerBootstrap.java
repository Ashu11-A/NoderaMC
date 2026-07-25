package dev.nodera.mod.server;

import dev.nodera.mod.common.NoderaConfig;
import dev.nodera.mod.common.NoderaHost;
import dev.nodera.mod.common.NoderaPeerService;
import dev.nodera.mod.common.NoderaSessionPayload;
import dev.nodera.mod.common.ShareOptions;
import dev.nodera.mod.debug.DiagnosticsService;
import dev.nodera.mod.debug.command.NoderaCommand;
import dev.nodera.mod.server.entity.EntityCaptureBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
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
        EntityCaptureBridge.get().register();
        NeoForge.EVENT_BUS.addListener(ServerBootstrap::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerBootstrap::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerBootstrap::onServerStopped);
        NeoForge.EVENT_BUS.addListener(ServerBootstrap::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(ServerBootstrap::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(ServerBootstrap::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(ServerBootstrap::onPlayerTickPost);
        NeoForge.EVENT_BUS.addListener(ServerBootstrap::onRegisterCommands);
    }

    /**
     * The final firewall before the NeoForge {@code EventBus} (issue #39): wrap every
     * {@link NoderaHost#activate} call from {@link #onServerStarted} so a host-activation failure —
     * a P2P bind collision, an entity-lane bootstrap fault, anything — can NEVER reach
     * {@code MinecraftServer.runServer} and crash the integrated server. NeoForge's bus rethrows
     * listener exceptions rather than isolating them, so this catch is the only reliable lever. The
     * world continues in vanilla mode; the operator can retry Share.
     */
    private static void safeActivate(MinecraftServer server, dev.nodera.mod.common.ShareOptions options) {
        try {
            NoderaHost.activate(server, options);
        } catch (RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger("NoderaHost").error(
                    "Nodera: share failed for '{}' — server continues in vanilla mode: {}",
                    server.getWorldData().getLevelName(), e.toString());
        }
    }

    private static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        // Task 32's server half: a dedicated server links (and, when `companion.required`,
        // demands) the always-on worker exactly like the client gate does — identity minting,
        // archive seeding, and host delegation all ride this link. The integrated server skips
        // this: its client dist already ran the gate at startup.
        linkServerWorker(server);
        // A dedicated server may auto-host (an always-on FULL_ARCHIVE peer). An integrated server
        // never auto-broadcasts a private world — it waits for the pause-menu "Share" action so
        // singleplayer stays private by default (Task 30a).
        if (server.isDedicatedServer() && NoderaConfig.HOST_AUTO_SHARE.get()) {
            safeActivate(server, ShareOptions.dedicatedDefault());
            return;
        }
        // Task 5d create pipeline: a world created with "Nodera: Shared" goes on the network the
        // moment it first starts — the same NoderaHost.activate path as the pause-menu Share. The
        // brand-new-world guard (game time 0) keeps a stale parked choice from ever sharing a
        // pre-existing world.
        var pendingShare = dev.nodera.mod.common.PendingCreateShare.consume();
        if (pendingShare.isPresent() && server.overworld() != null
                && server.overworld().getGameTime() == 0L) {
            safeActivate(server, pendingShare.get());
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
            safeActivate(server, restored);
        }
    }

    private static void linkServerWorker(MinecraftServer server) {
        if (!server.isDedicatedServer() || dev.nodera.mod.common.CompanionLink.isPresent()) {
            return;
        }
        String endpoint = NoderaConfig.SERVER_COMPANION_CONTROL_ENDPOINT.get();
        boolean required = NoderaConfig.SERVER_COMPANION_REQUIRED.get();
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("NoderaCompanion");
        try {
            dev.nodera.mod.common.CompanionClient client =
                    dev.nodera.mod.common.CompanionClient.parse(endpoint);
            dev.nodera.mod.common.CompanionGate.GateResult result = required
                    ? dev.nodera.mod.common.CompanionGate.requireRunning(client) // throws if absent
                    : dev.nodera.mod.common.CompanionGate.evaluate(client);
            if (result.ok()) {
                client.probe().ifPresent(info ->
                        dev.nodera.mod.common.CompanionLink.set(client, info));
                log.info("Nodera companion gate (server): {}", result.message());
            } else {
                log.warn("Nodera companion gate (server, not enforced): {}", result.message());
            }
        } catch (IllegalArgumentException e) {
            if (required) {
                throw new dev.nodera.mod.common.CompanionUnavailableException(
                        "Nodera companion endpoint '" + endpoint + "' is malformed: " + e.getMessage());
            }
            log.warn("Nodera companion endpoint '{}' is malformed: {}", endpoint, e.getMessage());
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        DiagnosticsService d = NoderaPeerService.get().serverDiagnostics();
        if (d != null) {
            d.onServerStopping();
        }
        NoderaHost.onServerStopping(event.getServer());
        dev.nodera.mod.common.PlayerNodeRegistry.clear();
        OperatorBridge.get().reset();
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        // Continuity final flush: the save is fully written and quiescent here, so this archive is
        // the session's last word — seeded to the worker, it outlives the game (and the machine).
        java.nio.file.Path saveRoot = event.getServer().getWorldPath(
                net.minecraft.world.level.storage.LevelResource.ROOT);
        dev.nodera.mod.common.WorldArchiver.seedNow(saveRoot);
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        String route = NoderaPeerService.get().hostRoute();
        if (route != null && event.getEntity() instanceof ServerPlayer player) {
            // The world identity rides the session payload so every joiner — whatever path it
            // used to connect — can arm the continuity lane for exactly this world.
            MinecraftServer server = player.serverLevel().getServer();
            String worldIdHex = dev.nodera.mod.common.NoderaWorldStore
                    .read(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT))
                    .map(id -> id.worldId().toHex()).orElse("");
            // Issue #36 F1: a fresh single-use announce challenge rides the session payload; the
            // client must sign it (bound to its key + MC UUID) for its node announce to be trusted.
            String challengeB64 = java.util.Base64.getEncoder().encodeToString(
                    dev.nodera.mod.common.ModNetworking.announceChallenges()
                            .issue(player.getUUID(), System.currentTimeMillis()).toArray());
            PacketDistributor.sendToPlayer(player, new NoderaSessionPayload(
                    route, worldIdHex, server.getWorldData().getLevelName(), challengeB64));
            // Keep the worker's live player count fresh (multiplayer-list rows, Task 33).
            NoderaHost.refreshWorkerPresence(server);
            // Issue #36 F3: op the world author (integrated owner) on login — covers an auto-re-share
            // that ran before the owner joined. Everyone else's op flows from a key-checked grant.
            NoderaHost.syncAuthorOnLogin(server, player);
        }
        // Task 12 live lane: the FOV ownership plan needs a player to anchor it, so a world shared
        // before anyone was online (a dedicated auto-share boot) activates on first login instead.
        if (route != null && NoderaConfig.ENTITY_LANE_AUTO.get() && !NoderaHost.entityLaneActive()
                && event.getEntity() instanceof ServerPlayer player) {
            try {
                NoderaHost.activateEntityLaneFromWorld(player.serverLevel().getServer());
            } catch (RuntimeException | LinkageError e) {
                // A lane bootstrap failure (including a missing optional library) must never take
                // the server down with the player mid-login.
                org.slf4j.LoggerFactory.getLogger("NoderaHost").warn(
                        "Nodera: entity lane bootstrap on login failed: {}", e.toString());
            }
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        DiagnosticsService d = NoderaPeerService.get().serverDiagnostics();
        if (d != null) {
            d.onPlayerLoggedOut(event.getEntity());
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            MinecraftServer server = player.serverLevel().getServer();
            NoderaHost.refreshWorkerPresence(server);
            // Drop any op the bridge granted this player so it never lingers past their session.
            OperatorBridge.get().onLogout(server, player);
            // No-host ownership: a departed player's node leaves the plan; the survivors re-plan
            // and absorb its regions (the FOV planner reassigns deterministically).
            dev.nodera.mod.common.PlayerNodeRegistry.forget(player.getUUID());
            dev.nodera.mod.common.ModNetworking.announceChallenges().forget(player.getUUID());
            NoderaHost.replanEntityLane(server);
        }
    }

    private static void onServerTickPost(ServerTickEvent.Post event) {
        // Region enter/leave evidence log + the scripted ownership drive (docs/minecraft/TESTING.md).
        dev.nodera.mod.server.entity.RegionDriveDebug.onServerTick(event.getServer());
        // FOV ownership follows the player: re-plan when someone crosses a region boundary, so the
        // regions a player owns track their view instead of staying frozen at their join position.
        // Self-catching on purpose — NeoForge's EventBus rethrows listener exceptions straight into
        // the tick loop, so an ownership hiccup must never take the integrated server down.
        try {
            NoderaHost.tickOwnership(event.getServer());
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger("NoderaHost")
                    .warn("Nodera: ownership tick failed: {}", e.toString());
        }
        // Complete a parked integrated-server publish once the host player is fully in the world
        // (a world shared before login — every auto-re-share — parks it). Cheap flag check when idle.
        NoderaHost.tickGamePublish(event.getServer());
        // Issue #43 continuous streaming: while hosting, re-seed the world archive on the
        // configured cadence so the network copy is never more than one interval behind — a
        // crash/exit can no longer revert the world past the last interval.
        if (NoderaPeerService.get().isHosting()) {
            try {
                dev.nodera.mod.common.WorldArchiver.streamTick(event.getServer());
            } catch (RuntimeException e) {
                // Streaming is durability, not correctness — never let it touch the tick loop.
                org.slf4j.LoggerFactory.getLogger("NoderaHost")
                        .warn("Nodera: archive stream tick failed: {}", e.toString());
            }
        }
        DiagnosticsService d = NoderaPeerService.get().serverDiagnostics();
        if (d != null) {
            d.onServerTickPost(event);
        }
        // In-game debug console: drain captured Nodera service log lines to subscribed players,
        // and push the relay-metrics summary every 10 s while anyone is listening (who processes
        // whose events, and how long — the live-TPS investigation stream).
        dev.nodera.mod.debug.DebugConsole.flush(event.getServer());
        if (event.getServer().getTickCount() % 200 == 0) {
            var relay = NoderaHost.entityLaneRelayMetrics();
            if (relay != null) {
                dev.nodera.mod.debug.DebugConsole.push("INFO [RelayMetrics] " + relay.describe()
                        .replace('\n', ' '));
            }
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
