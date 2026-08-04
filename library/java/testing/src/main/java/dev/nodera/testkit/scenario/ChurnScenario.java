package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.harness.ManagedProcess;
import dev.nodera.testkit.harness.PlayerRole;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Join/leave churn: a player joining and leaving over and over must not damage the world.
 *
 * <ol>
 *   <li>C1 player A hosts a shared world, player B joins, then both leave.</li>
 *   <li>C2 five times over: player B joins, plays briefly, disconnects (random dwell).</li>
 *   <li>C3 log audit: no {@code ERROR}/{@code FATAL}/exception in the mod or the worker logs — a
 *       curated allowlist covers the known-benign lines.</li>
 * </ol>
 *
 * <p>The world and the workers stay up the whole time: churn must not corrupt the lane, leak
 * sessions, or accumulate errors.
 *
 * <p>Topology: the standard one. Player B is the one that churns; player A and all three peers stay
 * up across every cycle.
 *
 * <p>Thread-context: run on the runner's thread; stateless between runs.
 */
public final class ChurnScenario implements Scenario {

    /**
     * How many join/leave cycles to drive.
     *
     * <p>The shell suite took this as {@code --cycles N}; here it is a constant because a scenario
     * declares what it proves rather than taking a parameter that changes it. Five is what every
     * recorded run used.
     */
    private static final int CYCLES = 5;

    private static final String JOINER_RUN = "clientJoinRunProgramArgs";

    @Override
    public String id() {
        return "churn";
    }

    @Override
    public String title() {
        return "repeated join/leave churn leaves the world and the peers undamaged";
    }

    @Override
    public Set<String> tags() {
        return Set.of("live", "continuity");
    }

    @Override
    public Requirements requirements() {
        return Requirements.liveClients(5);
    }

    @Override
    public void run(ScenarioContext context) throws Exception {
        LiveStack stack = context.stack();
        LogWatcher hostLog = context.log("client-host.log");
        Path hostLogFile = stack.logDir().resolve("client-host.log");
        HostWorldSupport.HostedPair[] players = new HostWorldSupport.HostedPair[1];
        Random dwellLength = new Random();

        context.stage("C0", "infrastructure, every peer, and the staged world are ready", () -> {
            HostWorldSupport.probeWorkers(context);
            Path config = HostWorldSupport.stagedWorld(context);
            // Churn measures join/leave, not the ownership drive — keep the drive off so the
            // teleport script does not move players mid-cycle.
            HostWorldSupport.setHostConfig(config, "debug", "regionDrive", "false");
        });

        context.stage("C1", "the world is hosted and the baseline join+leave registers", () -> {
            players[0] = HostWorldSupport.hostedTwoPlayers(context);
            HostWorldSupport.awaitMemberNodes(context, hostLog, "C1");
            // The first cycle starts from a clean two-player baseline: drop player B again.
            HostWorldSupport.stopClient(players[0].joiner(), JOINER_RUN);
            hostLog.await("JoinerDev left the game", Duration.ofSeconds(120));
        });

        for (int cycle = 1; cycle <= CYCLES; cycle++) {
            int index = cycle;
            context.stage("C2." + cycle, "cycle " + cycle + "/" + CYCLES
                    + ": player B joins, dwells, and leaves", () -> {
                int mark = hostLog.lineCount();
                ManagedProcess client = stack.startClient("runClientJoin",
                        "client-joiner-" + index + ".log");
                hostLog.awaitAfter("JoinerDev joined the game", Duration.ofSeconds(600), mark);
                // A random dwell rather than a fixed one: a cycle that always disconnects at the
                // same point in the tick would only ever exercise one moment of the session.
                Duration dwell = Duration.ofSeconds(dwellLength.nextInt(20) + 10);
                context.note("cycle " + index + ": dwelling " + dwell.toSeconds()
                        + "s, then disconnecting");
                context.settle(dwell);
                HostWorldSupport.stopClient(client, JOINER_RUN);
                hostLog.awaitAfter("JoinerDev left the game", Duration.ofSeconds(120), mark);
                context.settle(Duration.ofSeconds(5));
            });
        }

        context.stage("C3", "no errors accumulated across the churn cycles", () -> {
            List<Path> audited = new ArrayList<>();
            audited.add(hostLogFile);
            for (int i = 0; i < stack.workers().size(); i++) {
                PlayerRole role = context.topology().workerRole(i);
                audited.add(stack.logDir().resolve("worker-" + role.cliName() + ".log"));
            }
            List<String> offenders = new ArrayList<>();
            for (Path file : audited) {
                HostWorldSupport.auditErrors(file, 0)
                        .forEach(line -> offenders.add(file.getFileName() + ": " + line));
            }
            context.check(offenders.isEmpty(),
                    "error lines found across " + CYCLES + " churn cycles: "
                            + String.join(" | ", offenders));
        });

        stack.collectWorkerState();
    }
}
