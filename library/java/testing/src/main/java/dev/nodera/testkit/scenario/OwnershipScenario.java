package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Per-player region ownership, and the world outliving the player who was hosting it.
 *
 * <ol>
 *   <li>O1 player A shares a world on the Nodera network; player B joins through the
 *       tracker/rendezvous — each player's node owns its own field-of-view regions.</li>
 *   <li>O2 the ownership DRIVE runs: each player is teleported to a region its own node owns, then
 *       player B is sent into player A's region — the REGION enter/leave log is the evidence
 *       stream.</li>
 *   <li>O3 player A disconnects; player B keeps the world (continuity recovery — today a brief
 *       visible recovery, the T16 local-replica view makes it invisible; the assertion is survival
 *       and re-host, not screenlessness).</li>
 *   <li>O4 player A RE-JOINS THE SAME WORLD over the network, now hosted by B.</li>
 * </ol>
 *
 * <p>Topology: the standard one — 2 players, 1 tracker, 1 rendezvous, 3 headless peers. Needs the
 * baked {@code NoderaE2E} world, which {@link ContinuityScenario} produces and this scenario's own
 * staging will bake if it is absent.
 *
 * <p>Thread-context: run on the runner's thread; stateless between runs.
 */
public final class OwnershipScenario implements Scenario {

    private static final String HOST_RUN = "clientHostRunProgramArgs";

    @Override
    public String id() {
        return "ownership";
    }

    @Override
    public String title() {
        return "each player owns its own regions, and the world survives its host leaving";
    }

    @Override
    public Set<String> tags() {
        return Set.of("live", "ownership");
    }

    @Override
    public Requirements requirements() {
        return Requirements.liveClients(5);
    }

    @Override
    public void run(ScenarioContext context) throws Exception {
        LiveStack stack = context.stack();
        LogWatcher hostLog = context.log("client-host.log");
        LogWatcher joinLog = context.log("client-join.log");
        Path hostLogFile = stack.logDir().resolve("client-host.log");
        HostWorldSupport.HostedPair[] players = new HostWorldSupport.HostedPair[1];
        int[] mark = new int[1];

        context.stage("O0", "infrastructure, every peer, and the staged world are ready", () -> {
            HostWorldSupport.probeWorkers(context);
            Path config = HostWorldSupport.stagedWorld(context);
            // Test-1 config on top of the standard staging: the ownership drive, plus a
            // ghost-captured overworld so spawn-area mobs do not instantly revoke every region
            // (ENTITY_EXCLUSION acceptance #3 — the drive needs regions to STAY delegated).
            HostWorldSupport.setHostConfig(config, "debug", "regionDrive", "true");
            HostWorldSupport.setHostConfig(config, "entity", "mobCaptureDimensions",
                    "[\"minecraft:overworld\"]");
            context.check(HostWorldSupport.containsAfter(config, 0,
                            "mobCaptureDimensions = [\"minecraft:overworld\"]"),
                    "could not enable mobCapture in " + config);
        });

        context.stage("O1", "two players are in, with per-player region ownership live", () -> {
            players[0] = HostWorldSupport.hostedTwoPlayers(context);
            // A world baked before a FlatWorldRules.RULES_VERSION bump carries a certified genesis
            // the current engine refuses, so the lane never boots and this wait would burn its whole
            // timeout with no stated cause — bail out the moment the bootstrap failure appears
            // instead.
            HostWorldSupport.awaitMemberNodes(context, hostLog, "O1");
            joinLog.await("client validation lane active", Duration.ofSeconds(180));
        });

        context.stage("O2", "the ownership drive ran and logged region enter/leave evidence", () -> {
            hostLog.await("DRIVE step 1", Duration.ofSeconds(300));
            hostLog.await("DRIVE step 2", Duration.ofSeconds(300));
            hostLog.await("REGION: ", Duration.ofSeconds(120));
            List<String> evidence = HostWorldSupport.readLines(hostLogFile).stream()
                    .filter(line -> line.contains("REGION: ") || line.contains("DRIVE "))
                    .toList();
            evidence.subList(Math.max(0, evidence.size() - 20), evidence.size())
                    .forEach(line -> HostWorldSupport.transcript(context,
                            "region-evidence.log", line));
        });

        context.stage("O2b", "a seeded region carries the real world, not sixty-four columns of air",
                () -> {
            // The exit test for the all-air base. Every region lane used to activate on
            // EntityLaneBootstrap.initialSnapshot — 64 columns of air — because that is the only
            // base every member can derive identically with no transfer. So the validated lane held
            // air plus whatever edits it had witnessed, and a region seeded from it carried air:
            // the one thing the whole content plane exists to move was not in it.
            //
            // Size is the assertion because size is what distinguishes them and nothing else does.
            // An all-air region encodes to a few hundred bytes (64 uniform columns, no dense
            // sections); a region of real terrain is megabytes. There is no threshold to tune here
            // — the two differ by four orders of magnitude.
            Path workerLog = stack.logDir().resolve("worker-player1.log");
            java.util.regex.Pattern seeded = java.util.regex.Pattern.compile(
                    "Seeding region .* — (\\d+) piece\\(s\\), (\\d+) byte\\(s\\)");
            long largest = 0;
            for (String line : HostWorldSupport.readLines(workerLog)) {
                java.util.regex.Matcher m = seeded.matcher(line);
                if (m.find()) {
                    largest = Math.max(largest, Long.parseLong(m.group(2)));
                }
            }
            HostWorldSupport.transcript(context, "region-terrain.log",
                    "largest seeded region: " + largest + " byte(s)");
            if (largest == 0) {
                throw new AssertionError("the host's worker seeded no region at all, so there is "
                        + "nothing to say about what a region carries");
            }
            if (largest < 100_000) {
                throw new AssertionError("the largest region this host seeded is " + largest
                        + " byte(s) — that is an all-air region, so the lane is still activating "
                        + "on the derived base and the content plane is moving nothing");
            }
        });

        context.stage("O3", "player B kept the world and now hosts it", () -> {
            HostWorldSupport.stopClient(players[0].host(), HOST_RUN);
            // The prefix, not the whole sentence: the takeover has two endings — this peer was
            // elected, or another one was — and both prove the thing this stage is about. Waiting
            // on the full line is how this assertion went stale in the first place, against a
            // message the succession rework reworded and no production code has emitted since.
            joinLog.await("Nodera: the host's connection ended", Duration.ofSeconds(120));
            joinLog.await("Nodera: sharing world", Duration.ofSeconds(420));
            joinLog.await("game server open for joiners on port " + context.topology().gamePort(),
                    Duration.ofSeconds(180));
        });

        context.stage("O4", "player A re-joined over the network and ownership re-planned", () -> {
            // Mark first: the re-plan that proves anything is a NEW one, after the rejoin — player
            // B's own rehost already logged a "member node(s)" line above.
            mark[0] = joinLog.lineCount();
            stack.startClient("runClientRejoin", "client-rejoin.log");
            joinLog.awaitAfter("HostDev joined the game", Duration.ofSeconds(600), mark[0]);
            joinLog.awaitAfter("member node(s)", Duration.ofSeconds(240), mark[0]);
        });

        stack.collectWorkerState();
    }
}
