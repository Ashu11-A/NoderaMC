package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.HarnessException;
import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.mc.RconClient;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Two players against every {@code /nodera} command, on a neutral dedicated server.
 *
 * <ol>
 *   <li>K0/K1 the standard topology plus a clean-slate dedicated server with RCON; JoinerDev and
 *       JoinerTwo both join and the entity lane goes live.</li>
 *   <li>K2 each player executes every read-surface {@code /nodera} command — driven with
 *       {@code execute as <player> at <player> run …} so the command runs on the player, not on the
 *       console — and the response text is captured and validated per command.</li>
 *   <li>K2b the extractor reads the REAL world: four blocks the consensus palette cannot express are
 *       placed, and the extraction's palette-exclusion counter must move by exactly four.</li>
 *   <li>K3 the in-game suite: {@code /nodera selftest} walks the whole Brigadier tree as each
 *       player, benchmarks, and persists JSON+MD reports; it must complete with zero syntax errors
 *       and zero exceptions. Then {@code /nodera selftest full}, which adds the op/deop grant
 *       lane.</li>
 *   <li>K4 every transcript, report and worker state document is collected.</li>
 * </ol>
 *
 * <p>Thread-context: run on the runner's thread; stateless between runs.
 */
public final class CommandsScenario implements Scenario {

    /** {@code blocks outside the palette: N} — the one number a script can move by an exact amount. */
    private static final Pattern EXCLUDED =
            Pattern.compile("blocks outside the palette: (\\d+)");

    @Override
    public String id() {
        return "commands";
    }

    @Override
    public String title() {
        return "every /nodera command answers correctly for two players in one world";
    }

    @Override
    public Set<String> tags() {
        return Set.of("live", "commands");
    }

    @Override
    public Requirements requirements() {
        return Requirements.liveClients(5);
    }

    @Override
    public void run(ScenarioContext context) throws Exception {
        LiveStack stack = context.stack();
        RconClient rcon = stack.rcon();

        context.stage("K0", "infrastructure and every peer are ready", () ->
                HostWorldSupport.probeWorkers(context));

        context.stage("K1", "the dedicated server hosts JoinerDev and JoinerTwo, lane live", () -> {
            HostWorldSupport.dedicatedTwoPlayers(context);
            context.settle(Duration.ofSeconds(5));  // let the FOV plan + diagnostics sampling settle
        });

        for (String player : List.of("JoinerDev", "JoinerTwo")) {
            String other = player.equals("JoinerDev") ? "JoinerTwo" : "JoinerDev";
            context.stage("K2." + player, "the full command sweep answers as " + player, () -> {
                sweep(context, rcon, player, other);
            });
        }

        context.stage("K2b", "the extractor counts exactly the blocks the palette cannot express",
                () -> {
            // Spectator so the player neither falls nor dies while parked; y=200 is above any
            // terrain, so the target section is uniform air before the drive and everything counted
            // in it is the drive's. Absolute coordinates on purpose — `~` offsets from a moving
            // player are not a fixed target.
            rcon.require("gamemode spectator JoinerDev");
            rcon.teleport("JoinerDev", 64, 200, 64);
            context.settle(Duration.ofSeconds(6));

            int before = extractExcluded(context, rcon);
            // A diamond block has no consensus id, so each one placed adds exactly one excluded
            // block. Anything vaguer (dense sections, chunk counts) depends on terrain and would
            // assert noise.
            for (int x = 64; x <= 67; x++) {
                rcon.require("setblock " + x + " 202 64 minecraft:diamond_block replace");
            }
            context.settle(Duration.ofSeconds(4));

            int after = extractExcluded(context, rcon);
            int delta = after - before;
            context.check(delta == 4, "expected exactly 4 newly excluded blocks, the extraction "
                    + "moved by " + delta + " (" + before + " -> " + after + ")");
            rcon.require("gamemode survival JoinerDev");
        });

        context.stage("K3", "/nodera selftest and /nodera selftest full complete with no syntax "
                + "errors and no exceptions", () -> {
            String selftest = rcon.require("nodera selftest");
            HostWorldSupport.transcript(context, "commands-console.log",
                    "=== console: /nodera selftest\n" + selftest + "\n");
            context.checkContains(selftest, "SELFTEST complete:", "the selftest never completed");
            context.checkContains(selftest, "syntaxErr=0", "the selftest hit syntax errors");
            context.checkContains(selftest, "exception=0", "the selftest hit exceptions");

            String full = rcon.require("nodera selftest full");
            HostWorldSupport.transcript(context, "commands-console.log",
                    "=== console: /nodera selftest full\n" + full + "\n");
            context.checkContains(full, "SELFTEST complete:", "selftest full never completed");
            context.checkContains(full, "exception=0", "selftest full hit exceptions");

            Path reports = stack.paths().gameDir("run").resolve("world").resolve("nodera-selftest");
            context.check(hasSelftestReport(reports),
                    "no selftest report persisted under " + reports);
        });

        context.stage("K4", "every artefact of the run is collected", stack::collectWorkerState);
    }

    /**
     * Every read-surface command, in the order the shell suite drove them.
     *
     * <p>The expectations are deliberately the smallest fragment that proves the command answered
     * with its own panel rather than with a usage error: {@code epoch} for the session panel,
     * {@code tx} for the network counters, and so on. A stricter match would fail on a formatting
     * change that broke nothing.
     */
    private void sweep(ScenarioContext context, RconClient rcon, String player, String other) {
        expect(context, run(context, rcon, player, "nodera session"), "epoch");
        expect(context, run(context, rcon, player, "nodera status"), "epoch");   // alias
        expect(context, run(context, rcon, player, "nodera peers"), "peers");
        expect(context, run(context, rcon, player, "nodera net"), "tx");
        expect(context, run(context, rcon, player, "nodera net entity"), "tx");
        expect(context, run(context, rcon, player, "nodera regions"), "owned");
        expect(context, run(context, rcon, player, "nodera zone"), "region");
        expect(context, run(context, rcon, player, "nodera zone"), "state");
        expect(context, run(context, rcon, player, "nodera entities"), "total");
        requireAnswer(context, run(context, rcon, player, "nodera health"), "nodera health");
        requireAnswer(context, run(context, rcon, player, "nodera server"), "nodera server");
        expect(context, run(context, rcon, player, "nodera worlds"), "worlds");
        expect(context, run(context, rcon, player, "nodera share status"), "Sharing: yes");
        expect(context, run(context, rcon, player, "nodera whois " + other), "whois for " + other);
        expect(context, run(context, rcon, player, "nodera hud tab off"), "hud tab off");
        expect(context, run(context, rcon, player, "nodera hud tab on"), "hud tab on");
        expect(context, run(context, rcon, player, "nodera hud bars on"), "hud bars on");
        expect(context, run(context, rcon, player, "nodera hud alerts on"), "hud alerts on");
        expect(context, run(context, rcon, player, "nodera hud all on"), "hud all");
        expect(context, run(context, rcon, player, "nodera debug sample-rate 20"),
                "sample rate = 20");
        expect(context, run(context, rcon, player, "nodera debug verbose on"), "debug console ON");
        expect(context, run(context, rcon, player, "nodera debug verbose off"), "debug console OFF");
        requireAnswer(context, run(context, rcon, player, "nodera debug relay"),
                "nodera debug relay");
        expect(context, run(context, rcon, player, "nodera debug capture"), "edit(s) seen");
        expect(context, run(context, rcon, player, "nodera debug extract"),
                "blocks outside the palette");
        expect(context, run(context, rcon, player, "tps"), "TPS:");
    }

    /** Execute one command as the player, and transcript the response whatever it was. */
    private String run(ScenarioContext context, RconClient rcon, String player, String command) {
        String response = rcon.send("execute as " + player + " at " + player + " run " + command)
                .orElse("");
        HostWorldSupport.transcript(context, "commands-" + player + ".log",
                "=== " + player + ": /" + command + "\n" + response + "\n");
        return response;
    }

    /** The response must contain the needle, case-insensitively. */
    private void expect(ScenarioContext context, String response, String needle) {
        if (!response.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
            context.fail("expected '" + needle + "' in the response — got: "
                    + response.substring(0, Math.min(300, response.length())));
        }
    }

    /** Some commands have no fixed phrase; for those, silence is the failure. */
    private void requireAnswer(ScenarioContext context, String response, String command) {
        context.check(!response.isBlank(), "/" + command + " returned nothing");
    }

    /** The palette-exclusion count of one live extraction, transcripted as it is read. */
    private int extractExcluded(ScenarioContext context, RconClient rcon) {
        String out = rcon.send("execute as JoinerDev at JoinerDev run nodera debug extract")
                .orElse("");
        HostWorldSupport.transcript(context, "commands-console.log", "=== extract\n" + out + "\n");
        Matcher matcher = EXCLUDED.matcher(out);
        if (!matcher.find()) {
            throw new HarnessException(
                    "/nodera debug extract reported no palette-exclusion count: " + out);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private boolean hasSelftestReport(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString())
                    .anyMatch(name -> name.startsWith("selftest-") && name.endsWith(".json"));
        } catch (Exception unreadable) {
            return false;
        }
    }
}
