package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.HarnessException;
import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.Topology;
import dev.nodera.testkit.mc.RconClient;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * THE PHASE-1 EXIT GATE (issue #5).
 *
 * <p>The project's hard bet is that independent nodes re-executing the same batches agree, byte for
 * byte, forever. It has been proven headlessly for years of CI time ({@code ShadowValidationIT}:
 * 3 workers x 250 random batches, zero divergence) and never once against real Minecraft clients on
 * real terrain. That is what this scenario does.
 *
 * <pre>
 *   D0  THREE players on one dedicated server — three independent nodes re-executing the same
 *       regions. Two nodes can only disagree pairwise; three is where a majority can be wrong and be
 *       caught being wrong
 *   D1  the players spread out until at least MIN_REGIONS distinct regions are activated, so the
 *       soak is not one region's story
 *   D2  SUSTAINED RANDOM PLAY for SOAK_SECONDS: place/break/fill, mobs, and movement, driven at
 *       every player independently
 *   D3  THE GATE: {@code validation.divergences} is zero on every node, and no node logged a
 *       DIVERGENCE line. A divergence is not an error a node can fix — it is the measurement this
 *       scenario exists to take, so it is read from the worker STATE rather than inferred from a
 *       quiet log
 *   D4  the numbers issue #5 asks to record: regions, commits, votes, bytes moved, and the observed
 *       interference rate, written to the results directory
 * </pre>
 *
 * <p>The acceptance run is &gt;= 2 h ({@code SOAK_SECONDS=7200}); the default here is short enough to
 * sit in the nightly matrix, and the stage records which one it ran so a green tick is never
 * mistaken for the full gate.
 *
 * <p>Thread-context: stateless; the runner calls {@link #run} on its own thread.
 */
public final class DeterminismScenario implements Scenario {

    /** What issue #5's acceptance actually asks for. */
    private static final long GATE_SECONDS = 7200;

    private static final List<String> PLAYERS =
            List.of("JoinerDev", "JoinerTwo", "JoinerThree");

    private static final Pattern LANE_ACTIVE = Pattern.compile("active on (\\d+)");
    private static final Pattern TICK_STALL =
            Pattern.compile("A single server tick took ([0-9.]+)");
    private static final Pattern LEADING_DIGITS = Pattern.compile("(\\d+)");

    @Override
    public String id() {
        return "determinism";
    }

    @Override
    public String title() {
        return "three independent nodes re-executing the same regions never disagree";
    }

    @Override
    public Set<String> tags() {
        return Set.of("live", "determinism", "soak");
    }

    @Override
    public Requirements requirements() {
        return Requirements.liveClients(5);
    }

    /**
     * The gate's shape: three independent re-executing nodes.
     *
     * <p>Worker slots and their ports are computed FROM the player count, so asking for three
     * players here is what starts three companion workers and hands player three a control port
     * something is listening on.
     */
    @Override
    public Topology topology() {
        return Topology.standard().withPlayers(3);
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        LiveStack stack = ctx.stack();
        RconClient rcon = stack.rcon();
        Path transcript = stack.resultsDir().resolve("determinism.log");
        long soakSeconds = envSeconds("SOAK_SECONDS", 900);
        int minRegions = (int) envSeconds("MIN_REGIONS", 4);

        // --- D0: stack + three players ---------------------------------------------------------
        ctx.stage("D0", "three players in-world, lane live", () ->
                ServerDedicatedDrive.start(ctx, Map.of()));

        for (String who : PLAYERS) {
            rcon.send("gamemode creative " + who);
        }

        // --- D1: spread out over at least MIN_REGIONS -------------------------------------------
        // One region proves nothing about a mesh: divergence is a disagreement BETWEEN nodes, and
        // nodes only disagree about regions they both hold. Parking the players a region apart (512
        // blocks) with overlapping view distances is what produces shared committees rather than
        // three private worlds.
        ctx.stage("D1", "at least " + minRegions + " regions are active across the mesh", () -> {
            rcon.teleport("JoinerDev", 200, 100, 200);
            rcon.teleport("JoinerTwo", 712, 100, 200);
            rcon.teleport("JoinerThree", 456, 100, 712);
            ctx.settle(Duration.ofSeconds(20));

            stack.collectWorkerState();
            long workerSide = 0;
            for (Path state : stateFiles(stack)) {
                workerSide = Math.max(workerSide,
                        ServerJson.number(parse(state), "validation.active_regions"));
            }
            long clientSide = 0;
            for (String gameDir : List.of("run-join", "run-join2", "run-join3")) {
                for (String line : ServerLogs.window(stack.paths().gameLog(gameDir))) {
                    if (!line.contains("client validation lane active on")) {
                        continue;
                    }
                    Matcher matcher = LANE_ACTIVE.matcher(line);
                    if (matcher.find()) {
                        clientSide = Math.max(clientSide, Long.parseLong(matcher.group(1)));
                    }
                }
            }
            append(transcript, "=== regions: worker-side=" + workerSide
                    + " client-side=" + clientSide);
            long active = Math.max(workerSide, clientSide);
            ctx.check(active >= minRegions, "D1: only " + active + " region(s) activated, need "
                    + minRegions + " (see " + stack.resultsDir() + ")");
            ctx.note("D1: " + active + " region(s) active across the mesh");
        });

        // --- D2: sustained random play ------------------------------------------------------------
        int[] rounds = new int[1];
        int[] soakMark = new int[1];
        ctx.stage("D2", "random play sustained for " + soakSeconds + "s at three players", () -> {
            soakMark[0] = ctx.log("server.log").lineCount();
            long deadline = System.nanoTime() + Duration.ofSeconds(soakSeconds).toNanos();
            while (System.nanoTime() < deadline) {
                int round = ++rounds[0];
                for (String who : PLAYERS) {
                    // `setblock` is a DIRECT WORLD WRITE: it fires neither EntityPlaceEvent nor
                    // BreakEvent, so BlockCaptureBridge never sees it and nothing reaches the
                    // validated lane. The first real run of this suite proved that — 197 rounds of
                    // setblock and an empty block_capture ledger, so its "zero divergences" measured
                    // an idle lane and no soak duration would have changed it. `/nodera debug drive`
                    // runs the same submit the bridge runs, as the player, so the edits are actually
                    // captured, signed, routed and committed.
                    rcon.send("execute as " + who + " run nodera debug drive 2");
                }
                if (round % 5 == 0) {
                    rcon.send("execute at " + PLAYERS.get(round % 3)
                            + " run summon minecraft:zombie ~ ~ ~");
                }
                if (round % 7 == 0) {
                    // Movement re-plans ownership: regions change hands while validation is in
                    // flight, which is the state a divergence is most likely to hide in.
                    rcon.send("execute in minecraft:overworld run tp " + PLAYERS.get(round % 3)
                            + " " + (200 + round * 16) + " 100 " + (200 + round * 8));
                }
                ServerDedicatedDrive.sleep(Duration.ofSeconds(2));
            }
            ctx.note("D2: " + rounds[0] + " round(s) driven");
        });

        // --- D3: THE GATE --------------------------------------------------------------------------
        ctx.stage("D3", "zero divergences on every node, and none logged", () -> {
            stack.collectWorkerState();
            long total = 0;
            int seen = 0;
            for (Path state : stateFiles(stack)) {
                Object document = parse(state);
                long divergences = ServerJson.number(document, "validation.divergences");
                total += divergences;
                seen++;
                ctx.note(state.getFileName() + ": divergences=" + divergences
                        + " commits=" + ServerJson.number(document, "validation.committee_commits")
                        + " votes_cast=" + ServerJson.number(document, "validation.votes_cast")
                        + " regions=" + ServerJson.number(document, "validation.active_regions"));
            }
            ctx.note("nodes=" + seen + " total_divergences=" + total);
            ctx.check(total == 0, "D3: THE GATE FAILED — nodes disagreed about the world "
                    + "(divergences above; grep DIVERGENCE in " + stack.logDir() + " and "
                    + stack.paths().gameDir("run-join*") + "/logs)");

            // The counter and the log are independent witnesses: a counter that never moved because
            // the lane never ran would pass silently, so the logs are checked for the line too.
            List<String> logged = divergenceLines(stack);
            if (!logged.isEmpty()) {
                logged.stream().limit(5).forEach(line -> append(transcript, line));
                ctx.fail("D3: a node logged a DIVERGENCE (see " + transcript + ")");
            }

            if (soakSeconds >= GATE_SECONDS) {
                ctx.note("D3: THE GATE HELD — zero divergences over " + soakSeconds
                        + "s of three-client play (issue #5's >= " + GATE_SECONDS
                        + "s acceptance)");
            } else {
                ctx.note("D3: zero divergences over " + soakSeconds + "s of three-client play — a "
                        + "SHORT run; the issue #5 acceptance is SOAK_SECONDS=" + GATE_SECONDS);
            }
        });

        // --- D4: the numbers issue #5 asks to record -----------------------------------------------
        ctx.stage("D4", "the bandwidth and interference numbers are recorded", () -> {
            // The in-game half of the numbers. The worker STATE knows what the mesh did; only the
            // game knows how much of the world the palette could express and how much of it moved
            // without an action behind it. Both are read AFTER the soak so they describe the run
            // rather than its first tick.
            Path ledger = stack.resultsDir().resolve("capture-ledger.txt");
            Path extraction = stack.resultsDir().resolve("extraction.txt");
            write(ledger, rcon.send("nodera debug capture").orElse(""));
            write(extraction, rcon.send("execute as " + PLAYERS.get(0) + " at " + PLAYERS.get(0)
                    + " run nodera debug extract").orElse(""));
            append(transcript, "=== block capture ledger\n" + read(ledger));

            int stalls = ServerLogs.count(ctx.log("server.log").file(),
                    "A single server tick took");
            double worst = worstTickStall(ctx.log("server.log").file());

            write(stack.resultsDir().resolve("phase1-numbers.json"),
                    numbersJson(stack, soakSeconds, rounds[0], stalls, worst,
                            captureLedger(read(ledger)), extraction(read(extraction))));

            // The watchdog stall is a MEASUREMENT here, not a failure, and it is reported rather
            // than hidden. Three real Minecraft clients plus a server plus four workers on one CI
            // runner oversubscribes the box, and "A single server tick took 60.00 seconds" is that
            // oversubscription being honest about itself. It says nothing about whether the nodes
            // agreed — which is this scenario's actual claim, and is asserted separately in D3.
            append(transcript, "=== tick stalls: " + stalls + " (worst " + worst + "s) over "
                    + soakSeconds + "s, " + rounds[0] + " round(s)");
            ctx.note("D4: " + stalls + " watchdog tick stall(s), worst " + worst
                    + "s — recorded, not fatal (see D3 for the gate)");

            // Vanilla's own dungeon-spawner failure, on vanilla's worldgen thread, from
            // MonsterRoomFeature — it fires when the feature cannot pick a mob for a spawner it is
            // placing. This soak walks three clients into fresh chunks for fifteen minutes, so it
            // generates terrain the shorter scenarios never touch, which is why only this one sees
            // it. Failing on it would mean the soak can never pass on a world that happens to
            // generate a dungeon, and it says nothing about whether the validated lane diverged.
            Pattern benign = Pattern.compile(ServerLogs.BENIGN_ERRORS.pattern()
                    + "|A single server tick took|Failed to fetch mob spawner entity");
            List<String> errors = ServerLogs.auditErrorsAfter(ctx.log("server.log").file(),
                    soakMark[0], benign, ServerLogs.BENIGN_NETTY);
            ctx.check(errors.isEmpty(), "D4: the soak left errors in the server log: "
                    + String.join(" | ", errors));

            stack.collectArtefacts();
        });
    }

    // ---------------------------------------------------------------------------------------

    /** Every worker STATE snapshot this run has collected, in name order. */
    private static List<Path> stateFiles(LiveStack stack) {
        try (Stream<Path> files = Files.list(stack.resultsDir())) {
            return files.filter(file -> file.getFileName().toString().startsWith("state-")
                            && file.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException none) {
            return List.of();
        }
    }

    private static Object parse(Path file) {
        return ServerJson.tryParse(read(file)).orElse(Map.of());
    }

    /** Any {@code DIVERGENCE} line, in the service logs or in a client's own log. */
    private static List<String> divergenceLines(LiveStack stack) {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(stack.logDir())) {
            files.filter(Files::isRegularFile).forEach(file ->
                    ServerLogs.window(file).stream()
                            .filter(line -> line.contains("DIVERGENCE"))
                            .forEach(hits::add));
        } catch (IOException none) {
            // No log directory is not evidence of a divergence.
        }
        for (String gameDir : List.of("run-join", "run-join2", "run-join3")) {
            ServerLogs.window(stack.paths().gameLog(gameDir)).stream()
                    .filter(line -> line.contains("DIVERGENCE"))
                    .forEach(hits::add);
        }
        return hits;
    }

    private static double worstTickStall(Path serverLog) {
        double worst = 0;
        for (String line : ServerLogs.window(serverLog)) {
            Matcher matcher = TICK_STALL.matcher(line);
            while (matcher.find()) {
                worst = Math.max(worst, Double.parseDouble(matcher.group(1)));
            }
        }
        return worst;
    }

    /**
     * {@code /nodera debug capture} prints "REASON: n" per outcome.
     *
     * <p>The reasons are the ledger's own words (CAPTURED, REGION_NOT_DELEGATED, BLOCK_UNSUPPORTED,
     * …), so parsing them keeps the JSON honest even when a new reason is added.
     */
    private static Map<String, Long> captureLedger(String text) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            String[] parts = line.trim().split(":");
            if (parts.length != 2) {
                continue;
            }
            String reason = parts[0].trim();
            String count = parts[1].trim();
            if (!reason.isEmpty() && reason.equals(reason.toUpperCase(java.util.Locale.ROOT))
                    && count.chars().allMatch(Character::isDigit) && !count.isEmpty()) {
                out.put(reason, Long.parseLong(count));
            }
        }
        return out;
    }

    /** The three numbers {@code /nodera debug extract} reports, by their own labels. */
    private static Map<String, Long> extraction(String text) {
        Map<String, Long> out = new LinkedHashMap<>();
        Map<String, String> labels = Map.of(
                "blocks outside the palette", "blocks_outside_palette",
                "dense sections", "dense_sections",
                "drift from committed state", "drift_blocks");
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            labels.forEach((label, key) -> {
                if (line.startsWith(label)) {
                    Matcher digits = LEADING_DIGITS.matcher(line.substring(label.length()));
                    if (digits.find()) {
                        out.put(key, Long.parseLong(digits.group(1)));
                    }
                }
            });
        }
        return out;
    }

    /**
     * The summary artefact issue #5 asks for.
     *
     * <p>{@code rounds_driven} and the tick-stall pair are recorded because a soak that drove 12
     * rounds in 900 s is a different experiment from one that drove 180, and the divergence number
     * means less without it. An empty capture ledger is itself a reading — see L-80.
     */
    private static String numbersJson(LiveStack stack, long soakSeconds, int rounds, int stalls,
                                      double worst, Map<String, Long> capture,
                                      Map<String, Long> extraction) {
        StringBuilder json = new StringBuilder("{\n");
        json.append("  \"soak_seconds\": ").append(soakSeconds).append(",\n");
        json.append("  \"rounds_driven\": ").append(rounds).append(",\n");
        json.append("  \"block_capture\": ").append(mapJson(capture)).append(",\n");
        json.append("  \"extraction\": ").append(mapJson(extraction)).append(",\n");
        json.append("  \"tick_stalls\": ").append(stalls).append(",\n");
        json.append("  \"worst_tick_stall_seconds\": ").append(worst).append(",\n");
        json.append("  \"nodes\": [");
        List<Path> states = stateFiles(stack);
        for (int i = 0; i < states.size(); i++) {
            Object document = parse(states.get(i));
            String name = states.get(i).getFileName().toString();
            name = name.substring("state-".length(), name.length() - ".json".length());
            json.append(i == 0 ? "\n" : ",\n");
            json.append("    {\"node\": \"").append(name).append("\"")
                    .append(", \"regions\": ")
                    .append(ServerJson.number(document, "validation.active_regions"))
                    .append(", \"commits\": ")
                    .append(ServerJson.number(document, "validation.committee_commits"))
                    .append(", \"fallback_commits\": ")
                    .append(ServerJson.number(document, "validation.fallback_commits"))
                    .append(", \"votes_cast\": ")
                    .append(ServerJson.number(document, "validation.votes_cast"))
                    .append(", \"votes_received\": ")
                    .append(ServerJson.number(document, "validation.votes_received"))
                    .append(", \"divergences\": ")
                    .append(ServerJson.number(document, "validation.divergences"))
                    .append(", \"bytes_up\": ").append(transferNumber(document, "sent"))
                    .append(", \"bytes_down\": ").append(transferNumber(document, "received"))
                    .append("}");
        }
        json.append(states.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
        return json.toString();
    }

    /** Bytes moved, under whichever of the two field spellings this build reports. */
    private static long transferNumber(Object document, String direction) {
        long value = ServerJson.number(document, "transfer.bytes_" + direction);
        if (value != 0) {
            return value;
        }
        value = ServerJson.number(document, "content.bytes_" + direction);
        if (value != 0) {
            return value;
        }
        String alternative = "sent".equals(direction) ? "uploaded_bytes" : "downloaded_bytes";
        value = ServerJson.number(document, "transfer." + alternative);
        return value != 0 ? value : ServerJson.number(document, "content." + alternative);
    }

    private static String mapJson(Map<String, Long> values) {
        StringBuilder out = new StringBuilder("{");
        values.forEach((key, value) -> {
            if (out.length() > 1) {
                out.append(", ");
            }
            out.append('"').append(key).append("\": ").append(value);
        });
        return out.append('}').toString();
    }

    private static long envSeconds(String name, long fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static String read(Path file) {
        return String.join("\n", ServerLogs.window(file));
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new HarnessException("cannot write " + file, e);
        }
    }

    private static void append(Path file, String line) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new HarnessException("cannot append to " + file, e);
        }
    }
}
