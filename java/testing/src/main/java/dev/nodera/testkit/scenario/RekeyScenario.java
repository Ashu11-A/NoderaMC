package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.HarnessException;
import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.harness.ManagedProcess;
import dev.nodera.testkit.harness.PlayerRole;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * THE AUTHOR CHANGES THE WORLD PASSWORD (L-51 / issue #37).
 *
 * <pre>
 *   R0   the standard topology + a clean-slate dedicated server that auto-shares its world under
 *        password A, with the live-join gate armed
 *   R1   a joiner carrying password A joins — the baseline the re-key has to invalidate
 *   R2   the author re-keys to password B ({@code /nodera share password <B>} over RCON; the Share
 *        screen drives the same {@code NoderaHost.reconfigure}). The exit evidence: the save is
 *        re-packed and re-encrypted under a fresh salt, the manifest root changes, the identity is
 *        re-signed, and the worker seeds + announces the NEW version
 *   R2b  the continuous lane must not publish the save in the clear (L-59)
 *   R3   the SAME joiner reconnects with the OLD password and is refused at the game server;
 *        reconnecting with the NEW password joins normally
 *   R4   transcripts + worker STATE snapshots collected
 * </pre>
 *
 * <p>Thread-context: stateless; the runner calls {@link #run} on its own thread.
 */
public final class RekeyScenario implements Scenario {

    private static final String PASSWORD_A = "e2e-alpha";
    private static final String PASSWORD_B = "e2e-bravo";

    /**
     * A fast streaming cadence (10 s instead of 2 min).
     *
     * <p>R2b has to watch what the CONTINUOUS lane publishes for a password-protected world, and
     * waiting two minutes per observation is the difference between an assertion and a hope.
     */
    private static final String STREAM_INTERVAL_TICKS = "200";

    /** The MDG run id of the joining client, for the targeted stops between stages. */
    private static final String JOIN_RUN_ID = "clientJoin";

    @Override
    public String id() {
        return "rekey";
    }

    @Override
    public String title() {
        return "re-keying a world invalidates the old password at the live gate and re-seeds "
                + "ciphertext";
    }

    @Override
    public Set<String> tags() {
        return Set.of("live", "password", "rekey");
    }

    @Override
    public Requirements requirements() {
        return Requirements.liveClients(5);
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        LiveStack stack = ctx.stack();
        LogWatcher server = ctx.log("server.log");
        // The companion worker of the joining player. LiveStack names its log by role, so this is
        // the same file the shell called worker-peer1.log.
        LogWatcher worker = ctx.log("worker-" + PlayerRole.PLAYER_ONE.cliName() + ".log");
        Path transcript = stack.resultsDir().resolve("rekey.log");

        // --- R0: stack + a password-protected dedicated server -------------------------------
        ctx.stage("R0", "the world is shared under password A and gated", () -> {
            stack.stageDedicatedServer(Map.of(
                    "sharePassword", PASSWORD_A,
                    "streamIntervalTicks", STREAM_INTERVAL_TICKS));
            stack.startDedicatedServer("server.log");
            server.await("sharing world", Duration.ofSeconds(420));
            server.await("is password-gated", Duration.ofSeconds(120));
        });

        // --- R1: a joiner with password A joins ------------------------------------------------
        ManagedProcess[] joiner = new ManagedProcess[1];
        ctx.stage("R1", "password A joins — the baseline the re-key must invalidate", () -> {
            stack.writeClientConfig("run-join", PlayerRole.PLAYER_ONE, PASSWORD_A);
            joiner[0] = stack.startClient("runClientJoin", "client-join-a.log");
            server.awaitJoin("JoinerDev joined the game", Duration.ofSeconds(600),
                    stack.logDir().resolve("client-join-a.log"),
                    "R1: the joiner with password A never joined");
        });
        ServerDedicatedDrive.stopClient(JOIN_RUN_ID, joiner[0]);

        // --- R2: the author re-keys to password B ----------------------------------------------
        ctx.stage("R2", "re-keyed — new salt, new ciphertext, new manifest root, re-signed "
                + "identity, re-announced", () -> {
            int mark = server.lineCount();
            int workerMark = worker.lineCount();
            String reply = stack.rcon().require("nodera share password " + PASSWORD_B);
            append(transcript, "=== /nodera share password: " + reply);

            server.awaitAfter("password re-key complete", Duration.ofSeconds(300), mark);
            // The ciphertext the network holds must be the NEW one: the worker seeds and announces a
            // fresh ENCRYPTED manifest version. The word matters — a plaintext "Seeding world
            // archive" line would satisfy a looser needle while proving the opposite.
            worker.awaitAfter("Seeding ENCRYPTED world archive", Duration.ofSeconds(300),
                    workerMark);
            for (String line : linesAfter(server.file(), mark)) {
                if (line.contains("password re-key complete")) {
                    append(transcript, line);
                }
            }
        });

        // --- R2b: the continuous lane must not publish the save in the clear (L-59) ------------
        // Found live, and the reason this stage exists: the streaming lane kept seeding PLAINTEXT
        // archives on its cadence, each newer than the ciphertext — so a password-protected world
        // was served in the clear to anyone who fetched its newest version.
        ctx.stage("R2b", "every refresh of a password-protected world is ciphertext", () -> {
            int plainMark = worker.lineCount();
            ctx.settle(Duration.ofSeconds(45));   // several streaming intervals at 200 ticks
            for (String line : linesAfter(worker.file(), plainMark)) {
                if (line.contains("Seeding world archive") && !line.contains("ENCRYPTED")) {
                    ctx.fail("R2b: the streaming lane published this password-protected world in "
                            + "the CLEAR: " + line);
                }
                if (line.contains("Seeding")) {
                    append(transcript, line);
                }
            }
        });

        // --- R3: the old password is refused, the new one joins --------------------------------
        ManagedProcess[] stale = new ManagedProcess[1];
        ctx.stage("R3a", "the old password is refused at the game server", () -> {
            int mark = server.lineCount();
            stack.writeClientConfig("run-join", PlayerRole.PLAYER_ONE, PASSWORD_A);
            stale[0] = stack.startClient("runClientJoin", "client-join-old.log");
            server.awaitAfter("refused a joiner at the password gate", Duration.ofSeconds(600), mark);
            for (String line : linesAfter(server.file(), mark)) {
                ctx.check(!line.contains("JoinerDev joined the game"),
                        "R3: the stale password still got into the world");
            }
        });
        ServerDedicatedDrive.stopClient(JOIN_RUN_ID, stale[0]);

        ctx.stage("R3b", "the new password joins — the re-key moved the gate, it did not close it",
                () -> {
            stack.writeClientConfig("run-join", PlayerRole.PLAYER_ONE, PASSWORD_B);
            stack.startClient("runClientJoin", "client-join-b.log");
            server.awaitJoin("JoinerDev joined the game", Duration.ofSeconds(600),
                    stack.logDir().resolve("client-join-b.log"),
                    "R3: the new password never joined");
        });

        // --- R4: artefacts -----------------------------------------------------------------------
        ctx.stage("R4", "worker STATE snapshots + log collection", () -> {
            stack.collectWorkerState();
            stack.collectArtefacts();
        });
    }

    /**
     * The lines of {@code file} after a mark.
     *
     * <p>R2b and R3 assert on the ABSENCE of a pattern in a window, which no wait can express; see
     * {@link ServerLogs} for the harness gap that puts the reader there.
     */
    private static List<String> linesAfter(Path file, int mark) {
        return ServerLogs.linesAfter(file, mark);
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
