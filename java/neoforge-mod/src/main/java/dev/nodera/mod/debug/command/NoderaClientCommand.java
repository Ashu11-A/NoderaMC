package dev.nodera.mod.debug.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.nodera.diagnostics.DiagnosticsCollector;
import dev.nodera.diagnostics.model.TelemetrySnapshot;
import dev.nodera.diagnostics.state.Semantic;
import dev.nodera.diagnostics.view.Cell;
import dev.nodera.diagnostics.view.Panel;
import dev.nodera.diagnostics.view.Row;
import dev.nodera.diagnostics.view.TorrentWorldListView;
import dev.nodera.diagnostics.view.ViewBuilder;
import dev.nodera.mod.common.NoderaPeerService;
import dev.nodera.peer.PeerRuntime;
import net.minecraft.commands.CommandSourceStack;
import com.mojang.brigadier.arguments.StringArgumentType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * The client {@code /noderac} command tree (Task 18) — registered via {@code RegisterClientCommandsEvent}
 * on the logical client only. Reads the <b>client</b> {@link PeerRuntime}'s own snapshot with no
 * server round-trip, so the player can see their own tx/rx + membership view even when the vanilla
 * server connection is down but the P2P mesh is alive (the continuity case).
 *
 * <p>The snapshot is taken at invocation time (no client tick loop needed). {@code zone} and
 * {@code hud} are deferred to the runClient acceptance pass: {@code zone} needs the local player's
 * world position, which under the layering rule (Task 0 §4.4) requires {@code net.minecraft.client.*}
 * code under {@code dev.nodera.mod.client}, out of scope while {@code runClient} is GUI-deferred.
 *
 * <p>Thread-context: runs on the client thread.
 */
public final class NoderaClientCommand {

    private NoderaClientCommand() {}

    /** Register the {@code /noderac} tree (called only on the client). */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        NoderaPeerService svc = NoderaPeerService.get();
        dispatcher.register(literal("noderac")
                .then(literal("session").executes(panel(svc, ViewBuilder::sessionPanel)))
                .then(literal("peers").executes(panel(svc, ViewBuilder::peersPanel)))
                .then(literal("worker").executes(NoderaClientCommand::worker))
                .then(literal("worlds").executes(NoderaClientCommand::worlds))
                .then(literal("net")
                        .executes(panel(svc, s -> ViewBuilder.netPanel(s, null)))
                        .then(argument("type", StringArgumentType.string())
                                .executes(ctx -> clientNetType(ctx, svc)))));
    }

    /** {@code /noderac worker} — the always-on worker's live state over the control channel. */
    private static int worker(CommandContext<CommandSourceStack> ctx) {
        if (!dev.nodera.mod.common.CompanionLink.isPresent()) {
            return CommandTree.fail(ctx, CommandLang.WORKER_ABSENT);
        }
        var info = dev.nodera.mod.common.CompanionLink.info();
        var state = dev.nodera.mod.common.CompanionLink.client().state().orElse(null);
        var hosted = dev.nodera.mod.common.WorkerStateParser.connectedWorlds(state);
        // MC-GUI-5: a panel of key-bearing cells, not a StringBuilder — the same Panel/Row/Cell
        // shape every other command renders through, so the words live in en_us.json.
        List<Row> rows = new ArrayList<>();
        rows.add(Row.of(Cell.tr(CommandLang.WORKER_LINKED, Semantic.HEALTHY,
                info.protocolVersion(), info.daemonVersion())));
        if (hosted.isEmpty()) {
            rows.add(Row.of(Cell.tr(CommandLang.WORKER_NO_WORLDS, Semantic.SECONDARY)));
        }
        for (var world : hosted) {
            rows.add(Row.of(
                    Cell.raw(world.name()),
                    TorrentWorldListView.playersCell(world.players(), Semantic.NEUTRAL),
                    world.mcRoute().isBlank()
                            ? Cell.tr(CommandLang.WORLD_GAME_CLOSED, Semantic.SECONDARY)
                            : Cell.tr(CommandLang.WORLD_JOINABLE_AT, Semantic.HEALTHY,
                                    world.mcRoute())));
        }
        CommandTree.sendPanel(ctx.getSource(),
                Panel.titled(CommandLang.WORKER_TITLE, Semantic.HEADING, rows));
        return 1;
    }

    /** {@code /noderac worlds} — the merged multiplayer world list, as the Worlds tab sees it. */
    private static int worlds(CommandContext<CommandSourceStack> ctx) {
        var entries = dev.nodera.mod.client.multiplayer.MultiplayerWorldFeed.snapshot();
        if (entries.isEmpty()) {
            return CommandTree.reply(ctx, CommandLang.WORLDS_NONE_KNOWN);
        }
        List<Row> rows = new ArrayList<>();
        for (var entry : entries) {
            List<Cell> cells = new ArrayList<>();
            cells.add(Cell.raw(entry.name()));
            if (entry.hasHost()) {
                cells.add(Cell.tr(CommandLang.WORLDS_BY_HOST, Semantic.SECONDARY, entry.hostName()));
            }
            cells.add(entry.playersCell());
            cells.add(entry.mcRoute().isBlank()
                    ? Cell.tr(TorrentWorldListView.healthKey(entry.health()), Semantic.SECONDARY)
                    : Cell.tr(CommandLang.WORLD_JOINABLE, Semantic.HEALTHY));
            rows.add(new Row(cells));
        }
        CommandTree.sendPanel(ctx.getSource(),
                Panel.titled(CommandLang.WORLDS_TITLE, Semantic.HEADING, rows));
        return entries.size();
    }

    /** Sample the client runtime now; null if the client peer is not running. */
    private static TelemetrySnapshot snapshot(NoderaPeerService svc) {
        PeerRuntime rt = svc.clientRuntime();
        DiagnosticsCollector c = svc.clientCollector();
        if (rt == null || c == null) {
            return null;
        }
        return c.sample(0L, System.nanoTime(), rt.nodeId(), rt.isBootstrap());
    }

    private static int clientNetType(CommandContext<CommandSourceStack> ctx, NoderaPeerService svc) {
        TelemetrySnapshot s = snapshot(svc);
        if (s == null) {
            return CommandTree.offline(ctx);
        }
        return CommandTree.sendPanel(ctx.getSource(), ViewBuilder.netPanel(s, StringArgumentType.getString(ctx, "type")));
    }

    private static Command<CommandSourceStack> panel(NoderaPeerService svc, Function<TelemetrySnapshot, Panel> fn) {
        return ctx -> {
            TelemetrySnapshot s = snapshot(svc);
            if (s == null) {
                return CommandTree.offline(ctx);
            }
            return CommandTree.sendPanel(ctx.getSource(), fn.apply(s));
        };
    }
}
