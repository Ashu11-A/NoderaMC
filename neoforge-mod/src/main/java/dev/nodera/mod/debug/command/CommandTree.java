package dev.nodera.mod.debug.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.nodera.diagnostics.model.TelemetrySnapshot;
import dev.nodera.diagnostics.view.DiagnosticsView;
import dev.nodera.diagnostics.view.Panel;
import dev.nodera.mod.debug.render.ComponentRenderer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The declarative-command helpers (Task 18): every {@code /nodera} and {@code /noderac} subcommand
 * is a one-liner whose executor produces a {@link Panel} or {@link DiagnosticsView}; this class
 * uniformly renders it via {@link ComponentRenderer} and sends it to the source. So command output
 * is a uniform, colour-coded table by construction, and permission gating + the "offline" guard
 * live in exactly one place.
 *
 * <p>Thread-context: executors run on the server main thread (server) or client thread (client).
 */
public final class CommandTree {

    private CommandTree() {}

    /** @return an executor that renders {@code fn(snapshot)} as a single panel. */
    public static Command<CommandSourceStack> panel(Supplier<TelemetrySnapshot> snap,
                                                    Function<TelemetrySnapshot, Panel> fn) {
        return ctx -> {
            TelemetrySnapshot s = snap.get();
            if (s == null) {
                return offline(ctx);
            }
            return sendPanel(ctx.getSource(), fn.apply(s));
        };
    }

    /** @return an executor that renders {@code fn(snapshot)} as a full view. */
    public static Command<CommandSourceStack> view(Supplier<TelemetrySnapshot> snap,
                                                   Function<TelemetrySnapshot, DiagnosticsView> fn) {
        return ctx -> {
            TelemetrySnapshot s = snap.get();
            if (s == null) {
                return offline(ctx);
            }
            return sendView(ctx.getSource(), fn.apply(s));
        };
    }

    /** Render + send one panel; returns the row count (brigadier result). */
    public static int sendPanel(CommandSourceStack src, Panel panel) {
        src.sendSuccess(() -> ComponentRenderer.renderPanel(panel), false);
        return panel.rows().size();
    }

    /** Render + send a whole view; returns the panel count (brigadier result). */
    public static int sendView(CommandSourceStack src, DiagnosticsView view) {
        src.sendSuccess(() -> ComponentRenderer.renderView(view), false);
        return view.panels().size();
    }

    /** The "diagnostics offline" guard (server runtime not started / no snapshot yet). */
    public static int offline(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("Nodera: diagnostics offline"), false);
        return 0;
    }
}
