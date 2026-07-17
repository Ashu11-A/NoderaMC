package dev.nodera.mod.debug.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.nodera.diagnostics.DiagnosticsCollector;
import dev.nodera.diagnostics.model.TelemetrySnapshot;
import dev.nodera.diagnostics.view.Panel;
import dev.nodera.diagnostics.view.ViewBuilder;
import dev.nodera.mod.common.NoderaPeerService;
import dev.nodera.peer.PeerRuntime;
import net.minecraft.commands.CommandSourceStack;
import com.mojang.brigadier.arguments.StringArgumentType;

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
                .then(literal("net")
                        .executes(panel(svc, s -> ViewBuilder.netPanel(s, null)))
                        .then(argument("type", StringArgumentType.string())
                                .executes(ctx -> clientNetType(ctx, svc)))));
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
