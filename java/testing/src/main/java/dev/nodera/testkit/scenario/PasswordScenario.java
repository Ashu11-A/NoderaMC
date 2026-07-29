package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.harness.ManagedProcess;
import dev.nodera.testkit.harness.PlayerRole;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * THE LIVE-JOIN PASSWORD GATE (L-52).
 *
 * <pre>
 *   W0  the standard topology and a clean-slate dedicated server that auto-shares its world WITH a
 *       password ({@code host.sharePassword}) — the world is listed and reachable exactly as an open
 *       one is
 *   W1  a joiner with NO password connects. The exit: it is REFUSED at the game server — the host
 *       logs the refusal, the client never appears in the world, and the player list stays empty
 *   W2  the SAME client, now carrying the world password in its own config, connects again and joins
 *       normally. Without this half the scenario would pass just as well against a gate that refuses
 *       everyone
 *   W3  transcripts + worker STATE snapshots collected
 * </pre>
 *
 * <p>The gap this closes: the world password protected the archived CONTENT plane only. A listed
 * world's game route is public, so anyone who resolved it could connect and play in a
 * "password-protected" world.
 *
 * <p>Thread-context: stateless; the runner calls {@link #run} on its own thread.
 */
public final class PasswordScenario implements Scenario {

    private static final String WORLD_PASSWORD = "e2e-correct-horse";

    /** The MDG run id of the joining client, for the targeted stop between W1 and W2. */
    private static final String JOIN_RUN_ID = "clientJoin";

    @Override
    public String id() {
        return "password";
    }

    @Override
    public String title() {
        return "a password-gated world refuses a joiner without the password and admits one with it";
    }

    @Override
    public Set<String> tags() {
        return Set.of("live", "password");
    }

    @Override
    public Requirements requirements() {
        return Requirements.liveClients(5);
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        LiveStack stack = ctx.stack();
        LogWatcher server = ctx.log("server.log");

        // --- W0: stack + a password-protected dedicated server -------------------------------
        ctx.stage("W0", "the world is shared and password-gated", () -> {
            stack.stageDedicatedServer(Map.of("sharePassword", WORLD_PASSWORD));
            stack.startDedicatedServer("server.log");
            server.await("sharing world", Duration.ofSeconds(420));
            // Shared is not gated. Without this line the world would be open to anyone who
            // resolved it, which is the whole defect L-52 names.
            server.await("is password-gated", Duration.ofSeconds(120));
        });

        // --- W1: a joiner without the password is refused -------------------------------------
        ManagedProcess[] joiner = new ManagedProcess[1];
        ctx.stage("W1", "the passwordless joiner is refused at the game server", () -> {
            stack.writeClientConfig("run-join", PlayerRole.PLAYER_ONE, null);   // no join password
            joiner[0] = stack.startClient("runClientJoin", "client-join-nopw.log");

            server.await("refused a joiner at the password gate", Duration.ofSeconds(600));
            // The refusal is only meaningful if it happened INSTEAD of a join, not after one.
            ctx.check(!server.contains("JoinerDev joined the game"),
                    "W1: the joiner reached the world despite having no password");
            String players = stack.rcon().require("list");
            ctx.checkAbsent(players, "JoinerDev",
                    "W1: the refused joiner is in the player list");
        });

        // The refused client stays up on its disconnect screen, and W2 reuses its game dir — stop
        // THAT client (only it: the dedicated server must keep listening for W2) and wait for it to
        // be gone rather than racing its session lock.
        ServerDedicatedDrive.stopClient(JOIN_RUN_ID, joiner[0]);
        ctx.settle(Duration.ofSeconds(3));

        // --- W2: the same client WITH the password joins --------------------------------------
        ctx.stage("W2", "the correct password joins normally — the gate refuses, it does not block "
                + "everyone", () -> {
            stack.writeClientConfig("run-join", PlayerRole.PLAYER_ONE, WORLD_PASSWORD);
            stack.startClient("runClientJoin", "client-join-pw.log");
            server.awaitJoin("JoinerDev joined the game", Duration.ofSeconds(600),
                    stack.logDir().resolve("client-join-pw.log"),
                    "W2: the joiner with the correct password never joined");
            String players = stack.rcon().require("list");
            ctx.checkContains(players, "JoinerDev",
                    "W2: joined, but the player list disagrees");
        });

        // --- W3: artefacts ---------------------------------------------------------------------
        ctx.stage("W3", "worker STATE snapshots + log collection", () -> {
            stack.collectWorkerState();
            stack.collectArtefacts();
        });
    }
}
