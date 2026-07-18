package dev.nodera.mod.debug.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.nodera.diagnostics.model.TelemetrySnapshot;
import dev.nodera.diagnostics.view.ViewBuilder;
import dev.nodera.mod.debug.DiagnosticsService;
import dev.nodera.mod.debug.DiagnosticsService.Surface;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

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
                .then(literal("debug").requires(s -> s.hasPermission(OP_LEVEL))
                        .then(literal("sample-rate")
                                .then(argument("n", IntegerArgumentType.integer(1, 100))
                                        .executes(ctx -> sampleRate(ctx, service))))
                        .then(literal("verbose")
                                .then(literal("on").executes(verboseStaged()))
                                .then(literal("off").executes(verboseStaged())))));
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
        ctx.getSource().sendSuccess(() -> Component.literal(
                "whois for " + target.getName().getString()
                        + ": cross-player report is staged (enable via /nodera debug)"), false);
        return 1;
    }

    private static int sampleRate(CommandContext<CommandSourceStack> ctx, Supplier<DiagnosticsService> service) {
        int n = IntegerArgumentType.getInteger(ctx, "n");
        DiagnosticsService svc = service.get();
        if (svc != null) {
            svc.setSampleTicks(n);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("sample rate = " + n + " ticks"), false);
        return n;
    }

    /** {@code /nodera debug verbose} — staged: the surface ships now, finer logging lands with it. */
    private static Command<CommandSourceStack> verboseStaged() {
        return ctx -> {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "verbose logging is staged (ships when richer diagnostics logging lands)"), false);
            return 1;
        };
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
                ctx.getSource().sendFailure(Component.literal("Run this as a player with the HUD online."));
                return 0;
            }
            svc.setPref(p.getUUID(), surface, on);
            String label = surface == Surface.ALL ? "all surfaces" : surface.name().toLowerCase(java.util.Locale.ROOT);
            ctx.getSource().sendSuccess(() -> Component.literal("HUD " + label + " " + (on ? "on" : "off")), false);
            return 1;
        };
    }
}
