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
            hostLog.awaitGuarded("member node(s)", Duration.ofSeconds(180),
                    HostWorldSupport.STALE_BAKE_GUARD,
                    "O1: " + HostWorldSupport.STALE_BAKE_MESSAGE);
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

        context.stage("O3", "player B kept the world and now hosts it", () -> {
            HostWorldSupport.stopClient(players[0].host(), HOST_RUN);
            joinLog.await("Nodera continuity: host connection lost", Duration.ofSeconds(120));
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
