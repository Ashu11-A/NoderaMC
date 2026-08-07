package dev.nodera.mod.server;

import dev.nodera.peer.control.CompanionClient;
import dev.nodera.peer.control.CompanionGate;
import dev.nodera.peer.control.CompanionLink;
import dev.nodera.peer.control.CompanionUnavailableException;
import dev.nodera.endpoint.share.PendingCreateShare;
import dev.nodera.endpoint.world.NoderaWorldStore;
import dev.nodera.endpoint.world.PlayerNodeRegistry;
import dev.nodera.mod.common.NoderaConfig;
import dev.nodera.mod.common.NoderaHost;
import dev.nodera.mod.common.NoderaPeerService;
import dev.nodera.mod.common.NoderaSessionPayload;
import dev.nodera.endpoint.share.ShareOptions;
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
        // Block actions ride the same lane as entity actions; the bridge stays inert until a
        // region lane installs itself as the sink, so subscribing here costs a no-op per edit.
        dev.nodera.mod.server.shadow.BlockCaptureBridge.get().register();
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
    private static void safeActivate(MinecraftServer server, dev.nodera.endpoint.share.ShareOptions options) {
        try {
            NoderaHost.activate(server, options);
        } catch (RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger("NoderaHost").error(
                    "Nodera: share failed for '{}' — server continues in vanilla mode: {}",
                    server.getWorldData().getLevelName(), e.toString());
        }
    }

    private static void onServerStarted(ServerStartedEvent event) {
        try {
            // A fresh server is not stopping, whatever the last one was doing. The flag is
            // process-wide and a client opens many worlds in one launch; leaving it set would make
            // the next world's lane bootstrap abandon itself before it started.
            NoderaHost.onServerStarted();
            MinecraftServer server = event.getServer();
            // Task 32's server half: a dedicated server links (and, when `companion.required`,
            // demands) the always-on worker exactly like the client gate does — identity minting,
            // archive seeding, and host delegation all ride this link. The integrated server skips
            // this: its client dist already ran the gate at startup.
            linkServerWorker(server);
            // A dedicated server may auto-host (an always-on FULL_ARCHIVE peer). An integrated
            // server never auto-broadcasts a private world — it waits for the pause-menu "Share"
            // action so singleplayer stays private by default (Task 30a).
            if (server.isDedicatedServer() && NoderaConfig.HOST_AUTO_SHARE.get()) {
                // L-52: `host.sharePassword` is the dedicated server's only way to protect a world
                // — it has no Share screen. Empty keeps the previous plaintext behaviour exactly.
                safeActivate(server, ShareOptions.dedicatedDefault()
                        .withPassword(NoderaConfig.HOST_SHARE_PASSWORD.get()));
                return;
            }
            // Task 5d create pipeline: a world created with "Nodera: Shared" goes on the network the
            // moment it first starts — the same NoderaHost.activate path as the pause-menu Share.
            // The brand-new-world guard (game time 0) keeps a stale parked choice from ever sharing
            // a pre-existing world.
            var pendingShare = dev.nodera.endpoint.share.PendingCreateShare.consume();
            if (pendingShare.isPresent() && server.overworld() != null
                    && server.overworld().getGameTime() == 0L) {
                safeActivate(server, pendingShare.get());
                return;
            }
            // Task 33: a world previously "Opened to Nodera" auto-re-shares on load, so the original
            // host always restores its shared status when returning to the world (no need to press
            // Share again). A corrupt or truncated `nodera-world.dat` throws from here, and until
            // this guard existed it threw straight into ServerStartedEvent dispatch.
            java.nio.file.Path saveRoot = server.getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT);
            if (dev.nodera.endpoint.world.NoderaWorldStore.isShared(saveRoot)) {
                var id = dev.nodera.endpoint.world.NoderaWorldStore.read(saveRoot);
                ShareOptions restored = id.map(w -> new ShareOptions(
                                "", true, w.listedOnTracker(), 5))
                        .orElse(ShareOptions.playerDefault());
                safeActivate(server, restored);
            }
        } catch (CompanionUnavailableException required) {
            // The ONE deliberate abort on this path. `companion.required` means a dedicated server
            // without its worker must not come up pretending to be one, and NeoForge surfacing the
            // throw is how that refusal reaches the operator. Re-thrown explicitly so the firewall
            // below cannot swallow an enforcement decision by accident.
            throw required;
        } catch (RuntimeException | LinkageError e) {
            // Everything else here is Nodera failing, and a world that cannot be shared is still a
            // world: NeoForge's bus rethrows into MinecraftServer.runServer, so this catch is what
            // keeps a bad `nodera-world.dat` from being a crash instead of a vanilla world.
            org.slf4j.LoggerFactory.getLogger("NoderaHost").warn(
                    "Nodera: server-started wiring failed — the world opens unshared: {}",
                    e.toString());
        }
    }

    private static void linkServerWorker(MinecraftServer server) {
        if (!server.isDedicatedServer() || dev.nodera.peer.control.CompanionLink.isPresent()) {
            return;
        }
        String endpoint = NoderaConfig.resolveControlEndpoint(
                NoderaConfig.SERVER_COMPANION_CONTROL_ENDPOINT.get());
        boolean required = NoderaConfig.SERVER_COMPANION_REQUIRED.get();
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("NoderaCompanion");
        try {
            // Bind the telemetry façade to whatever worker the link ends up holding. Registered
            // before the gate runs, so a link recorded below is not missed.
            dev.nodera.endpoint.telemetry.ModTelemetry.followCompanionLink();
            dev.nodera.peer.control.CompanionClient client =
                    dev.nodera.peer.control.CompanionClient.parse(endpoint);
            dev.nodera.peer.control.CompanionGate.GateResult result = required
                    ? dev.nodera.peer.control.CompanionGate.requireRunning(client) // throws if absent
                    : dev.nodera.peer.control.CompanionGate.evaluate(client);
            if (result.ok()) {
                client.probe().ifPresent(info ->
                        dev.nodera.peer.control.CompanionLink.set(client, info));
                log.info("Nodera companion gate (server): {}", result.message());
            } else {
                log.warn("Nodera companion gate (server, not enforced): {}", result.message());
            }
        } catch (IllegalArgumentException e) {
            if (required) {
                throw new dev.nodera.peer.control.CompanionUnavailableException(
                        "Nodera companion endpoint '" + endpoint + "' is malformed: " + e.getMessage());
            }
            log.warn("Nodera companion endpoint '{}' is malformed: {}", endpoint, e.getMessage());
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        try {
            DiagnosticsService d = NoderaPeerService.get().serverDiagnostics();
            if (d != null) {
                d.onServerStopping();
            }
            NoderaHost.onServerStopping(event.getServer());
            dev.nodera.endpoint.world.PlayerNodeRegistry.clear();
            OperatorBridge.get().reset();
        } catch (RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger("NoderaHost").warn(
                    "Nodera: host teardown failed: {}", e.toString());
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        try {
            // Continuity final flush: the save is fully written and quiescent here, so this archive
            // is the session's last word — seeded to the worker, it outlives the game (and the
            // machine). It is also disk and network work on the shutdown path, where a throw takes
            // the whole game down with it instead of losing one archive.
            java.nio.file.Path saveRoot = event.getServer().getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT);
            dev.nodera.mod.common.WorldArchiver.seedNow(saveRoot);
        } catch (RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger("NoderaHost").warn(
                    "Nodera: final archive seed failed — the network copy stays at the last "
                            + "streamed interval: {}", e.toString());
        }
        try {
            // Only after the final flush: the archiver reads the world identity through the hosted
            // state, and forgetting it before the seed would make the last word of the session an
            // anonymous one. Forgetting it at all is the point — the next world opened in this JVM
            // must not inherit this world's permission set or write its grants into this world's
            // save. Its own guard, because a failed seed must not skip it.
            NoderaHost.forgetHostedWorld();
        } catch (RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger("NoderaHost").warn(
                    "Nodera: forgetting the hosted world failed: {}", e.toString());
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        String route;
        try {
            // Everything the join needs from Nodera, behind one firewall. The inner guard below
            // was already here and stays: it lets the lane bootstrap fail without costing the
            // player their session payload, which this outer catch — reached only once — cannot do.
            route = NoderaPeerService.get().hostRoute();
            if (route != null && event.getEntity() instanceof ServerPlayer player) {
                // The world identity rides the session payload so every joiner — whatever path it
                // used to connect — can arm the continuity lane for exactly this world.
                MinecraftServer server = player.serverLevel().getServer();
                String worldIdHex = dev.nodera.endpoint.world.NoderaWorldStore
                        .read(server.getWorldPath(
                                net.minecraft.world.level.storage.LevelResource.ROOT))
                        .map(id -> id.worldId().toHex()).orElse("");
                // Issue #36 F1: a fresh single-use announce challenge rides the session payload; the
                // client must sign it (bound to its key + MC UUID) for its announce to be trusted.
                String challengeB64 = java.util.Base64.getEncoder().encodeToString(
                        dev.nodera.mod.common.ModNetworking.announceChallenges()
                                .issue(player.getUUID(), System.currentTimeMillis()).toArray());
                PacketDistributor.sendToPlayer(player, new NoderaSessionPayload(
                        route, worldIdHex, server.getWorldData().getLevelName(), challengeB64));
                // Keep the worker's live player count fresh (multiplayer-list rows, Task 33).
                NoderaHost.refreshWorkerPresence(server);
                // Issue #36 F3: op the world author (integrated owner) on login — covers an
                // auto-re-share that ran before the owner joined. Everyone else's op flows from a
                // key-checked grant.
                NoderaHost.syncAuthorOnLogin(server, player);
            }
        } catch (RuntimeException | LinkageError e) {
            // Same rule as the lane guard below, applied to the rest of the method: a Nodera
            // failure must never take the server down with the player mid-login. They join a
            // vanilla-looking world instead.
            org.slf4j.LoggerFactory.getLogger("NoderaHost").warn(
                    "Nodera: session hand-off on login failed: {}", e.toString());
            return;
        }
        try {
            // Task 12 live lane: the FOV ownership plan needs a player to anchor it, so a world
            // shared before anyone was online (a dedicated auto-share boot) activates on first
            // login instead.
            if (route != null && NoderaHost.laneEnabledHere() && !NoderaHost.entityLaneActive()
                    && event.getEntity() instanceof ServerPlayer player) {
                NoderaHost.activateEntityLaneFromWorld(player.serverLevel().getServer());
            }
        } catch (RuntimeException | LinkageError e) {
            // A lane bootstrap failure (including a missing optional library) must never take
            // the server down with the player mid-login.
            org.slf4j.LoggerFactory.getLogger("NoderaHost").warn(
                    "Nodera: entity lane bootstrap on login failed: {}", e.toString());
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        // The login twin has been guarded since issue #39 — "must never take the server down with
        // the player mid-login" — and this half was not, while doing strictly more: a departure
        // handoff writes region archives to disk AND seeds them to the network, and a re-plan
        // rebuilds the committee. Any of it throwing here reaches PlayerList.remove, which is
        // called from the network thread and from the server's own shutdown.
        //
        // Each step below gets its OWN guard, for the same reason onServerStopped has two rather
        // than one: this is a teardown sequence, and under a single guard an early throw is not a
        // survivable failure but a silent skip of everything after it. The steps are not
        // interchangeable — the last three are what stops the world remembering a player who has
        // gone — so they may not be made conditional on the ones before them succeeding. The ORDER
        // is still contract: RegionDepartureHandoff documents "call this before
        // PlayerNodeRegistry#forget", because after the forget the plan no longer holds the player
        // and there is nothing left to hand over. Splitting the guards preserves that order; it
        // only stops a failed handoff from cancelling the forget.
        try {
            DiagnosticsService d = NoderaPeerService.get().serverDiagnostics();
            if (d != null) {
                d.onPlayerLoggedOut(event.getEntity());
            }
        } catch (RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger("NoderaHost").warn(
                    "Nodera: departure diagnostics failed: {}", e.toString());
        }
        ServerPlayer player;
        MinecraftServer server;
        try {
            if (!(event.getEntity() instanceof ServerPlayer leaving)) {
                return;
            }
            player = leaving;
            server = leaving.serverLevel().getServer();
        } catch (RuntimeException | LinkageError e) {
            // Nothing below can run without these two, so this is the one guard that returns.
            org.slf4j.LoggerFactory.getLogger("NoderaHost").warn(
                    "Nodera: the departing player could not be resolved, so their node is left in "
                            + "the plan: {}", e.toString());
            return;
        }
        try {
            // `player` is passed because vanilla has not removed them yet: PlayerList.remove
            // fires this event as its FIRST statement and calls players.remove sixteen lines
            // later, so a naive count here is one too high — permanently, since nothing later
            // corrects it. It reads the world identity off disk and talks to the worker, so it is
            // a real throw site and used to be the first one: under the old single guard, a
            // corrupt nodera-world.dat here cost the player their forget and the world its re-plan.
            NoderaHost.refreshWorkerPresence(server, player);
        } catch (RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger("NoderaHost").warn(
                    "Nodera: presence refresh on departure failed — the worker's player count "
                            + "stays stale until the next cadence tick: {}", e.toString());
        }
        try {
            // Drop any op the bridge granted this player so it never lingers past their session.
            OperatorBridge.get().onLogout(server, player);
        } catch (RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger("NoderaHost").warn(
                    "Nodera: dropping the departing player's granted op failed: {}", e.toString());
        }
        try {
            // Before anything forgets them: hand their regions over. A re-plan decides who is
            // RESPONSIBLE for a region; it does not move the region. For ground only this
            // player could see, the chunks stop being held the moment they leave the plan, and
            // everything built there since the last seed goes with them. This is the step that
            // seeds it first — disk writes plus network seeding, the most failure-prone thing this
            // handler does, and its own guard because losing one region's edits must not also
            // leave a ghost node in the plan.
            dev.nodera.mod.server.shadow.RegionDepartureHandoff.handOff(server, player);
        } catch (RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger("NoderaHost").warn(
                    "Nodera: region handoff for the departing player failed — their regions keep "
                            + "the copies they already had: {}", e.toString());
        }
        try {
            // No-host ownership: a departed player's node leaves the plan; the survivors re-plan
            // and absorb its regions (the FOV planner reassigns deterministically). This line is
            // the only caller of PlayerNodeRegistry#forget in the tree, so nothing else recovers a
            // node it skips — the plan would keep assigning regions to a player who has left, and
            // their single-use announce challenge would outlive the session that issued it.
            dev.nodera.endpoint.world.PlayerNodeRegistry.forget(player.getUUID());
            dev.nodera.mod.common.ModNetworking.announceChallenges().forget(player.getUUID());
        } catch (RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger("NoderaHost").warn(
                    "Nodera: forgetting the departing player failed — a node that has left is "
                            + "still in the ownership plan: {}", e.toString());
        }
        try {
            NoderaHost.replanEntityLane(server);
        } catch (RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger("NoderaHost").warn(
                    "Nodera: re-planning after a departure failed — the committee keeps the "
                            + "departed player's seats until the next re-plan: {}", e.toString());
        }
    }

    private static void onServerTickPost(ServerTickEvent.Post event) {
        // Every step below gets its OWN guard rather than one wrapper around the method, because
        // the point of a per-tick handler is that the next step still runs: an outer catch would
        // turn one failing sub-system into every later sub-system silently not ticking. Three of
        // these guards were already here; the other three are what issue #231 was about.
        try {
            // Region enter/leave evidence log + the scripted ownership drive (docs/minecraft/TESTING.md).
            dev.nodera.mod.server.entity.RegionDriveDebug.onServerTick(event.getServer());
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger("NoderaHost")
                    .warn("Nodera: region drive debug tick failed: {}", e.toString());
        }
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
        try {
            NoderaHost.tickGamePublish(event.getServer());
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger("NoderaHost")
                    .warn("Nodera: parked publish tick failed: {}", e.toString());
        }
        // Re-report the player count on a cadence. The worker holds it under a lease and forgets it
        // when nobody refreshes — that is what stops a dead game vouching for a stale number — so a
        // live game has to keep saying it. Login/logout alone are not enough: a busy world nobody
        // joins or leaves for an hour would go quiet and read as unknown for that hour.
        try {
            NoderaHost.tickWorkerPresence(event.getServer());
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger("NoderaHost")
                    .warn("Nodera: presence refresh failed: {}", e.toString());
        }
        // Issue #43 continuous streaming: while hosting, re-seed the world archive on the
        // configured cadence so the network copy is never more than one interval behind — a
        // crash/exit can no longer revert the world past the last interval.
        try {
            // `isHosting()` moved INSIDE the guard: a state read is still a call, and the guard
            // that covered only the line under it left the question "are we hosting" outside.
            if (NoderaPeerService.get().isHosting()) {
                dev.nodera.mod.common.WorldArchiver.streamTick(event.getServer());
            }
        } catch (RuntimeException e) {
            // Streaming is durability, not correctness — never let it touch the tick loop.
            org.slf4j.LoggerFactory.getLogger("NoderaHost")
                    .warn("Nodera: archive stream tick failed: {}", e.toString());
        }
        try {
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
        } catch (RuntimeException e) {
            // The whole reporting half under one guard: a HUD sample, a console drain and a
            // metrics line are the same kind of thing — evidence about the tick, never the tick.
            org.slf4j.LoggerFactory.getLogger("NoderaHost")
                    .warn("Nodera: diagnostics tick failed: {}", e.toString());
        }
    }

    private static void onPlayerTickPost(PlayerTickEvent.Post event) {
        try {
            DiagnosticsService d = NoderaPeerService.get().serverDiagnostics();
            if (d != null && event.getEntity() instanceof ServerPlayer player) {
                d.onPlayerTickPost(player);
            }
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger("NoderaHost")
                    .warn("Nodera: player diagnostics tick failed: {}", e.toString());
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        try {
            NoderaCommand.register(event.getDispatcher(),
                    () -> NoderaPeerService.get().serverDiagnostics());
        } catch (RuntimeException | LinkageError e) {
            // A world with no `/nodera` command is a working world; a world that will not start
            // because a command tree failed to build is not.
            org.slf4j.LoggerFactory.getLogger("NoderaHost").warn(
                    "Nodera: command registration failed — /nodera is unavailable: {}", e.toString());
        }
    }
}
