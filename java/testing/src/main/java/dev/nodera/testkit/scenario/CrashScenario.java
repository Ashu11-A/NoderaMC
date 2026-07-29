package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * A sudden client crash, and zero disruption for the survivor.
 *
 * <ol>
 *   <li>X0 the stack, player A hosting the baked {@code NoderaE2E} world, player B joined.</li>
 *   <li>X1 player B's client JVM is SIGKILLed mid-session — a real crash: no disconnect packet, no
 *       shutdown hooks.</li>
 *   <li>X2 the survivor (player A, the host) experiences NO disruption: the leave registers,
 *       ownership re-plans across the remaining member set, NO continuity recovery arms and NO
 *       migration screen appears (the world never went anywhere), no {@code ERROR}/{@code FATAL}
 *       beyond the benign abrupt-disconnect allowlist, no "Exception in server tick loop", and the
 *       host JVM is still alive.</li>
 *   <li>X3 the world is still live: player B rejoins successfully.</li>
 * </ol>
 *
 * <p>The inverse case — the HOST crashes — is the continuity recovery, which is what
 * {@link ContinuityScenario} and {@link OwnershipScenario} assert.
 *
 * <p>Topology: the standard one. The spare peer and player B's own companion both survive the
 * crash; only the game JVM dies.
 *
 * <p>Thread-context: run on the runner's thread; stateless between runs.
 */
public final class CrashScenario implements Scenario {

    /** ModDevGradle's per-run token for the joiner client's JVM. */
    private static final String JOINER_RUN = "clientJoinRunProgramArgs";

    /** ModDevGradle's per-run token for the host client's JVM. */
    private static final String HOST_RUN = "clientHostRunProgramArgs";

    @Override
    public String id() {
        return "crash";
    }

    @Override
    public String title() {
        return "a player's client crashing disrupts nothing for the player still in the world";
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
        int[] mark = new int[1];

        context.stage("X0", "two players in-world on the staged world, every peer up", () -> {
            Path config = HostWorldSupport.stagedWorld(context);
            // The crash test measures disruption, not the ownership drive.
            HostWorldSupport.setHostConfig(config, "debug", "regionDrive", "false");
            players[0] = HostWorldSupport.hostedTwoPlayers(context);
            hostLog.awaitGuarded("member node(s)", Duration.ofSeconds(180),
                    HostWorldSupport.STALE_BAKE_GUARD,
                    "X0: " + HostWorldSupport.STALE_BAKE_MESSAGE);
            context.settle(Duration.ofSeconds(10));  // let the session settle so the crash hits a
                                                     // steady state
        });

        context.stage("X1", "player B crashed (SIGKILL, no clean disconnect)", () -> {
            mark[0] = hostLog.lineCount();
            // The per-run token is the only reliable way to tell the joiner JVM from the host JVM:
            // ModDevGradle passes each run's program arguments via an @argfile, so the command line
            // carries "<run>RunProgramArgs" rather than the quick-play flags themselves.
            context.check(HostWorldSupport.runJvmAlive(JOINER_RUN),
                    "cannot find the joiner client JVM");
            HostWorldSupport.killRunJvms(JOINER_RUN);
            // Reap the Gradle wrapper around the dead JVM.
            players[0].joiner().stop(Duration.ofSeconds(20));
        });

        context.stage("X2", "the survivor is undisturbed: no continuity arm, no migration screen, "
                + "no errors", () -> {
            hostLog.awaitAfter("JoinerDev left the game", Duration.ofSeconds(120), mark[0]);
            hostLog.awaitAfter("member node(s)", Duration.ofSeconds(240), mark[0]);
            context.settle(Duration.ofSeconds(20));  // observation window: any delayed fallout shows
                                                     // up here

            context.check(HostWorldSupport.runJvmAlive(HOST_RUN),
                    "the HOST client died too — that is a disruption");
            context.check(!HostWorldSupport.containsAfter(hostLogFile, mark[0],
                            "Nodera continuity: host connection lost"),
                    "the survivor armed continuity recovery — the migration path ran on a live world");
            context.check(!HostWorldSupport.containsAfterIgnoringCase(hostLogFile, mark[0],
                            "nodera.continuity.migrating"),
                    "the survivor showed the migration screen");
            context.check(!HostWorldSupport.containsAfter(hostLogFile, mark[0],
                            "Exception in server tick loop"),
                    "the integrated server crashed");
            // Error audit, same allowlist discipline as the churn scenario's C3: abrupt kills make
            // benign netty noise.
            HostWorldSupport.requireNoErrors(hostLogFile, mark[0],
                    "X2: error lines after the crash");
            // The peers are unaffected by a game JVM dying — a worker that fell over would silently
            // thin the swarm below the quorum floor for the rejoin below.
            HostWorldSupport.probeWorkers(context);
        });

        context.stage("X3", "the world is still live: player B rejoins the same session", () -> {
            mark[0] = hostLog.lineCount();
            stack.startClient("runClientJoin", "client-rejoin.log");
            hostLog.awaitAfter("JoinerDev joined the game", Duration.ofSeconds(600), mark[0]);
        });

        stack.collectWorkerState();
    }
}
