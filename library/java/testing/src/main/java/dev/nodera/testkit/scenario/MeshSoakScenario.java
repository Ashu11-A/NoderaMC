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
 *   <li>S1 SUSTAINED LOAD: rounds of <b>captured</b> block edits, mob summons and movement over
 *       RCON, so regions keep committing versions rather than settling after one burst.</li>
 *   <li>S2 WHO validated it. Under field-of-view ownership the seats sit on the PLAYERS' client
 *       lanes, not on the headless workers, so the client lane is the thing that must show it
 *       re-executed and voted under load.</li>
 *   <li>S2b THE EXIT, first half: two headless WORKERS — not the clients — must each report a
 *       non-zero {@code committee_commits}. A worker only reaches that counter by voting on a
 *       proposal it re-executed itself, so it is the one number that cannot be reached by
 *       certified state simply arriving.</li>
 *   <li>S3 THE EXIT, second half: every region root that more than one peer reports must be
 *       IDENTICAL. Two peers that validated the same region and disagree about its root is the
 *       failure this whole lane exists to prevent.</li>
 *   <li>S4 no errors accumulated across the soak; artefacts collected.</li>
 * </ol>
 *
 * <p><b>Why S1 does not use {@code fill} any more.</b> A {@code /fill} — like {@code /setblock} —
 * is a direct world write: it fires neither {@code BlockEvent.EntityPlaceEvent} nor
 * {@code BlockEvent.BreakEvent}, so {@code BlockCaptureBridge} never sees it and nothing reaches
 * the validated lane. Every mesh-soak run this row has ever recorded drove its load that way, which
 * is why the workers reported {@code votes_cast=0} while holding dozens of seats: there was never
 * an action for a primary to propose. What moved the region versions was the ghost lane's
 * {@code ExternalDelta} stream — certified state arriving, not a committee round. {@code /nodera
 * debug drive} runs the same {@code submitBlockAction} the bridge runs, as the player, so the edit
 * is captured, signed, forwarded to its primary, proposed and voted on. {@code DeterminismScenario}
 * learned this first and this scenario did not follow.
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

        List<String> players = context.topology().players() >= 2
                ? List.of("JoinerDev", "JoinerTwo") : List.of("JoinerDev");

        context.stage("S1", "sustained load applied for " + SOAK.toSeconds() + "s", () -> {
            mark[0] = serverLog.lineCount();
            players.forEach(who -> rcon.require("gamemode creative " + who));
            long deadline = System.nanoTime() + context.topology().scaled(SOAK).toNanos();
            int round = 0;
            while (System.nanoTime() < deadline) {
                round++;
                // CAPTURED block edits: the same submitBlockAction the bridge calls, as the player,
                // so the edit is signed, forwarded to its primary, proposed and voted on. A `fill`
                // here is a direct world write that reaches the lane through nothing at all — see
                // the class javadoc, and DeterminismScenario's D2, which found this first.
                for (String who : players) {
                    rcon.send("execute as " + who + " run nodera debug drive 2");
                }
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

        context.stage("S2b", "two headless workers each committed as a committee member", () -> {
            // THE EXIT CLAUSE, first half (L-30): "two headless workers (not the clients) both
            // report non-zero committee_commits". The counter is only reachable from
            // WorkerValidationService.onCommitAnnounce -> commitLocally, which returns early unless
            // this worker already cast a ballot on a proposal it re-executed itself. So a non-zero
            // committee_commits cannot be produced by certified state merely arriving: the
            // ExternalDelta path advances a replica's version and root without touching it, which
            // is exactly how every previous run of this scenario reported active regions, advancing
            // roots and votes_cast=0 at the same time.
            stack.collectWorkerState();
            // The capture ledger, for whoever reads this when it fails: "nothing was captured" and
            // "everything was captured" are indistinguishable from the counters alone.
            HostWorldSupport.transcript(context, "soak.log", "=== capture ledger: "
                    + rcon.send("nodera debug capture").orElse("<no answer>"));
            List<Path> states = workerStates(context);
            context.check(!states.isEmpty(),
                    "S2b: no worker answered NODERA-STATE at all, so nothing can be read about the "
                            + "committee (check the control sockets in " + stack.logDir() + ")");
            List<String> voting = new ArrayList<>();
            List<String> silent = new ArrayList<>();
            for (Path state : states) {
                Object document = ServerJson.tryParse(readOrEmpty(state)).orElse(Map.of());
                String worker = workerName(state);
                long commits = ServerJson.number(document, "validation.committee_commits");
                String line = worker
                        + ": committee_commits=" + commits
                        + " votes_cast=" + ServerJson.number(document, "validation.votes_cast")
                        + " votes_received="
                        + ServerJson.number(document, "validation.votes_received")
                        + " active_regions="
                        + ServerJson.number(document, "validation.active_regions");
                HostWorldSupport.transcript(context, "soak.log", "=== " + line);
                context.note(line);
                (commits > 0 ? voting : silent).add(worker);
            }
            context.check(voting.size() >= 2,
                    "S2b: L-30's exit clause is unmet — only " + voting.size()
                            + " headless worker(s) report a non-zero committee_commits ("
                            + (voting.isEmpty() ? "none" : String.join(", ", voting))
                            + "); silent: " + String.join(", ", silent)
                            + ". A worker holding seats and replicas while committing nothing means "
                            + "certified state is ARRIVING (ExternalDelta) rather than being "
                            + "PROPOSED to it — start at WorkerValidationService.proposeBatch's "
                            + "addressee log, then at onProposal's body-version check");
            context.note("S2b: " + voting.size() + " worker(s) committed as committee members: "
                    + String.join(", ", voting));
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
        // This used to note "S3: SKIPPED" and return green, which is how a stage that asserted
        // nothing survived several runs. S2b now proves two workers committed as committee
        // members before this stage runs, so a worker holding no region replica here is a
        // contradiction, not a topology this scenario has to tolerate.
        context.check(seats > 0, "S3: no WORKER holds a region replica, so there are no two "
                + "inspectable peers to compare roots between — yet S2b saw workers commit. The "
                + "seats would be on the players' client lanes, whose roots the control socket "
                + "cannot see (L-30 / L-60)");

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

    /**
     * Every {@code state-<worker>.json} {@link LiveStack#collectWorkerState()} wrote, in a stable
     * order. These are the HEADLESS workers' answers and only theirs — the clients have no control
     * socket, which is precisely why L-30's exit clause is phrased about workers.
     */
    private static List<Path> workerStates(ScenarioContext context) {
        Path results = context.stack().resultsDir();
        if (!Files.isDirectory(results)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(results)) {
            return files.filter(file -> file.getFileName().toString().startsWith("state-")
                            && file.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (Exception unreadable) {
            return List.of();
        }
    }

    /** {@code state-peer1.json} → {@code peer1}. */
    private static String workerName(Path state) {
        String name = state.getFileName().toString();
        return name.substring("state-".length(), name.length() - ".json".length());
    }

    /** A state document the worker never answered is not a divergence; read it as empty. */
    private static String readOrEmpty(Path state) {
        try {
            return Files.readString(state, StandardCharsets.UTF_8);
        } catch (Exception unreadable) {
            return "";
        }
    }

    /** Every worker's {@code region_roots}, keyed by region and then by the worker that holds it. */
    private Map<String, Map<String, VersionedRoot>> readRegionRoots(ScenarioContext context) {
        Map<String, Map<String, VersionedRoot>> roots = new TreeMap<>();
        for (Path file : workerStates(context)) {
            String worker = workerName(file);
            String document = readOrEmpty(file);
            Matcher matcher = REGION_ROOT.matcher(document);
            while (matcher.find()) {
                roots.computeIfAbsent(matcher.group(1), key -> new LinkedHashMap<>())
                        .put(worker, new VersionedRoot(Long.parseLong(matcher.group(3)),
                                matcher.group(2)));
            }
        }
        return roots;
    }

    /** One peer's view of one region: the head root and the version it is the root of. */
    private record VersionedRoot(long version, String root) {
    }
}
