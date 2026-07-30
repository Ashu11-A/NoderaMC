package dev.nodera.mod.debug.command;

import dev.nodera.endpoint.lang.CommandLang;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /nodera selftest [full]} — the in-game command test + benchmark drive.
 *
 * <p>Walks the live Brigadier tree under the {@code nodera} and {@code tps} roots (plus the
 * {@code op}/{@code deop} alias roots in {@code full} mode), synthesizes concrete argument values,
 * and executes every reachable leaf <em>as each connected player</em> against a capturing command
 * source — so the suite exercises exactly what a player typing in chat would hit, and records the
 * exact response text each command produced. Each read-only command is executed {@value #BENCH_RUNS}
 * times and timed (min/avg/max nanos): the command benchmark.
 *
 * <p>Results are persisted for offline analysis under the world save:
 * {@code <world>/nodera-selftest/selftest-<stamp>.json} (machine-readable) and {@code .md}
 * (human-readable). A single {@code SELFTEST complete:} log line summarizes the run for scripted
 * harnesses ({@code scripts/e2e-commands.sh} greps it).
 *
 * <p>Deliberately excluded by default (session-destroying or permission-mutating):
 * {@code share} / {@code share stop} (would flip the host lane under the joiners) and the
 * {@code op}/{@code deop} grant lane ({@code full} includes the grant lane; nothing ever runs
 * {@code share stop}). {@code selftest} itself is excluded to avoid recursion.
 *
 * <p>Thread-context: runs synchronously on the server main thread (commands are main-thread-bound);
 * a full run is a few hundred milliseconds plus one tracker round-trip per {@code worlds} run.
 */
public final class SelfTest {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaSelfTest");

    /** Timed iterations per benchable command. */
    private static final int BENCH_RUNS = 3;

    /** Command prefixes never executed (recursion / session-destroying). */
    private static final List<String> ALWAYS_SKIP = List.of("nodera selftest", "nodera share stop");

    /** Exact commands never executed by default; {@code full} lifts these. */
    private static final List<String> FULL_ONLY_PREFIXES =
            List.of("nodera op", "nodera deop", "op", "deop");

    /** Mutating toggles run once (still recorded, never benchmark-looped). */
    private static final List<String> RUN_ONCE_MARKERS =
            List.of(" hud ", " verbose ", " sample-rate ", "op ", "deop ", "share");

    private SelfTest() {}

    /** One executed (or skipped) command and its outcome. */
    private record Result(String player, String command, String status, int result,
                          int iterations, long nanosMin, long nanosAvg, long nanosMax,
                          List<String> response, String error) {}

    /** A capturing chat sink: everything the command sends to its source lands here. */
    private static final class Capture implements CommandSource {
        final List<String> lines = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component component) {
            lines.add(component.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }

    /** Executor for {@code /nodera selftest [full]}. Returns the OK-command count. */
    public static int run(CommandContext<CommandSourceStack> ctx, boolean full) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        List<ServerPlayer> players = List.copyOf(server.getPlayerList().getPlayers());
        if (players.isEmpty()) {
            src.sendFailure(Component.translatable(CommandLang.SELFTEST_NO_PLAYERS));
            return 0;
        }

        List<String> roots = new ArrayList<>(List.of("nodera", "tps"));
        if (full) {
            roots.addAll(List.of("op", "deop"));
        }
        List<String> commands = new ArrayList<>();
        for (String root : roots) {
            CommandNode<CommandSourceStack> node = dispatcher.getRoot().getChild(root);
            if (node != null) {
                collect(node, root, commands);
            }
        }

        long startedNanos = System.nanoTime();
        String stamp = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        List<Result> results = new ArrayList<>();
        for (ServerPlayer player : players) {
            for (String template : commands) {
                results.add(execute(server, player, players, template, full));
            }
            // The hud on/off walk leaves every surface off; restore the all-on default.
            var svc = dev.nodera.mod.common.NoderaPeerService.get().serverDiagnostics();
            if (svc != null) {
                svc.setPref(player.getUUID(), dev.nodera.mod.debug.DiagnosticsService.Surface.ALL, true);
            }
        }
        long totalMs = (System.nanoTime() - startedNanos) / 1_000_000;

        int ok = 0;
        int zero = 0;
        int syntax = 0;
        int thrown = 0;
        int skipped = 0;
        for (Result r : results) {
            switch (r.status) {
                case "OK" -> ok++;
                case "ZERO" -> zero++;
                case "SYNTAX_ERR" -> syntax++;
                case "EXCEPTION" -> thrown++;
                default -> skipped++;
            }
        }

        double tps = dev.nodera.mod.debug.render.BossBarManager.currentTps(server);
        double avgTickMs = server.getAverageTickTimeNanos() / 1_000_000.0;
        var lane = dev.nodera.mod.common.NoderaHost.entityLaneRelayMetrics();
        String relay = lane == null ? null : lane.describe();

        Path reportDir = server.getWorldPath(LevelResource.ROOT).resolve("nodera-selftest");
        Path jsonPath = reportDir.resolve("selftest-" + stamp + ".json");
        Path mdPath = reportDir.resolve("selftest-" + stamp + ".md");
        try {
            Files.createDirectories(reportDir);
            Files.writeString(jsonPath,
                    toJson(stamp, full, totalMs, tps, avgTickMs, relay, players, results,
                            ok, zero, syntax, thrown, skipped),
                    StandardCharsets.UTF_8);
            Files.writeString(mdPath,
                    toMarkdown(stamp, full, totalMs, tps, avgTickMs, relay, results,
                            ok, zero, syntax, thrown, skipped),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("SELFTEST report write failed: {}", e.toString());
            src.sendFailure(Component.translatable(CommandLang.SELFTEST_WRITE_FAILED,
                    String.valueOf(e.getMessage())));
        }

        // Two audiences, two shapes (MC-GUI-5). The log line keeps its exact machine-readable
        // form — scripts/e2e-commands.sh greps "SELFTEST complete:" and would silently stop
        // matching if it were translated. What the player reads is a lang key over the same
        // numbers, so it can be translated without breaking any harness.
        String summary = String.format(Locale.ROOT,
                "SELFTEST complete: ok=%d zero=%d syntaxErr=%d exception=%d skipped=%d "
                        + "commands=%d players=%d durationMs=%d tps=%.1f report=%s",
                ok, zero, syntax, thrown, skipped, commands.size(), players.size(), totalMs, tps,
                jsonPath);
        LOG.info(summary);
        Object[] counts = {ok, zero, syntax, thrown, skipped, commands.size(), players.size(),
                totalMs, jsonPath.toString()};
        src.sendSuccess(() -> Component.translatable(CommandLang.SELFTEST_SUMMARY, counts), true);
        return ok;
    }

    /**
     * Depth-first walk: every node with an executor yields one concrete command line; argument
     * nodes are replaced by a synthesized placeholder token ({@code <player>}, {@code <type>},
     * {@code <n>}) resolved per-execution.
     */
    private static void collect(CommandNode<CommandSourceStack> node, String path, List<String> out) {
        if (node.getCommand() != null) {
            out.add(path);
        }
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            String token;
            if (child instanceof LiteralCommandNode<CommandSourceStack> lit) {
                token = lit.getLiteral();
            } else if (child instanceof ArgumentCommandNode<?, ?> arg) {
                token = "<" + arg.getName() + ">";
            } else {
                continue;
            }
            collect(child, path + " " + token, out);
        }
    }

    /** Execute one command template as one player (or record why it was skipped). */
    private static Result execute(MinecraftServer server, ServerPlayer player,
                                  List<ServerPlayer> players, String template, boolean full) {
        String command = resolvePlaceholders(template, player, players);
        if (command == null) {
            return new Result(name(player), template, "SKIPPED", 0, 0, 0, 0, 0,
                    List.of(), "no synthesized value for an argument");
        }
        for (String prefix : ALWAYS_SKIP) {
            if (command.startsWith(prefix)) {
                return new Result(name(player), command, "SKIPPED", 0, 0, 0, 0, 0,
                        List.of(), "excluded (session-destroying or recursive)");
            }
        }
        // "nodera share" exactly starts the host lane — only its read-only "status" leaf runs.
        if (command.equals("nodera share")) {
            return new Result(name(player), command, "SKIPPED", 0, 0, 0, 0, 0,
                    List.of(), "excluded (mutates the host lane; share status covers the read)");
        }
        if (!full) {
            for (String prefix : FULL_ONLY_PREFIXES) {
                if (command.equals(prefix) || command.startsWith(prefix + " ")) {
                    return new Result(name(player), command, "SKIPPED", 0, 0, 0, 0, 0,
                            List.of(), "grant lane runs only in 'selftest full'");
                }
            }
        }

        int runs = BENCH_RUNS;
        String padded = " " + command + " ";
        for (String marker : RUN_ONCE_MARKERS) {
            if (padded.contains(marker)) {
                runs = 1;
                break;
            }
        }

        Capture capture = new Capture();
        CommandSourceStack source = new CommandSourceStack(capture, player.position(),
                player.getRotationVector(), player.serverLevel(), 4,
                name(player), player.getDisplayName(), server, player);
        long min = Long.MAX_VALUE;
        long max = 0;
        long total = 0;
        int result = 0;
        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            try {
                result = server.getCommands().getDispatcher().execute(command, source);
            } catch (CommandSyntaxException e) {
                return new Result(name(player), command, "SYNTAX_ERR", 0, i + 1, 0, 0, 0,
                        capture.lines, e.getMessage());
            } catch (Exception e) {
                LOG.warn("SELFTEST '{}' as {} threw", command, name(player), e);
                return new Result(name(player), command, "EXCEPTION", 0, i + 1, 0, 0, 0,
                        capture.lines, e.toString());
            }
            long elapsed = System.nanoTime() - t0;
            min = Math.min(min, elapsed);
            max = Math.max(max, elapsed);
            total += elapsed;
        }
        return new Result(name(player), command, result > 0 ? "OK" : "ZERO", result, runs,
                min, total / runs, max, capture.lines, null);
    }

    /** Concrete values for the synthesized argument tokens; null = cannot synthesize. */
    private static String resolvePlaceholders(String template, ServerPlayer executor,
                                              List<ServerPlayer> players) {
        String command = template;
        if (command.contains("<player>")) {
            // Prefer the OTHER player so whois/op exercise the cross-player path.
            ServerPlayer target = players.stream()
                    .filter(p -> !p.getUUID().equals(executor.getUUID()))
                    .findFirst().orElse(executor);
            command = command.replace("<player>", name(target));
        }
        command = command.replace("<type>", "entity");
        command = command.replace("<n>", "20");
        return command.contains("<") ? null : command;
    }

    private static String name(ServerPlayer p) {
        return p.getGameProfile().getName();
    }

    // ---- report rendering -----------------------------------------------------------------

    private static String toJson(String stamp, boolean full, long totalMs, double tps,
                                 double avgTickMs, String relay, List<ServerPlayer> players,
                                 List<Result> results, int ok, int zero, int syntax, int thrown,
                                 int skipped) {
        StringBuilder b = new StringBuilder(8192);
        b.append("{\n");
        b.append("  \"stamp\": ").append(quote(stamp)).append(",\n");
        b.append("  \"mode\": ").append(quote(full ? "full" : "default")).append(",\n");
        b.append("  \"durationMs\": ").append(totalMs).append(",\n");
        b.append("  \"tps\": ").append(String.format(Locale.ROOT, "%.2f", tps)).append(",\n");
        b.append("  \"avgTickMs\": ").append(String.format(Locale.ROOT, "%.3f", avgTickMs))
                .append(",\n");
        b.append("  \"relay\": ").append(relay == null ? "null" : quote(relay)).append(",\n");
        b.append("  \"players\": [");
        for (int i = 0; i < players.size(); i++) {
            b.append(i == 0 ? "" : ", ").append(quote(name(players.get(i))));
        }
        b.append("],\n");
        b.append("  \"summary\": {\"ok\": ").append(ok).append(", \"zero\": ").append(zero)
                .append(", \"syntaxErr\": ").append(syntax).append(", \"exception\": ")
                .append(thrown).append(", \"skipped\": ").append(skipped).append("},\n");
        b.append("  \"results\": [\n");
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            b.append("    {\"player\": ").append(quote(r.player))
                    .append(", \"command\": ").append(quote(r.command))
                    .append(", \"status\": ").append(quote(r.status))
                    .append(", \"result\": ").append(r.result)
                    .append(", \"iterations\": ").append(r.iterations)
                    .append(", \"nanosMin\": ").append(r.nanosMin == Long.MAX_VALUE ? 0 : r.nanosMin)
                    .append(", \"nanosAvg\": ").append(r.nanosAvg)
                    .append(", \"nanosMax\": ").append(r.nanosMax)
                    .append(", \"error\": ").append(r.error == null ? "null" : quote(r.error))
                    .append(", \"response\": [");
            for (int j = 0; j < r.response.size(); j++) {
                b.append(j == 0 ? "" : ", ").append(quote(r.response.get(j)));
            }
            b.append("]}").append(i == results.size() - 1 ? "\n" : ",\n");
        }
        b.append("  ]\n}\n");
        return b.toString();
    }

    private static String toMarkdown(String stamp, boolean full, long totalMs, double tps,
                                     double avgTickMs, String relay, List<Result> results,
                                     int ok, int zero, int syntax, int thrown, int skipped) {
        StringBuilder b = new StringBuilder(8192);
        b.append("# Nodera selftest ").append(stamp).append("\n\n");
        b.append("- mode: ").append(full ? "full" : "default").append('\n');
        b.append("- duration: ").append(totalMs).append(" ms\n");
        b.append(String.format(Locale.ROOT, "- TPS: %.1f (avg tick %.2f ms)%n", tps, avgTickMs));
        b.append("- summary: ok=").append(ok).append(" zero=").append(zero)
                .append(" syntaxErr=").append(syntax).append(" exception=").append(thrown)
                .append(" skipped=").append(skipped).append("\n\n");
        if (relay != null) {
            b.append("## Relay metrics\n\n```\n").append(relay).append("\n```\n\n");
        }
        b.append("## Commands\n\n");
        b.append("| player | command | status | result | avg ms | response |\n");
        b.append("|---|---|---|---|---|---|\n");
        for (Result r : results) {
            String response = r.error != null ? r.error : String.join(" ⏎ ", r.response);
            b.append("| ").append(r.player)
                    .append(" | `").append(r.command)
                    .append("` | ").append(r.status)
                    .append(" | ").append(r.result)
                    .append(" | ").append(String.format(Locale.ROOT, "%.3f", r.nanosAvg / 1e6))
                    .append(" | ").append(response.replace("|", "\\|").replace("\n", " ⏎ "))
                    .append(" |\n");
        }
        return b.toString();
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
