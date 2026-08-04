package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.HarnessException;
import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.harness.ManagedProcess;
import dev.nodera.testkit.harness.PlayerRole;
import dev.nodera.testkit.suite.ScenarioContext;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The dedicated-server player drive: a NeoForge server, its joiners, and a live entity lane.
 *
 * <p>The Java form of {@code nodera_dedicated_two_players} in {@code scripts/lib/e2e-main.sh}. It is
 * the shape for scenarios that interrogate a neutral server over RCON, and it is here rather than in
 * one scenario because two of them (determinism and profile) need exactly the same bring-up.
 *
 * <h2>Role slots are not game directories</h2>
 *
 * <p>The launcher's comment is worth carrying: "Player 1 / player 2 are role slots, not game dirs —
 * a host-client suite maps them to run-host + run-join, a dedicated-server suite to run-join +
 * run-join2." So {@link PlayerRole#PLAYER_ONE}'s companion worker belongs to {@code JoinerDev} in
 * {@code run-join} here, even though the same role means {@code HostDev} in a host-client scenario.
 * The mapping is stated in {@link #SLOTS} rather than implied by a port number.
 *
 * <p>Thread-context: static helpers, called from the scenario's thread.
 */
public final class ServerDedicatedDrive {

    /** One player slot of a dedicated-server run. */
    public record Slot(PlayerRole role, String gameDirectory, String playerName,
                       String gradleTask, String logName) { }

    /**
     * The three slots, in join order.
     *
     * <p>A third player is the Phase-1 exit gate's shape (issue #5): three nodes re-executing the
     * same regions, because two can only ever disagree pairwise.
     */
    public static final List<Slot> SLOTS = List.of(
            new Slot(PlayerRole.PLAYER_ONE, "run-join", "JoinerDev",
                    "runClientJoin", "client-join.log"),
            new Slot(PlayerRole.PLAYER_TWO, "run-join2", "JoinerTwo",
                    "runClientJoinTwo", "client-join2.log"),
            new Slot(PlayerRole.PLAYER_THREE, "run-join3", "JoinerThree",
                    "runClientJoinThree", "client-join3.log"));

    private ServerDedicatedDrive() {
    }

    /**
     * Stage and start the dedicated server, then bring every player of the topology into the world.
     *
     * <p>The second and third clients only launch when the topology asks for them. A second real
     * Minecraft client is the single most expensive thing in a run, which is what makes a
     * memory-tight machine unable to execute scenarios that need only one player.
     *
     * @param context        the scenario's context.
     * @param hostOverrides  {@code sharePassword} / {@code streamIntervalTicks} for the staged
     *                       {@code nodera-server.toml}.
     * @return the client processes, in join order, so a caller can stop one of them by name.
     */
    public static Map<Slot, ManagedProcess> start(ScenarioContext context,
                                                  Map<String, String> hostOverrides) {
        LiveStack stack = context.stack();
        stack.stageDedicatedServer(hostOverrides);
        stack.startDedicatedServer("server.log");
        LogWatcher server = context.log("server.log");
        server.await("sharing world", Duration.ofSeconds(420));

        Map<Slot, ManagedProcess> clients = new LinkedHashMap<>();
        int players = Math.min(context.topology().players(), SLOTS.size());
        for (int i = 0; i < players; i++) {
            Slot slot = SLOTS.get(i);
            stack.writeClientConfig(slot.gameDirectory(), slot.role(), null);
            clients.put(slot, stack.startClient(slot.gradleTask(), slot.logName()));
            server.awaitJoin(slot.playerName() + " joined the game",
                    context.topology().joinTimeout(),
                    stack.logDir().resolve(slot.logName()),
                    slot.playerName() + " never joined");
        }
        server.await("entity lane live", Duration.ofSeconds(300));
        return clients;
    }

    /**
     * Stop ONE client and wait for it to be gone.
     *
     * <p>The run id is MDG's per-run token ({@code clientJoin}, {@code clientJoinTwo},
     * {@code clientHost}, …), which appears in that JVM's command line as
     * {@code <run>RunProgramArgs}. Matching it is the whole point: killing everything that looks
     * like a game JVM also kills the dedicated SERVER, and the next stage then dials a game port
     * nothing is listening on — a "the joiner never joined" failure with an innocent client.
     *
     * <p>The game JVM is a child of the GRADLE DAEMON, not of the wrapper the harness started, so
     * stopping the wrapper's process tree is not enough on its own.
     *
     * <h2>One matcher, one guard</h2>
     *
     * <p>This used to shell out to {@code pkill -f -- "<runId>RunProgramArgs"}, which was a second,
     * independent answer to "which process is this run's game JVM" — and the unguarded one. The
     * Gradle daemon serves every build in a run, so its command line names EVERY client's argfile:
     * a bare {@code pkill} on one client's token also matches the process that owns the others, and
     * signalling it takes them all down at once. {@link HostWorldSupport} learned that the hard way
     * and carries the daemon exclusion; delegating to it means the rule is written once and cannot
     * drift, rather than being fixed in one copy and left wrong in the other.
     *
     * @param runId   the MDG run id, e.g. {@code clientJoin}.
     * @param wrapper the Gradle wrapper process, if the caller has it.
     */
    public static void stopClient(String runId, ManagedProcess wrapper) {
        HostWorldSupport.stopClient(wrapper, runId + "RunProgramArgs");
    }

    /**
     * Wait for a port to come free, the way the shell's {@code check_ports} does.
     *
     * <p>Stopping a server is not enough on its own to release the game port: the dev CLIENTS are
     * still up, still holding their connections, and the listener stays bound for several seconds
     * after the server thread exits. The first attempt at the profile suite stopped only the server,
     * slept 8 s, and Paper died with "bind(..) failed: Address already in use" — a stage that then
     * reports the platform as broken when the only thing wrong was our own teardown.
     */
    public static void awaitPortFree(int port, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (isBound(port)) {
            if (System.nanoTime() > deadline) {
                throw new HarnessException("port " + port + " is still held after "
                        + timeout.toSeconds() + "s");
            }
            sleep(Duration.ofSeconds(2));
        }
    }

    private static boolean isBound(int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException nothingListening) {
            return false;
        }
    }

    /** Run a command, returning its exit code; a command that cannot start counts as "no match". */
    static int run(List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            return builder.start().waitFor();
        } catch (IOException notInstalled) {
            return 127;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HarnessException("interrupted while running " + String.join(" ", command), e);
        }
    }

    static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HarnessException("interrupted while waiting", e);
        }
    }
}
