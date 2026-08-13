package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.HarnessException;
import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.LogWatcher;
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

    /**
     * The selftest's machine-readable summary, which lives in the SERVER LOG and not in the reply.
     * {@code SelfTest} keeps this exact spelling untranslated for harnesses; the player's reply is a
     * lang key over the same numbers and reads "Selftest complete: …".
     */
    private static final String SELFTEST_SUMMARY = "SELFTEST complete:";

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
            // THE COUNTS COME FROM THE LOG, NOT FROM THE REPLY. SelfTest emits two shapes on
            // purpose (SelfTest.java:179-191, MC-GUI-5): the player's reply is
            // Component.translatable and reads "Selftest complete: 62 ok, 0 zero, 0 syntax
            // error(s), …", while the log keeps the machine-readable
            // "SELFTEST complete: … syntaxErr=0 exception=0" precisely so a harness can grep it.
            // This stage used to grep the RCON reply for the log's wording, so it failed on a
            // selftest that had in fact completed with zero of everything (run 31401507964).
            LogWatcher serverLog = context.log("server.log");

            int mark = serverLog.lineCount();
            String selftest = rcon.require("nodera selftest");
            HostWorldSupport.transcript(context, "commands-console.log",
                    "=== console: /nodera selftest\n" + selftest + "\n");
            String summary = awaitSelftestSummary(context, serverLog, mark,
                    "the selftest never completed");
            context.checkContains(summary, "syntaxErr=0", "the selftest hit syntax errors");
            context.checkContains(summary, "exception=0", "the selftest hit exceptions");

            int fullMark = serverLog.lineCount();
            String full = rcon.require("nodera selftest full");
            HostWorldSupport.transcript(context, "commands-console.log",
                    "=== console: /nodera selftest full\n" + full + "\n");
            String fullSummary = awaitSelftestSummary(context, serverLog, fullMark,
                    "selftest full never completed");
            context.checkContains(fullSummary, "exception=0", "selftest full hit exceptions");

            Path reports = stack.paths().gameDir("run").resolve("world").resolve("nodera-selftest");
            context.check(hasSelftestReport(reports),
                    "no selftest report persisted under " + reports);
        });

        context.stage("K4", "every artefact of the run is collected", stack::collectWorkerState);
    }

    /**
     * The machine-readable {@code SELFTEST complete:} line the run just produced.
     *
     * <p>RCON returns as soon as the command's reply is sent, and {@code SelfTest} logs its summary
     * on the same thread immediately before that reply — but the log file is a different stream, so
     * the line can lag the reply by a flush. Waiting for it is not a guess about timing: the run is
     * over by the time we look, so the only thing the wait absorbs is the write.
     *
     * @param mark    a {@link LogWatcher#lineCount()} taken before the command was sent, so an
     *                earlier selftest in the same run cannot satisfy this one.
     * @param because what to say if it never arrives.
     * @return the summary line, or the empty string when it did not appear (the stage is already
     *         failed by then, and returning empty lets the count checks report as well).
     */
    private static String awaitSelftestSummary(
            ScenarioContext context, LogWatcher serverLog, int mark, String because) {
        if (!serverLog.pollFor(SELFTEST_SUMMARY, Duration.ofSeconds(60), mark)) {
            context.check(false, because + " — no '" + SELFTEST_SUMMARY + "' in "
                    + serverLog.file() + " after line " + mark);
            return "";
        }
        return serverLog.lastMatchAfter(SELFTEST_SUMMARY, mark).orElse("");
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
        // The HUD reply is COMPOSED, and the needles below used to assume it was not. The command
        // answers `nodera.cmd.hud.set` = "HUD %s %s", where the first argument is the surface's own
        // translated phrase (`nodera.cmd.hud.surface.<name>`) and the second is `nodera.cmd.state.
        // {on,off}` — see NoderaCommand.setHud. So the sub-command word is NOT what comes back:
        // `hud tab` answers "HUD tab list", `hud bars` answers "HUD boss bars", `hud alerts`
        // answers "HUD zone alerts". Four of these five needles were written against the
        // pre-translation reply, which was assembled in English at the call site (#113 moved it),
        // and `expect` is a case-insensitive CONTAINS — "HUD tab list off" does not contain
        // "hud tab off", so each was a real failure waiting for the first live run of this leg.
        // Only "hud all" survived, by being short enough to remain a prefix of "HUD all surfaces".
        // Recorded as one block because they fail one at a time: fixing only the one issue #258
        // names buys exactly one more live run before the next identical failure.
        expect(context, run(context, rcon, player, "nodera hud tab off"), "HUD tab list off");
        expect(context, run(context, rcon, player, "nodera hud tab on"), "HUD tab list on");
        expect(context, run(context, rcon, player, "nodera hud bars on"), "HUD boss bars on");
        expect(context, run(context, rcon, player, "nodera hud alerts on"), "HUD zone alerts on");
        expect(context, run(context, rcon, player, "nodera hud all on"), "HUD all surfaces on");
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
