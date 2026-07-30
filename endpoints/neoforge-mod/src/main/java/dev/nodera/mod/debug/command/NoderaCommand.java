package dev.nodera.mod.debug.command;

import dev.nodera.endpoint.control.CompanionLink;
import dev.nodera.endpoint.lang.CommandLang;
import dev.nodera.endpoint.share.ShareOptions;
import dev.nodera.endpoint.telemetry.ModTelemetry;
import dev.nodera.endpoint.world.PlayerNodeRegistry;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.nodera.diagnostics.model.TelemetrySnapshot;
import dev.nodera.diagnostics.state.Semantic;
import dev.nodera.diagnostics.view.Cell;
import dev.nodera.diagnostics.view.Panel;
import dev.nodera.diagnostics.view.Row;
import dev.nodera.diagnostics.view.ViewBuilder;
import dev.nodera.mod.debug.DiagnosticsService;
import dev.nodera.mod.debug.DiagnosticsService.Surface;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * The server {@code /nodera} command tree (Task 18). Replaces the inline
 * {@code ServerBootstrap.status()/peers()} executors with a declarative table of subcommands — each
 * produces a {@link dev.nodera.diagnostics.view.Panel}/view rendered uniformly by
 * {@link CommandTree}. {@code status} and {@code peers} remain as aliases (no break for the
 * {@code scripts/} beta scenario). Read-only panels are open to all; {@code server}, {@code whois},
 * and {@code debug} require op (permission level 2).
 *
 * <p>The {@link DiagnosticsService} is supplied lazily and resolved per-execution, so the tree can be
 * registered at server load before the bootstrap peer (and its service) is up.
 *
 * <p>Thread-context: executors run on the server main thread.
 */
public final class NoderaCommand {

    private static final int OP_LEVEL = 2;

    private NoderaCommand() {}

    /** Register the {@code /nodera} tree against a lazy diagnostics-service supplier. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                Supplier<DiagnosticsService> service) {
        Supplier<TelemetrySnapshot> snap = () -> {
            DiagnosticsService s = service.get();
            return s == null ? null : s.snapshotOrSample();
        };

        dispatcher.register(literal("nodera")
                .then(literal("share")
                        .requires(s -> s.hasPermission(OP_LEVEL))
                        .executes(NoderaCommand::shareStart)
                        .then(literal("stop").executes(NoderaCommand::shareStop))
                        .then(literal("status").executes(NoderaCommand::shareStatus))
                        // L-51: the author changes the world password. The Share screen drives the
                        // same NoderaHost.reconfigure; a dedicated server has no screen at all, and
                        // neither does a scripted run.
                        .then(literal("password")
                                .then(argument("new", StringArgumentType.string())
                                        .executes(NoderaCommand::sharePassword))))
                .then(literal("worlds").executes(NoderaCommand::worlds))
                .then(literal("session").executes(CommandTree.panel(snap, ViewBuilder::sessionPanel)))
                .then(literal("status").executes(CommandTree.panel(snap, ViewBuilder::sessionPanel))) // alias
                .then(literal("peers").executes(CommandTree.panel(snap, ViewBuilder::peersPanel)))    // alias
                .then(literal("net")
                        .executes(CommandTree.panel(snap, s -> ViewBuilder.netPanel(s, null)))
                        .then(argument("type", StringArgumentType.string())
                                .executes(ctx -> netType(ctx, service))))
                .then(literal("regions").executes(CommandTree.panel(snap, ViewBuilder::regionsPanel)))
                .then(literal("zone").executes(ctx -> zoneHere(ctx, service)))
                .then(literal("entities").executes(CommandTree.panel(snap, ViewBuilder::entitiesPanel)))
                .then(literal("health").executes(CommandTree.panel(snap, ViewBuilder::healthPanel)))
                .then(literal("server").requires(s -> s.hasPermission(OP_LEVEL))
                        .executes(CommandTree.panel(snap, ViewBuilder::serverPanel)))
                .then(literal("hud")
                        .then(hudBranch("tab", Surface.TAB, service))
                        .then(hudBranch("bars", Surface.BARS, service))
                        .then(hudBranch("alerts", Surface.ALERTS, service))
                        .then(hudBranch("all", Surface.ALL, service)))
                .then(literal("whois").requires(s -> s.hasPermission(OP_LEVEL))
                        .then(argument("player", EntityArgument.player())
                                .executes(NoderaCommand::whois)))
                .then(literal("op").requires(s -> s.hasPermission(OP_LEVEL))
                        .then(argument("player", EntityArgument.player())
                                .executes(ctx -> setRole(ctx, true))))
                .then(literal("deop").requires(s -> s.hasPermission(OP_LEVEL))
                        .then(argument("player", EntityArgument.player())
                                .executes(ctx -> setRole(ctx, false))))
                // Consent, in the place a player actually is. The companion app is required to
                // play, but a player may never open its window — and a setting they cannot find is
                // a setting they cannot change.
                .then(literal("telemetry")
                        .executes(NoderaCommand::telemetryStatus)
                        .then(literal("on").requires(s -> s.hasPermission(OP_LEVEL))
                                .executes(ctx -> telemetryConsent(ctx, true)))
                        .then(literal("off").requires(s -> s.hasPermission(OP_LEVEL))
                                .executes(ctx -> telemetryConsent(ctx, false))))
                .then(literal("selftest").requires(s -> s.hasPermission(OP_LEVEL))
                        .executes(ctx -> SelfTest.run(ctx, false))
                        .then(literal("full").executes(ctx -> SelfTest.run(ctx, true))))
                .then(literal("debug").requires(s -> s.hasPermission(OP_LEVEL))
                        .then(literal("sample-rate")
                                .then(argument("n", IntegerArgumentType.integer(1, 100))
                                        .executes(ctx -> sampleRate(ctx, service))))
                        .then(literal("verbose")
                                .then(literal("on").executes(ctx -> verbose(ctx, true)))
                                .then(literal("off").executes(ctx -> verbose(ctx, false))))
                        .then(literal("relay").executes(NoderaCommand::relay))
                        .then(literal("capture").executes(NoderaCommand::capture))
                        .then(literal("drive")
                                .then(argument("edits", IntegerArgumentType.integer(1, 512))
                                        .executes(NoderaCommand::driveEdits)))
                        .then(literal("extract").executes(NoderaCommand::extract))));

        // Issue #46: /tps (OP) — this server's live TPS + per-player round-trip latency (the
        // network view of every connected player; each Nodera player runs their own server, so
        // the local TPS IS the current player's TPS).
        dispatcher.register(literal("tps").requires(s -> s.hasPermission(OP_LEVEL))
                .executes(NoderaCommand::tps));
        // Issue #46: standard server muscle-memory aliases onto the key-checked Nodera grant lane
        // (#36) — /op == /nodera op, /deop == /nodera deop. Registered AFTER vanilla; Brigadier's
        // last registration for a literal wins, so these shadow the vanilla UUID-based op.
        dispatcher.register(literal("op").requires(s -> s.hasPermission(OP_LEVEL))
                .then(argument("player", EntityArgument.player())
                        .executes(ctx -> setRole(ctx, true))));
        dispatcher.register(literal("deop").requires(s -> s.hasPermission(OP_LEVEL))
                .then(argument("player", EntityArgument.player())
                        .executes(ctx -> setRole(ctx, false))));
    }

    /** {@code /tps} — live server TPS + per-player latency (issue #46). */
    private static int tps(CommandContext<CommandSourceStack> ctx) {
        var server = ctx.getSource().getServer();
        double tps = dev.nodera.mod.debug.render.BossBarManager.currentTps(server);
        // MC-GUI-5: numbers are formatted here, sentences are not — the row shape comes from the
        // lang file, and this command only supplies the measurements.
        List<Row> rows = new ArrayList<>();
        rows.add(Row.of(Cell.tr(CommandLang.TPS_HEADER, Semantic.HEADING,
                String.format(java.util.Locale.ROOT, "%.1f", tps),
                String.format(java.util.Locale.ROOT, "%.2f",
                        server.getAverageTickTimeNanos() / 1_000_000.0))));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            rows.add(Row.of(Cell.tr(CommandLang.TPS_PING, Semantic.NEUTRAL,
                    p.getGameProfile().getName(), p.connection.latency())));
        }
        CommandTree.sendPanel(ctx.getSource(),
                Panel.titled(CommandLang.TPS_TITLE, Semantic.HEADING, rows));
        return 1;
    }

    /** {@code /nodera share} — put the currently-loaded world on the network (op). */
    private static int shareStart(CommandContext<CommandSourceStack> ctx) {
        var server = ctx.getSource().getServer();
        dev.nodera.endpoint.share.ShareOptions current =
                dev.nodera.mod.common.NoderaPeerService.get().hostOptions();
        dev.nodera.mod.common.NoderaHost.activate(server,
                current != null ? current : dev.nodera.endpoint.share.ShareOptions.playerDefault());
        return CommandTree.announce(ctx, CommandLang.SHARE_STARTED,
                server.getWorldData().getLevelName());
    }

    /** {@code /nodera share stop} — stop sharing (op). */
    private static int shareStop(CommandContext<CommandSourceStack> ctx) {
        var server = ctx.getSource().getServer();
        dev.nodera.mod.common.NoderaHost.deactivate(server);
        return CommandTree.announce(ctx, CommandLang.SHARE_STOPPED,
                server.getWorldData().getLevelName());
    }

    /**
     * {@code /nodera share password <new>} — re-key the shared world (op, author only).
     *
     * <p>A password change is a full re-key, never an in-place edit: the save is re-packed and
     * re-encrypted under a fresh salt, the manifest root changes, the identity is re-signed, and the
     * world is re-announced. Only the world's author may do it, and the outcome is reported — this
     * command reports what {@link dev.nodera.mod.common.NoderaHost#reconfigure} decided, never a
     * cheerful "done" over a failure.
     */
    private static int sharePassword(CommandContext<CommandSourceStack> ctx) {
        var server = ctx.getSource().getServer();
        var svc = dev.nodera.mod.common.NoderaPeerService.get();
        dev.nodera.endpoint.share.ShareOptions current = svc.hostOptions();
        if (!svc.isHosting() || current == null) {
            return CommandTree.fail(ctx, CommandLang.SHARE_NOT_SHARING);
        }
        if (!dev.nodera.mod.common.NoderaHost.localWorkerIsAuthor(server)) {
            return CommandTree.fail(ctx, CommandLang.SHARE_NOT_AUTHOR);
        }
        String next = StringArgumentType.getString(ctx, "new");
        if (next.equals(current.password())) {
            return CommandTree.fail(ctx, CommandLang.SHARE_SAME_PASSWORD);
        }
        CommandTree.announce(ctx, CommandLang.SHARE_REKEYING,
                server.getWorldData().getLevelName());
        dev.nodera.mod.common.NoderaHost.reconfigure(server, current.withPassword(next));
        return 1;
    }

    /** {@code /nodera share status} — host lane state: route, game endpoint, worker link. */
    private static int shareStatus(CommandContext<CommandSourceStack> ctx) {
        var svc = dev.nodera.mod.common.NoderaPeerService.get();
        if (!svc.isHosting()) {
            CommandTree.reply(ctx, CommandLang.SHARE_NOT_SHARING);
            return 1;
        }
        String game = svc.gameRoute();
        boolean worker = dev.nodera.endpoint.control.CompanionLink.isPresent();
        // The Share screen already had lang entries for the endpoint and worker lines; the command
        // reuses them rather than paraphrasing the same two facts in a second wording.
        CommandTree.sendPanel(ctx.getSource(), Panel.titled(CommandLang.SHARE_STATUS_TITLE,
                Semantic.HEADING, List.of(
                        Row.of(Cell.tr(CommandLang.SHARE_STATUS_P2P, Semantic.HEALTHY,
                                svc.hostRoute())),
                        Row.of(game == null
                                ? Cell.tr(CommandLang.SHARE_STATUS_NO_ROUTE, Semantic.SECONDARY)
                                : Cell.tr(CommandLang.SHARE_STATUS_ROUTE, Semantic.NEUTRAL, game)),
                        Row.of(Cell.tr(worker
                                        ? CommandLang.SHARE_STATUS_WORKER_ON
                                        : CommandLang.SHARE_STATUS_WORKER_OFF,
                                worker ? Semantic.HEALTHY : Semantic.DEGRADED)))));
        return 1;
    }

    /**
     * {@code /nodera telemetry} — what this node shares, and whether it shares at all.
     *
     * <p>Readable by everyone, changeable by an operator: on a player's own node those are the same
     * person, and on a shared one the decision belongs to whoever runs it.
     */
    private static int telemetryStatus(CommandContext<CommandSourceStack> ctx) {
        dev.nodera.endpoint.telemetry.ModTelemetry.refresh();
        dev.nodera.telemetry.TelemetryConsent consent =
                dev.nodera.endpoint.telemetry.ModTelemetry.consent();
        String stateKey = switch (consent) {
            case GRANTED -> CommandLang.TELEMETRY_GRANTED;
            case DENIED -> CommandLang.TELEMETRY_DENIED;
            case UNANSWERED -> CommandLang.TELEMETRY_UNANSWERED;
        };
        // The state phrase is itself a translated component nested into the status line, so a
        // translator can reorder the sentence without the three states drifting apart.
        CommandTree.sendPanel(ctx.getSource(), Panel.titled(CommandLang.TELEMETRY_TITLE,
                Semantic.HEADING, List.of(
                        Row.of(Cell.tr(CommandLang.TELEMETRY_STATE,
                                consent == dev.nodera.telemetry.TelemetryConsent.GRANTED
                                        ? Semantic.HEALTHY : Semantic.SECONDARY,
                                Component.translatable(stateKey))),
                        Row.of(Cell.tr(CommandLang.TELEMETRY_SHARED, Semantic.NEUTRAL)),
                        Row.of(Cell.tr(CommandLang.TELEMETRY_CHANGE, Semantic.SECONDARY)))));
        return 1;
    }

    /** {@code /nodera telemetry on|off} — record the decision on the node. */
    private static int telemetryConsent(CommandContext<CommandSourceStack> ctx, boolean granted) {
        java.util.Optional<String> error =
                dev.nodera.endpoint.telemetry.ModTelemetry.setConsent(granted);
        if (error.isPresent()) {
            return CommandTree.fail(ctx, CommandLang.TELEMETRY_ERROR, error.get());
        }
        // Report what the NODE confirmed, not what the command intended — the same discipline the
        // companion app's settings badges follow.
        boolean live = dev.nodera.endpoint.telemetry.ModTelemetry.collects();
        return CommandTree.announce(ctx,
                live ? CommandLang.TELEMETRY_ON : CommandLang.TELEMETRY_OFF);
    }

    /** {@code /nodera worlds} — the tracker directory as the network sees it. */
    private static int worlds(CommandContext<CommandSourceStack> ctx) {
        java.util.List<dev.nodera.protocol.discovery.TrackerCatalogEntry> catalog;
        var tracker = dev.nodera.mod.common.NoderaPeerService.get().serverTrackerClient();
        if (tracker != null && !tracker.endpoints().isEmpty()) {
            catalog = tracker.catalog(0);
        } else {
            try (var ephemeral = trackerFromConfig()) {
                catalog = ephemeral == null ? java.util.List.of() : ephemeral.catalog(0);
            }
        }
        if (catalog.isEmpty()) {
            return CommandTree.reply(ctx, CommandLang.WORLDS_NONE_LISTED);
        }
        List<Row> rows = new ArrayList<>();
        for (var entry : catalog) {
            rows.add(Row.of(
                    Cell.raw(entry.worldName().isBlank()
                            ? entry.genesisHash().toHex().substring(0, 12) : entry.worldName()),
                    // PEERS, not players: this is the tracker catalog, and a tracker only ever
                    // sees who is announcing a swarm. Saying "online" here credited three seeders
                    // of an empty world with three players.
                    Cell.tr(CommandLang.WORLDS_PEERS, Semantic.NEUTRAL, entry.worldPlayerCount()),
                    Cell.tr(dev.nodera.diagnostics.view.TorrentWorldListView
                            .healthKey(entry.health()), Semantic.SECONDARY)));
        }
        CommandTree.sendPanel(ctx.getSource(),
                Panel.titled(CommandLang.WORLDS_TITLE, Semantic.HEADING, rows));
        return catalog.size();
    }

    /** An ephemeral tracker client over the server-config endpoints, or {@code null} if none. */
    private static dev.nodera.peer.discovery.TrackerClient trackerFromConfig() {
        java.util.List<dev.nodera.peer.discovery.TrackerClient.Endpoint> endpoints =
                new java.util.ArrayList<>();
        for (String route : dev.nodera.mod.common.NoderaConfig.TRACKER_ENDPOINTS.get()) {
            try {
                endpoints.add(dev.nodera.peer.discovery.TrackerClient.Endpoint.parse(route));
            } catch (IllegalArgumentException ignored) {
                // config-spec validated; a malformed survivor is just skipped
            }
        }
        return endpoints.isEmpty() ? null : new dev.nodera.peer.discovery.TrackerClient(
                endpoints, dev.nodera.core.identity.NodeIdentity.generate());
    }

    /** {@code /nodera zone} — the region at the caller's position + its ownership state. */
    private static int zoneHere(CommandContext<CommandSourceStack> ctx, Supplier<DiagnosticsService> service) {
        ServerPlayer player = ctx.getSource().getPlayer();
        DiagnosticsService svc = service.get();
        TelemetrySnapshot s = svc == null ? null : svc.latest();
        if (player == null || s == null) {
            return CommandTree.offline(ctx);
        }
        return CommandTree.sendPanel(ctx.getSource(),
                ViewBuilder.zonePanel(s, dev.nodera.mod.debug.Dimensions.of(player),
                        player.blockPosition().getX(), player.blockPosition().getZ()));
    }

    /** {@code /nodera net <type>} — the per-type breakdown filtered to one type. */
    private static int netType(CommandContext<CommandSourceStack> ctx, Supplier<DiagnosticsService> service) {
        DiagnosticsService svc = service.get();
        TelemetrySnapshot s = svc == null ? null : svc.latest();
        if (s == null) {
            return CommandTree.offline(ctx);
        }
        return CommandTree.sendPanel(ctx.getSource(), ViewBuilder.netPanel(s, StringArgumentType.getString(ctx, "type")));
    }

    /** {@code /nodera whois <player>} — staged off (needs ClientDiagnosticsReport, Task 18 §networking). */
    private static int whois(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        CommandTree.reply(ctx, CommandLang.WHOIS_STAGED, target.getName().getString());
        return 1;
    }

    /**
     * {@code /nodera op|deop <player>} (issue #36): mint an author-signed, key-bound permission grant
     * that ops (OPERATOR) or de-ops (MEMBER) a player, apply + persist it, and reconcile the vanilla
     * op status. Authority is the executor's key-checked OWNER/OPERATOR role (or the integrated owner
     * / server console); the target must have a verified Nodera identity.
     */
    private static int setRole(CommandContext<CommandSourceStack> ctx, boolean grantOp)
            throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        var server = src.getServer();
        var perms = dev.nodera.mod.common.NoderaHost.hostedPermissions();
        if (perms == null) {
            return CommandTree.fail(ctx, CommandLang.GRANT_NOT_SHARED);
        }
        ServerPlayer executor = src.getPlayer(); // null for the server console
        if (!isGrantAuthorized(server, executor, perms)) {
            return CommandTree.fail(ctx, CommandLang.GRANT_UNAUTHORIZED);
        }
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        dev.nodera.endpoint.world.PlayerNodeRegistry.PlayerNode node =
                dev.nodera.endpoint.world.PlayerNodeRegistry.nodeOf(target.getUUID());
        if (node == null) {
            return CommandTree.fail(ctx, CommandLang.GRANT_NO_IDENTITY,
                    target.getName().getString());
        }
        if (!dev.nodera.endpoint.control.CompanionLink.isPresent()) {
            return CommandTree.fail(ctx, CommandLang.GRANT_NO_WORKER);
        }
        dev.nodera.core.identity.WorldRole role = grantOp
                ? dev.nodera.core.identity.WorldRole.OPERATOR
                : dev.nodera.core.identity.WorldRole.MEMBER;
        long grantVersion = perms.grant(node.nodeId())
                .map(g -> g.grantVersion() + 1).orElse(1L);
        String worldIdHex = perms.worldId().toHex();
        java.util.Optional<dev.nodera.core.Bytes> signed =
                dev.nodera.endpoint.control.CompanionLink.client().grantRole(worldIdHex,
                        node.nodeId().value().toString(), node.publicKey(), role.ordinal(),
                        grantVersion);
        if (signed.isEmpty()) {
            return CommandTree.fail(ctx, CommandLang.GRANT_DECLINED);
        }
        dev.nodera.storage.WorldPermissionGrant grant;
        try {
            grant = dev.nodera.storage.WorldPermissionGrant.decode(
                    new dev.nodera.core.crypto.CanonicalReader(signed.get()));
        } catch (RuntimeException e) {
            return CommandTree.fail(ctx, CommandLang.GRANT_MALFORMED);
        }
        if (!dev.nodera.mod.common.NoderaHost.applyGrant(grant)) {
            return CommandTree.fail(ctx, CommandLang.GRANT_REJECTED);
        }
        dev.nodera.mod.server.OperatorBridge.get().syncPlayer(server, target);
        return CommandTree.announce(ctx,
                grantOp ? CommandLang.GRANT_OPPED : CommandLang.GRANT_DEOPPED,
                target.getName().getString());
    }

    /** Whether the executor may mint permission grants for this world. */
    private static boolean isGrantAuthorized(net.minecraft.server.MinecraftServer server,
                                             ServerPlayer executor,
                                             dev.nodera.storage.WorldPermissions perms) {
        if (executor == null) {
            return true; // the server console is a trusted local operator
        }
        if (!server.isDedicatedServer()
                && server.isSingleplayerOwner(executor.getGameProfile())
                && dev.nodera.mod.common.NoderaHost.localWorkerIsAuthor(server)) {
            return true; // the integrated-server owner authoring this world
        }
        dev.nodera.endpoint.world.PlayerNodeRegistry.PlayerNode node =
                dev.nodera.endpoint.world.PlayerNodeRegistry.nodeOf(executor.getUUID());
        return node != null && perms.isOperator(node.nodeId(), node.publicKey());
    }

    private static int sampleRate(CommandContext<CommandSourceStack> ctx, Supplier<DiagnosticsService> service) {
        int n = IntegerArgumentType.getInteger(ctx, "n");
        DiagnosticsService svc = service.get();
        if (svc != null) {
            svc.setSampleTicks(n);
        }
        CommandTree.reply(ctx, CommandLang.DEBUG_SAMPLE_RATE, n);
        return n;
    }

    /** {@code /nodera debug verbose} — staged: the surface ships now, finer logging lands with it. */
    /**
     * {@code /nodera debug verbose on|off} — the in-game Nodera debug console: mirror every
     * Nodera service log line to this player's chat while enabled.
     */
    private static int verbose(CommandContext<CommandSourceStack> ctx, boolean on)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (on) {
            dev.nodera.mod.debug.DebugConsole.subscribe(player);
            CommandTree.reply(ctx, CommandLang.DEBUG_VERBOSE_ON);
        } else {
            dev.nodera.mod.debug.DebugConsole.unsubscribe(player);
            CommandTree.reply(ctx, CommandLang.DEBUG_VERBOSE_OFF);
        }
        return 1;
    }

    /**
     * {@code /nodera debug relay} — per-peer event-relay accounting: how many events this node
     * captured from its own game vs processed for each other player, with average processing
     * times (the live-TPS investigation surface).
     */
    /**
     * The block-capture ledger: how many real edits became consensus actions, and for each edit
     * that did not, the reason. This is the live counterpart of the headless shadow soak's
     * bandwidth/interference numbers — "nothing was captured" and "everything was captured" look
     * identical from inside the game without it.
     */
    /**
     * Drive N validated block edits as the calling player (minecraft L-80 / issue #67 clause 1).
     *
     * <p><b>Why this exists.</b> The block-capture path is entity-driven on purpose:
     * {@code BlockCaptureBridge} listens to {@code EntityPlaceEvent}/{@code BreakEvent} and submits
     * only when the placer is a {@link ServerPlayer}, because the player's key is what signs the
     * action. That is correct, and it makes the path <b>undrivable from a script</b>: an RCON
     * {@code /setblock} is a direct world write and fires neither event. The determinism soak drove
     * 197 rounds of {@code setblock} and recorded an empty capture ledger for exactly that reason,
     * so its "zero divergences" measured an idle lane — no duration of soak would have fixed it.
     *
     * <p><b>What it does and does not stand in for.</b> It calls the same
     * {@code submitBlockAction} entry point the bridge calls, with the same actor, so everything
     * downstream is real: the signature, the admission rule, the forward-to-primary routing, the
     * proposal and the commit. What it skips is the NeoForge event plumbing upstream of that call —
     * which is covered by ordinary play and by {@code e2e-commands}. It is OP-gated and places into
     * the caller's own region, so it can assert nothing a player could not do themselves.
     */
    private static int driveEdits(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException notAPlayer) {
            return CommandTree.fail(ctx, CommandLang.DRIVE_NOT_PLAYER);
        }
        var lane = dev.nodera.mod.common.NoderaHost.entityLaneRuntime();
        if (lane == null) {
            return CommandTree.fail(ctx, CommandLang.DRIVE_NO_LANE);
        }
        int edits = IntegerArgumentType.getInteger(ctx, "edits");
        var level = player.serverLevel();
        int accepted = 0;
        for (int i = 0; i < edits; i++) {
            // Alternate stone and air at a position beside the player: a place and a break, which
            // is what the capture ledger distinguishes.
            net.minecraft.core.BlockPos pos = player.blockPosition()
                    .offset(1 + (i % 3), 0, 1 + ((i / 3) % 3));
            boolean place = (i % 2) == 0;
            net.minecraft.world.level.block.state.BlockState state = place
                    ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()
                    : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            level.setBlockAndUpdate(pos, state);
            if (dev.nodera.mod.server.shadow.BlockCaptureBridge.get()
                    .driveAsPlayer(player, pos, state, place)) {
                accepted++;
            }
        }
        CommandTree.reply(ctx, CommandLang.DRIVE_DONE, edits, accepted);
        return 1;
    }

    private static int capture(CommandContext<CommandSourceStack> ctx) {
        var outcomes = dev.nodera.mod.server.shadow.BlockCaptureBridge.get().outcomes();
        long total = outcomes.values().stream().mapToLong(Long::longValue).sum();
        List<Row> rows = new ArrayList<>();
        rows.add(Row.of(Cell.tr(CommandLang.CAPTURE_TOTAL, Semantic.HEADING, total)));
        outcomes.forEach((reason, count) -> {
            if (count > 0) {
                // The reason is an enum constant, not prose, so it rides in as an argument.
                rows.add(Row.of(Cell.tr(CommandLang.CAPTURE_REASON, Semantic.NEUTRAL,
                        reason.name(), count)));
            }
        });
        if (total == 0) {
            rows.add(Row.of(Cell.tr(CommandLang.CAPTURE_NONE, Semantic.SECONDARY)));
        }
        CommandTree.sendPanel(ctx.getSource(),
                Panel.titled(CommandLang.CAPTURE_TITLE, Semantic.HEADING, rows));
        return 1;
    }

    /**
     * Extract the caller's own region from the live world and say what came out: how much of it the
     * palette can express, how much was not resident, and — when this node's lane holds the region —
     * how far the world has drifted from the committed state (the live interference measurement the
     * Phase-1 acceptance asks for, in blocks rather than in sections).
     */
    private static int extract(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (CommandSyntaxException notAPlayer) {
            return CommandTree.fail(ctx, CommandLang.EXTRACT_NOT_PLAYER);
        }
        var level = player.serverLevel();
        var region = dev.nodera.core.region.RegionId.fromChunk(
                dev.nodera.mod.server.entity.MinecraftEntityAdapters.dimension(level),
                player.chunkPosition().x, player.chunkPosition().z);
        long started = System.nanoTime();
        var extraction = dev.nodera.mod.server.shadow.LiveSnapshotExtractor.extract(
                level, region, dev.nodera.core.state.SnapshotVersion.INITIAL,
                level.getGameTime());
        long millis = (System.nanoTime() - started) / 1_000_000L;

        List<Row> rows = new ArrayList<>();
        rows.add(Row.of(Cell.tr(CommandLang.EXTRACT_REGION, Semantic.HEADING,
                String.valueOf(region), millis)));
        rows.add(Row.of(Cell.tr(CommandLang.EXTRACT_CHUNKS, Semantic.NEUTRAL,
                extraction.snapshot().chunks().size(), extraction.missingChunks())));
        rows.add(Row.of(Cell.tr(CommandLang.EXTRACT_DENSE, Semantic.NEUTRAL,
                extraction.denseSections())));
        rows.add(Row.of(Cell.tr(CommandLang.EXTRACT_EXCLUDED, Semantic.NEUTRAL,
                extraction.excludedBlocks())));

        var lane = dev.nodera.mod.common.NoderaHost.entityLaneRuntime();
        if (lane != null) {
            lane.validation().currentSnapshot(region).ifPresentOrElse(committed -> {
                var report = new dev.nodera.shadow.InterferenceProbe()
                        .probe(committed, extraction.snapshot());
                rows.add(Row.of(Cell.tr(CommandLang.EXTRACT_DRIFT, Semantic.DEGRADED,
                        report.changedBlocks(), report.changedSections())));
            }, () -> rows.add(Row.of(
                    Cell.tr(CommandLang.EXTRACT_NO_COMMITTED, Semantic.SECONDARY))));
        }
        CommandTree.sendPanel(ctx.getSource(),
                Panel.titled(CommandLang.EXTRACT_TITLE, Semantic.HEADING, rows));
        return 1;
    }

    private static int relay(CommandContext<CommandSourceStack> ctx) {
        var lane = dev.nodera.mod.common.NoderaHost.entityLaneRelayMetrics();
        if (lane == null) {
            return CommandTree.fail(ctx, CommandLang.RELAY_NONE);
        }
        // The metric block itself is measured data from the lane, so it rides the pass-through
        // cell; only the heading is prose, and that comes from the lang file.
        CommandTree.sendPanel(ctx.getSource(), Panel.titled(CommandLang.RELAY_TITLE,
                Semantic.HEADING, List.of(Row.of(Cell.raw(lane.describe())))));
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> hudBranch(String name, Surface surface,
                                                                        Supplier<DiagnosticsService> service) {
        return literal(name)
                .then(literal("on").executes(setHud(service, surface, true)))
                .then(literal("off").executes(setHud(service, surface, false)));
    }

    private static Command<CommandSourceStack> setHud(Supplier<DiagnosticsService> service, Surface surface, boolean on) {
        return ctx -> {
            ServerPlayer p = ctx.getSource().getPlayer();
            DiagnosticsService svc = service.get();
            if (p == null || svc == null) {
                return CommandTree.fail(ctx, CommandLang.HUD_NOT_PLAYER);
            }
            svc.setPref(p.getUUID(), surface, on);
            CommandTree.reply(ctx, CommandLang.HUD_SET,
                    Component.translatable(CommandLang.hudSurfaceKey(surface.name())),
                    Component.translatable(on ? CommandLang.ON : CommandLang.OFF));
            return 1;
        };
    }
}
