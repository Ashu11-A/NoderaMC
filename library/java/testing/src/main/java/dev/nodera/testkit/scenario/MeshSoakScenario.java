package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.mc.RconClient;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The live mesh under sustained load (L-30).
 *
 * <p>The mechanism was already proven out of game: {@code WorkerQuorumValidationIT} for
 * committee-over-transport and {@code EventSyncOverTransportIT} for certified forward sync. What was
 * missing is the claim about a LIVE mesh: that validated state keeps flowing over the same
 * {@code PeerTransport} while real clients play, and that the peers still agree about the world at
 * the end.
 *
 * <ol>
 *   <li>S0 the standard topology with both players in-world and the entity lane live.</li>
 *   <li>S1 SUSTAINED LOAD: rounds of block edits, mob summons and movement over RCON, so regions
 *       keep committing versions rather than settling after one burst.</li>
 *   <li>S2 WHO validated it. Under field-of-view ownership the seats sit on the PLAYERS' client
 *       lanes, not on the headless workers, so the client lane is the thing that must show it
 *       re-executed and voted under load.</li>
 *   <li>S3 THE EXIT: every region root that more than one peer reports must be IDENTICAL. When no
 *       WORKER holds a replica there are no two inspectable peers to compare — the stage says so and
 *       names L-60/L-30 rather than passing quietly. Two peers that validated the same region and
 *       disagree about its root is the failure this whole lane exists to prevent.</li>
 *   <li>S4 no errors accumulated across the soak; artefacts collected.</li>
 * </ol>
 *
 * <p>Thread-context: run on the runner's thread; stateless between runs.
 */
public final class MeshSoakScenario implements Scenario {

    /** How long the load runs before the peers are asked whether they still agree. */
    private static final Duration SOAK = Duration.ofSeconds(180);

    /**
     * {@code "<region>":{"root":"<hex>","version":<n>}} — the only shape in the state document that
     * pairs a root with the version it is the root of.
     */
    private static final Pattern REGION_ROOT = Pattern.compile(
            "\"([^\"]+)\":\\{\"root\":\"([^\"]*)\",\"version\":(-?\\d+)\\}");

    @Override
    public String id() {
        return "mesh-soak";
    }

    @Override
    public String title() {
        return "validated state keeps flowing over the live mesh under load, and the peers agree";
    }

    @Override
    public Set<String> tags() {
        return Set.of("live", "mesh");
    }

    @Override
    public Requirements requirements() {
        return Requirements.liveClients(5);
    }

    @Override
    public void run(ScenarioContext context) throws Exception {
        LiveStack stack = context.stack();
        RconClient rcon = stack.rcon();
        LogWatcher serverLog = context.log("server.log");
        Path serverLogFile = stack.logDir().resolve("server.log");
        int[] mark = new int[1];

        context.stage("S0", "both players are in-world, the lane is live, every peer is up", () ->
                HostWorldSupport.dedicatedTwoPlayers(context));

        context.stage("S1", "sustained load applied for " + SOAK.toSeconds() + "s", () -> {
            mark[0] = serverLog.lineCount();
            rcon.require("gamemode creative JoinerDev");
            long deadline = System.nanoTime() + context.topology().scaled(SOAK).toNanos();
            int round = 0;
            while (System.nanoTime() < deadline) {
                round++;
                // Block edits are the cheapest thing that produces a committed delta per region tick.
                rcon.send("execute at JoinerDev run fill ~-2 ~ ~-2 ~2 ~ ~2 minecraft:stone");
                rcon.send("execute at JoinerDev run fill ~-2 ~ ~-2 ~2 ~ ~2 minecraft:air");
                // A mob keeps the entity lane busy alongside the block lane.
                rcon.send("execute at JoinerDev run summon minecraft:zombie ~ ~ ~");
                // Movement re-plans ownership, which is what makes this a mesh soak rather than a
                // single-region one: regions change hands while validation is in flight.
                rcon.send("execute in minecraft:overworld run tp JoinerDev "
                        + (200 + round * 64) + " 100 200");
                context.settle(Duration.ofSeconds(5));
            }
            context.note(round + " round(s) driven");
        });

        context.stage("S2", "the walking player's own lane re-executed and voted under load", () -> {
            // Under field-of-view ownership the seats belong to the PLAYERS' nodes: the client lanes
            // re-execute and vote, while the headless workers hold none. So the workers' counters
            // can be legitimately zero while validation is working perfectly — the same node
            // question as L-60. The drive therefore reads both sides and says which one carried the
            // load.
            stack.collectWorkerState();
            Path clientLatest = stack.paths().gameLog("run-join");
            Optional<String> lane = HostWorldSupport.lastMatchAfter(clientLatest, 0,
                    "client validation lane active");
            HostWorldSupport.transcript(context, "soak.log",
                    "=== client lane: " + lane.orElse("<none>"));
            context.check(lane.isPresent(),
                    "the player's client lane never activated — nothing validated the load at all");
            OptionalInt regions = HostWorldSupport.laneRegions(lane.get());
            context.check(regions.isPresent() && regions.getAsInt() > 0,
                    "the client lane reports no regions (got '" + lane.get() + "')");
            context.note("the walking player's lane covered " + regions.getAsInt() + " region(s)");
        });

        context.stage("S3", "every region held at the same version by more than one peer has the "
                + "same root", () -> compareRegionRoots(context));

        context.stage("S4", "the soak left no errors in the server log", () ->
                HostWorldSupport.requireNoErrors(serverLogFile, mark[0],
                        "S4: the soak left errors in the server log"));
    }

    /**
     * Compare every region root reported by more than one peer.
     *
     * <p>Two DIFFERENT outcomes used to share one failure message, and the difference matters more
     * than the assertion did. "Nothing shared" fires when no region is held by more than one peer,
     * while a mismatch is a genuine divergence. Reporting both as "the mesh diverged" cost real
     * diagnosis: the first run where workers finally held replicas (64, up from 0) reported a
     * divergence that had not happened, and the actual news — every region still has exactly ONE
     * holder — was hidden behind the wrong sentence.
     *
     * <p>Roots are compared ONLY between peers reporting the SAME version of a region. Two peers
     * holding the same region at different points in the same history report different roots and are
     * in perfect agreement; under sustained load that is the normal state, not a fork. The comparison
     * that means something is: same region, same version, same root.
     */
    private void compareRegionRoots(ScenarioContext context) {
        Map<String, Map<String, VersionedRoot>> roots = readRegionRoots(context);
        int seats = roots.values().stream().mapToInt(Map::size).sum();
        HostWorldSupport.transcript(context, "soak.log",
                "=== worker-held region replicas: " + seats);
        if (seats == 0) {
            // The honest state of the world, not a green tick over an assertion nobody can make
            // here.
            HostWorldSupport.transcript(context, "soak.log",
                    "=== S3 skipped: no worker holds a region replica (L-60 / L-30)");
            context.note("S3: SKIPPED — no WORKER holds a region replica, so there are no two "
                    + "inspectable peers to compare roots between. The seats are on the players' "
                    + "client lanes, whose roots the control socket cannot see. That is L-30's "
                    + "remaining gap, and it shares L-60's cause.");
            return;
        }

        int comparable = 0;
        int skew = 0;
        List<String> diverged = new ArrayList<>();
        for (Map.Entry<String, Map<String, VersionedRoot>> region : roots.entrySet()) {
            Map<Long, Map<String, String>> byVersion = new TreeMap<>();
            region.getValue().forEach((worker, entry) -> byVersion
                    .computeIfAbsent(entry.version(), key -> new LinkedHashMap<>())
                    .put(worker, entry.root()));
            List<Map.Entry<Long, Map<String, String>>> paired = byVersion.entrySet().stream()
                    .filter(entry -> entry.getValue().size() > 1).toList();
            if (paired.isEmpty()) {
                if (region.getValue().size() > 1) {
                    skew++;
                }
                continue;
            }
            for (Map.Entry<Long, Map<String, String>> version : paired) {
                comparable++;
                if (new LinkedHashSet<>(version.getValue().values()).size() != 1) {
                    diverged.add(region.getKey() + " @ version " + version.getKey()
                            + " diverged across peers: " + version.getValue());
                }
            }
        }

        String summary = "regions reported: " + roots.size()
                + "; region/version pairs held by more than one peer: " + comparable
                + "; regions where the peers are at different versions (not comparable): " + skew;
        HostWorldSupport.transcript(context, "soak.log", "=== " + summary);
        context.note(summary);
        diverged.forEach(line -> HostWorldSupport.transcript(context, "soak.log", line));

        context.check(diverged.isEmpty(),
                "peers disagree about a region root — the mesh diverged: "
                        + String.join(" | ", diverged));
        context.check(comparable > 0, "workers hold replicas, but no region is held at the SAME "
                + "VERSION by more than one peer — so there is still nothing to compare. NOT a "
                + "divergence: either the committees are seating one holder per region, or the "
                + "holders are at different points in the same history (L-30)");
    }

    /** Every worker's {@code region_roots}, keyed by region and then by the worker that holds it. */
    private Map<String, Map<String, VersionedRoot>> readRegionRoots(ScenarioContext context) {
        Map<String, Map<String, VersionedRoot>> roots = new TreeMap<>();
        Path results = context.stack().resultsDir();
        if (!Files.isDirectory(results)) {
            return roots;
        }
        try (Stream<Path> files = Files.list(results)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith("state-") || !name.endsWith(".json")) {
                    continue;
                }
                String worker = name.substring("state-".length(), name.length() - ".json".length());
                String document;
                try {
                    document = Files.readString(file, StandardCharsets.UTF_8);
                } catch (Exception unreadable) {
                    // A state document the worker never answered is not a divergence; skip it the
                    // way the shell's json.load did.
                    continue;
                }
                Matcher matcher = REGION_ROOT.matcher(document);
                while (matcher.find()) {
                    roots.computeIfAbsent(matcher.group(1), key -> new LinkedHashMap<>())
                            .put(worker, new VersionedRoot(Long.parseLong(matcher.group(3)),
                                    matcher.group(2)));
                }
            }
        } catch (Exception unreadable) {
            return roots;
        }
        return roots;
    }

    /** One peer's view of one region: the head root and the version it is the root of. */
    private record VersionedRoot(long version, String root) {
    }
}
