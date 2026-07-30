package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.ControlClient;
import dev.nodera.testkit.harness.HarnessException;
import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.harness.ManagedProcess;
import dev.nodera.testkit.harness.PlayerRole;
import dev.nodera.testkit.harness.TestPaths;
import dev.nodera.testkit.harness.Topology;
import dev.nodera.testkit.suite.ScenarioContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The composite bring-up steps the host-world scenarios share, ported from {@code
 * scripts/lib/e2e-main.sh}.
 *
 * <h2>Why these live in one class rather than in each scenario</h2>
 *
 * <p>The shell launcher held exactly one copy of the two standard two-player shapes, the staged
 * world bake and the host-TOML editor, and the comment that justified moving the bake out of the
 * continuity suite is the reason this class exists too: while it belonged to one suite, "run
 * continuity first" was a hidden precondition of four other suites and forced them to share a
 * machine. A composite step written once is also a step whose evidence line can be changed once.
 *
 * <h2>What is here that the harness does not provide</h2>
 *
 * <p>Everything in this class is either a composition of {@link LiveStack} calls or one of the four
 * capabilities the harness has no equivalent for: staging a game directory's {@code options.txt},
 * editing the staged world's {@code nodera-server.toml}, auditing a log from a mark rather than from
 * its first line, and addressing one ModDevGradle run's JVM by its {@code <run>RunProgramArgs}
 * token. Each of those carries a {@code // HARNESS-GAP:} note where it is implemented.
 *
 * <p>Thread-context: stateless static helpers, called from the scenario's own thread.
 */
public final class HostWorldSupport {

    /**
     * A world baked before a {@code FlatWorldRules.RULES_VERSION} bump carries a certified genesis
     * the current engine refuses ("rulesVersion N does not match ..."), so the entity lane never
     * boots and every ownership assertion times out with no stated cause. Scenarios that REUSE the
     * bake guard on this the moment the host log exists.
     */
    public static final String STALE_BAKE_GUARD = "entity lane bootstrap failed";

    /** What to tell the reader when {@link #STALE_BAKE_GUARD} fires. */
    public static final String STALE_BAKE_MESSAGE =
            "the staged world's certified genesis predates the current FlatWorldRules.RULES_VERSION"
            + " — the entity lane cannot boot. Re-bake it: "
            + "rm -rf java/neoforge-mod/run-host/saves/NoderaE2E && re-run the continuity scenario";

    /**
     * Benign lines an abrupt client kill always produces; anything else at {@code ERROR}/
     * {@code FATAL} fails an audit.
     */
    public static final Pattern BENIGN_ERRORS = Pattern.compile(
            "Lost connection|Disconnected|Connection reset|closed by remote|InterruptedException");

    /** Connection causes this harness caused itself, for the netty header's follow-up line. */
    public static final Pattern BENIGN_NETTY =
            Pattern.compile("Connection reset|ClosedChannelException|closed by remote");

    /** {@code client validation lane active on N region(s)} — the count is the assertion. */
    private static final Pattern LANE_REGIONS = Pattern.compile("active on (\\d+)");

    /** The {@code (-1234.5d)} numbers of a {@code data get entity <p> Pos} reply. */
    // Bounded and possessive on purpose: this runs over a server reply, which is input the
    // harness does not control, and an unbounded greedy repetition makes `find()` re-scan the
    // same characters from every start position — quadratic in the length of the reply.
    private static final Pattern POSITION_NUMBER = Pattern.compile("(-?\\d{1,20}+(?:\\.\\d{1,20}+)?)d");

    /** The shared world every host-client scenario plays on; baked once, reused thereafter. */
    private static final String STAGED_WORLD = "NoderaE2E";

    private HostWorldSupport() {
    }

    // ---------------------------------------------------------------------------------------
    // The two standard two-player shapes
    // ---------------------------------------------------------------------------------------

    /**
     * Player A hosts the staged world from its client, player B joins over the network.
     *
     * <p>The shape for scenarios that kill a hosting PLAYER (continuity, ownership, churn, crash).
     * Player 1's slot is {@code run-host}, player 2's is {@code run-join}, and the returned handles
     * are what those scenarios kill.
     *
     * @param context the running scenario.
     * @return the host client and the joiner client.
     */
    public static HostedPair hostedTwoPlayers(ScenarioContext context) {
        LiveStack stack = context.stack();
        Topology topology = context.topology();
        writeClientConfig(context, "run-host", PlayerRole.PLAYER_ONE, null);
        writeClientConfig(context, "run-join", PlayerRole.PLAYER_TWO, null);

        LogWatcher hostLog = context.log("client-host.log");
        Path hostLogFile = stack.logDir().resolve("client-host.log");
        ManagedProcess host = stack.startClient("runClientHost", "client-host.log");
        hostLog.awaitJoin("game server open for joiners on port " + topology.gamePort(),
                topology.joinTimeout(), hostLogFile, "player A never opened the shared world");

        ManagedProcess joiner = stack.startClient("runClientJoin", "client-join.log");
        hostLog.awaitJoin("JoinerDev joined the game", topology.joinTimeout(),
                stack.logDir().resolve("client-join.log"), "player B never joined");
        return new HostedPair(host, joiner);
    }

    /**
     * A dedicated server plus JoinerDev (and JoinerTwo, and JoinerThree) with the entity lane live.
     *
     * <p>The shape for scenarios that interrogate a neutral server over RCON (commands, farlands,
     * pickup, ownership-follow, mobs, pearl, mesh-soak). Note the slot-to-directory mapping: player
     * 1's worker backs {@code run-join} and player 2's backs {@code run-join2}, because
     * {@link PlayerRole} names a role slot, not a game directory — the host-client shape above maps
     * the same two slots to {@code run-host} and {@code run-join}.
     *
     * <p>The second and third clients start only when the topology asks for them. They used to
     * launch unconditionally, so {@code withPlayers(1)} said one slot and started two anyway — and a
     * second real Minecraft client is the single most expensive thing in a run, which is what makes
     * a memory-tight machine unable to execute scenarios that need only one player.
     *
     * @param context the running scenario.
     * @return the server and the clients that were started.
     */
    public static DedicatedSession dedicatedTwoPlayers(ScenarioContext context) {
        LiveStack stack = context.stack();
        Topology topology = context.topology();

        stack.stageDedicatedServer(Map.of());
        ManagedProcess server = stack.startDedicatedServer("server.log");
        LogWatcher serverLog = context.log("server.log");
        serverLog.await("sharing world", Duration.ofSeconds(420));

        List<ManagedProcess> clients = new ArrayList<>();
        clients.add(joinDedicated(context, "run-join", PlayerRole.PLAYER_ONE,
                "runClientJoin", "client-join.log", "JoinerDev"));
        if (topology.players() >= 2) {
            clients.add(joinDedicated(context, "run-join2", PlayerRole.PLAYER_TWO,
                    "runClientJoinTwo", "client-join2.log", "JoinerTwo"));
        }
        // A third player is the Phase-1 exit gate's shape (issue #5): three nodes re-executing the
        // same regions, because two can only ever disagree pairwise.
        if (topology.players() >= 3) {
            clients.add(joinDedicated(context, "run-join3", PlayerRole.PLAYER_THREE,
                    "runClientJoinThree", "client-join3.log", "JoinerThree"));
        }
        awaitEntityLane(context, serverLog);
        return new DedicatedSession(server, List.copyOf(clients));
    }

    /**
     * Wait for the validated lane — or say, at once, that this build cannot have one.
     *
     * <p>{@code ValidationLane.DETERMINISTIC_VALIDATION} is {@code false}: deterministic
     * re-execution and committee co-signing are deliberately out of scope for the initial release,
     * and the host says so on every share — <i>"'world' runs WITHOUT deterministic region validation
     * — the deterministic validation lane is switched off for this release"</i>. So {@code "entity
     * lane live"} is a line this build never prints, and every scenario built on this setup spent
     * 300 s waiting for it and then failed as though something had broken.
     *
     * <p>A skip is the honest outcome and a timeout is not: the difference between "this build does
     * not have the feature" and "the feature is broken" is exactly what a suite exists to tell a
     * reader, and a five-minute wait for a compile-time constant tells them neither. The reason
     * names the constant, so whoever flips it knows which suites come back.
     */
    private static void awaitEntityLane(ScenarioContext context, LogWatcher serverLog) {
        if (serverLog.contains("runs WITHOUT deterministic region validation")) {
            context.skip("the deterministic validation lane is switched off in this build "
                    + "(ValidationLane.DETERMINISTIC_VALIDATION = false) — every stage below it "
                    + "asserts committee behaviour that cannot run. Flip that constant to bring "
                    + "this scenario back.");
        }
        serverLog.await("entity lane live", Duration.ofSeconds(300));
    }

    private static ManagedProcess joinDedicated(ScenarioContext context, String gameDirectory,
                                                PlayerRole role, String task, String logName,
                                                String playerName) {
        LiveStack stack = context.stack();
        writeClientConfig(context, gameDirectory, role, null);
        ManagedProcess client = stack.startClient(task, logName);
        context.log("server.log").awaitJoin(playerName + " joined the game",
                context.topology().joinTimeout(), stack.logDir().resolve(logName),
                playerName + " never joined");
        return client;
    }

    /** The host client and the joiner client of {@link #hostedTwoPlayers}. */
    public record HostedPair(ManagedProcess host, ManagedProcess joiner) {
    }

    /** The dedicated server and every client of {@link #dedicatedTwoPlayers}, in join order. */
    public record DedicatedSession(ManagedProcess server, List<ManagedProcess> clients) {

        /** The first joiner ({@code JoinerDev}, game directory {@code run-join}). */
        public ManagedProcess playerOne() {
            return clients.get(0);
        }

        /** The second joiner ({@code JoinerTwo}, game directory {@code run-join2}). */
        public ManagedProcess playerTwo() {
            return clients.get(1);
        }
    }

    // ---------------------------------------------------------------------------------------
    // The staged world
    // ---------------------------------------------------------------------------------------

    /**
     * The staged {@code NoderaE2E} world, baked on first use and re-pointed at this run's ports.
     *
     * @param context the running scenario.
     * @return the world's {@code nodera-server.toml}, for scenarios that set further keys on it.
     */
    public static Path stagedWorld(ScenarioContext context) {
        TestPaths paths = context.stack().paths();
        Topology topology = context.topology();
        Path save = paths.gameDir("run-host").resolve("saves").resolve(STAGED_WORLD);
        bakeStagedWorld(context, save);
        if (!Files.isRegularFile(save.resolve("nodera-world.dat"))) {
            throw new HarnessException("no staged world and the bake did not produce one: " + save);
        }

        Path config = save.resolve("serverconfig").resolve("nodera-server.toml");
        createDirectories(config.getParent());
        if (!Files.isRegularFile(config)) {
            write(config, "[host]\n");
        }
        // Pin the published game port (deterministic quick-play target) and disable Mojang session
        // auth — dev offline accounts cannot pass it; real hosts keep the secure default.
        setHostConfig(config, "host", "gamePort", String.valueOf(topology.gamePort()));
        setHostConfig(config, "host", "onlineAuth", "false");
        // The host side had NO tracker/rendezvous section at all, so the server fell back to
        // NoderaConfig's compiled 127.0.0.1:25600 / :25601 — which happen to be the launcher's
        // defaults, which is why it looked like it worked. Change the tracker port, ask for a second
        // tracker, or point at the official services, and the host announced into the void while
        // every other component used the real list.
        setHostConfig(config, "tracker", "endpoints", tomlArray(topology.trackerEndpoints()));
        setHostConfig(config, "rendezvous", "endpoints", tomlArray(topology.rendezvousEndpoints()));
        // No-host region ownership: the validated entity lane assigns every connected player's node
        // its own FOV region set.
        setHostConfig(config, "entity", "laneAutoActivate", "true");
        return config;
    }

    /**
     * Bake the shared world: a dedicated server boots, auto-shares (which mints the signed identity,
     * certifies genesis, and seeds the archive), and its finished save becomes the host client's
     * world. Idempotent — a world that already exists is left alone.
     */
    private static void bakeStagedWorld(ScenarioContext context, Path save) {
        if (Files.isRegularFile(save.resolve("nodera-world.dat"))) {
            return;
        }
        LiveStack stack = context.stack();
        TestPaths paths = stack.paths();
        context.note("baking the shared world " + STAGED_WORLD + " via runServer (auto-share)");
        stack.stageDedicatedServer(Map.of());
        ManagedProcess bake = stack.startDedicatedServer("bake-server.log");
        context.log("bake-server.log").await("sharing world", Duration.ofSeconds(420));
        // Identity + genesis + archive-seed all happen at share time, and the share path flushes the
        // save first — so a stop here leaves a complete world folder (Gradle's stdin does not reach
        // the server console, so a "stop" command cannot).
        context.settle(Duration.ofSeconds(8));
        bake.stop(Duration.ofSeconds(20));
        killRunJvms("serverRunProgramArgs");
        awaitRunJvmsGone("serverRunProgramArgs", Duration.ofSeconds(60));

        Path baked = paths.gameDir("run").resolve("world");
        if (!Files.isRegularFile(baked.resolve("nodera-world.dat"))) {
            throw new HarnessException("the bake persisted no nodera-world.dat in " + baked
                    + " — identity minting failed");
        }
        createDirectories(save.getParent());
        deleteRecursively(save);
        copyRecursively(baked, save);
    }

    /**
     * Set a key in the staged world's {@code nodera-server.toml}.
     *
     * <p>A missing key is inserted directly BELOW its section header, never appended at EOF: TOML
     * section membership is positional, so an appended key silently joins whichever section happens
     * to be last. That is how {@code mobCaptureDimensions} ends up under {@code [debug]}, the entity
     * lane never captures, and every region revokes mid-drive for no stated reason.
     *
     * @param config  the file to edit.
     * @param section the TOML section the key belongs to.
     * @param key     the key.
     * @param value   the value, already in TOML form (quoted strings, bare numbers, arrays).
     */
    // HARNESS-GAP: the harness writes a fresh nodera-server.toml for the dedicated server but has no
    // way to amend the staged world's own copy, which is what every host-client scenario tunes.
    public static void setHostConfig(Path config, String section, String key, String value) {
        List<String> lines = readLines(config);
        Pattern existing = Pattern.compile("^([ \\t]*)" + Pattern.quote(key) + " = ");
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = existing.matcher(lines.get(i));
            if (matcher.find()) {
                lines.set(i, matcher.group(1) + key + " = " + value);
                write(config, String.join("\n", lines) + "\n");
                return;
            }
        }
        String header = "[" + section + "]";
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().equals(header)) {
                lines.add(i + 1, "\t" + key + " = " + value);
                write(config, String.join("\n", lines) + "\n");
                return;
            }
        }
        lines.add(header);
        lines.add("\t" + key + " = " + value);
        write(config, String.join("\n", lines) + "\n");
    }

    // ---------------------------------------------------------------------------------------
    // Client staging
    // ---------------------------------------------------------------------------------------

    /**
     * Point a client's game directory at its own companion worker, and seed its first-run options.
     *
     * @param context       the running scenario.
     * @param gameDirectory {@code run-host}, {@code run-join}, {@code run-join2}, …
     * @param role          the worker slot this client's companion occupies.
     * @param joinPassword  the L-52 gate's joiner half, or {@code null} for an ungated world.
     */
    public static void writeClientConfig(ScenarioContext context, String gameDirectory,
                                         PlayerRole role, String joinPassword) {
        stageClientOptions(context.stack().paths(), gameDirectory);
        context.stack().writeClientConfig(gameDirectory, role, joinPassword);
    }

    /**
     * First-run vanilla options for a client game directory.
     *
     * <p>Written ONLY when the directory has no {@code options.txt} yet — a real one is the player's
     * (and Minecraft rewrites it on exit), so this seeds a first launch and never overwrites tuned
     * settings.
     *
     * <p>Why this exists: on a game directory with no {@code options.txt},
     * {@code Options.onboardAccessibility} defaults to TRUE and {@code Minecraft.addInitialScreens}
     * puts {@code AccessibilityOnboardingScreen} IN FRONT of quick play — quick play is the
     * onboarding screen's continue callback, so it simply never runs. Every scripted client boots
     * fine, ticks its loop, and sits on that screen forever. Invisible on a developer machine (whose
     * run directories kept an {@code options.txt} from the first manual launch) and fatal in CI on a
     * fresh checkout: it is exactly why the three live jobs each burned thirty minutes and reported
     * "never joined".
     */
    // HARNESS-GAP: LiveStack.writeClientConfig writes nodera-client.toml only, so without this every
    // fresh game directory parks its client on the accessibility onboarding screen.
    public static void stageClientOptions(TestPaths paths, String gameDirectory) {
        Path directory = paths.gameDir(gameDirectory);
        createDirectories(directory);
        Path options = directory.resolve("options.txt");
        if (Files.isRegularFile(options)) {
            return;
        }
        // onboardAccessibility  — the blocker above; must be false before the first frame.
        // skipMultiplayerWarning — the third-party-server notice sits in front of the multiplayer
        //                          screen for any scenario that drives the GUI.
        // pauseOnLostFocus      — under Xvfb there is no window manager, so a client may never be
        //                          "active"; a paused singleplayer client stops ticking and would
        //                          never share its world.
        write(options, """
                onboardAccessibility:false
                skipMultiplayerWarning:true
                pauseOnLostFocus:false
                """);
    }

    // ---------------------------------------------------------------------------------------
    // Process control: one ModDevGradle run's JVM
    // ---------------------------------------------------------------------------------------

    /**
     * The PID of the Minecraft JVM belonging to one ModDevGradle run.
     *
     * <p>ModDevGradle passes each run's program arguments via an {@code @argfile}, so the JVM
     * command line carries {@code <run>RunProgramArgs} — NOT the quick-play flags themselves. That
     * per-run token is the only reliable way to tell the joiner JVM from the host JVM: matching a
     * bare {@code RunProgramArgs} also matches the dedicated SERVER, and a scenario that killed it
     * by accident then dials a game port nothing is listening on — a "the joiner never joined"
     * failure with an innocent client.
     *
     * @param runToken e.g. {@code clientJoinRunProgramArgs}.
     * @return the first matching PID, or empty when that run has no live JVM.
     */
    // HARNESS-GAP: ManagedProcess addresses the Gradle wrapper this harness started, but the game
    // JVM is a child of the Gradle DAEMON, so a crash scenario cannot reach it through the wrapper.
    public static OptionalLong findRunJvm(String runToken) {
        try (Stream<ProcessHandle> processes = ProcessHandle.allProcesses()) {
            return processes
                    .filter(handle -> handle.info().commandLine().orElse("").contains(runToken))
                    .mapToLong(ProcessHandle::pid)
                    .findFirst();
        }
    }

    /** @return {@code true} while that run's JVM is alive. */
    public static boolean runJvmAlive(String runToken) {
        return findRunJvm(runToken).isPresent();
    }

    /**
     * SIGKILL every JVM of one run — a real crash: no disconnect packet, no shutdown hook, the TCP
     * peer just goes silent.
     */
    public static void killRunJvms(String runToken) {
        forEachRunJvm(runToken, ProcessHandle::destroyForcibly);
    }

    /** Ask every JVM of one run to exit — the ordinary "the player closed the game" departure. */
    public static void stopRunJvms(String runToken) {
        forEachRunJvm(runToken, ProcessHandle::destroy);
    }

    /**
     * Stop ONE client and wait for it to be gone: its Gradle wrapper, then its game JVM.
     *
     * <p>Both halves are needed. The game JVM is a child of the GRADLE DAEMON, not of the wrapper
     * this harness started, so stopping the wrapper's tree is not enough on its own; and the run
     * token is what keeps the kill off every other JVM in the run — a bare match on
     * {@code RunProgramArgs} also hits the dedicated server, and the next stage then dials a game
     * port nothing is listening on.
     *
     * @param wrapper  the Gradle process the harness started, or {@code null} when only the JVM is
     *                 known.
     * @param runToken the run's {@code <run>RunProgramArgs} token.
     */
    public static void stopClient(ManagedProcess wrapper, String runToken) {
        if (wrapper != null) {
            wrapper.stop(Duration.ofSeconds(20));
        }
        stopRunJvms(runToken);
        awaitRunJvmsGone(runToken, Duration.ofSeconds(30));
    }

    private static void forEachRunJvm(String runToken, java.util.function.Consumer<ProcessHandle> action) {
        try (Stream<ProcessHandle> processes = ProcessHandle.allProcesses()) {
            processes.filter(handle -> handle.info().commandLine().orElse("").contains(runToken))
                    .forEach(action);
        }
    }

    /** Wait until no JVM of that run is left, so the next scenario's preflight sees free ports. */
    public static void awaitRunJvmsGone(String runToken, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!runJvmAlive(runToken)) {
                return;
            }
            sleep(Duration.ofSeconds(2));
        }
    }

    /**
     * Every worker must answer its control socket.
     *
     * <p>A worker that died on a bound port turns every later assertion into an unexplained timeout,
     * and after a scenario has deliberately killed a game JVM it is worth re-asking: a companion
     * that fell over with its game would silently thin the swarm below the quorum floor.
     */
    public static void probeWorkers(ScenarioContext context) {
        List<ControlClient> workers = context.stack().workers();
        for (int i = 0; i < workers.size(); i++) {
            if (!workers.get(i).isUp()) {
                PlayerRole role = context.topology().workerRole(i);
                throw new HarnessException("worker " + role.cliName() + " did not answer "
                        + "NODERA-PROBE on control port " + workers.get(i).port()
                        + " (see " + context.stack().logDir().resolve(
                                "worker-" + role.cliName() + ".log") + ")");
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Reading logs from a mark
    // ---------------------------------------------------------------------------------------

    /**
     * The non-benign {@code ERROR}/{@code FATAL} lines after {@code fromLine}, capped at six.
     *
     * <p>Vanilla's "Exception caught in connection" header is benign exactly when its cause is the
     * reset or close of a peer this harness itself killed; any other cause still counts, so the
     * audit reads the following line rather than the header.
     *
     * @return the offending lines; empty means clean.
     */
    // HARNESS-GAP: LogWatcher.auditErrors always audits the whole file, and every scenario that
    // kills something audits only what happened afterwards.
    public static List<String> auditErrors(Path file, int fromLine) {
        List<String> lines = readLines(file);
        List<String> offenders = new ArrayList<>();
        for (int i = Math.max(0, fromLine); i < lines.size() && offenders.size() < 6; i++) {
            String line = lines.get(i);
            if (line.contains("Exception caught in connection")) {
                String cause = i + 1 < lines.size() ? lines.get(i + 1) : "";
                if (!BENIGN_NETTY.matcher(cause).find()) {
                    offenders.add(line);
                    offenders.add(cause);
                }
                i++;
                continue;
            }
            if ((line.contains("ERROR") || line.contains("FATAL"))
                    && !BENIGN_ERRORS.matcher(line).find()) {
                offenders.add(line);
            }
        }
        return offenders;
    }

    /** Fail with the offending lines when the log picked up errors after {@code fromLine}. */
    public static void requireNoErrors(Path file, int fromLine, String what) {
        List<String> offenders = auditErrors(file, fromLine);
        if (!offenders.isEmpty()) {
            throw new HarnessException(what + ": " + String.join(" | ", offenders));
        }
    }

    /** @return {@code true} if {@code needle} appears at or after line {@code fromLine}. */
    public static boolean containsAfter(Path file, int fromLine, String needle) {
        List<String> lines = readLines(file);
        for (int i = Math.max(0, fromLine); i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** {@link #containsAfter} ignoring case — the shell's {@code grep -i} assertions. */
    public static boolean containsAfterIgnoringCase(Path file, int fromLine, String needle) {
        String lowered = needle.toLowerCase(Locale.ROOT);
        List<String> lines = readLines(file);
        for (int i = Math.max(0, fromLine); i < lines.size(); i++) {
            if (lines.get(i).toLowerCase(Locale.ROOT).contains(lowered)) {
                return true;
            }
        }
        return false;
    }

    /** The last line at or after {@code fromLine} containing {@code needle}. */
    public static Optional<String> lastMatchAfter(Path file, int fromLine, String needle) {
        List<String> lines = readLines(file);
        for (int i = lines.size() - 1; i >= Math.max(0, fromLine); i--) {
            if (lines.get(i).contains(needle)) {
                return Optional.of(lines.get(i));
            }
        }
        return Optional.empty();
    }

    /** Every line at or after {@code fromLine} containing {@code needle}, in file order. */
    public static List<String> matchesAfter(Path file, int fromLine, String needle) {
        List<String> lines = readLines(file);
        List<String> out = new ArrayList<>();
        for (int i = Math.max(0, fromLine); i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                out.add(lines.get(i));
            }
        }
        return out;
    }

    /** The lines of a file, or an empty list when it does not exist yet. */
    public static List<String> readLines(Path file) {
        if (!Files.isReadable(file)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException partiallyWritten) {
            // A log being appended to as it is read can hand back a malformed tail; treat that as
            // "nothing yet" rather than as a harness failure.
            return new ArrayList<>();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Evidence parsing
    // ---------------------------------------------------------------------------------------

    /**
     * The region count of a {@code client validation lane active on N region(s)} line.
     *
     * <p>Under field-of-view ownership the committee seats sit on the PLAYERS' client lanes, so this
     * number — not a worker's counter — is what says a player validated anything.
     */
    public static OptionalInt laneRegions(String laneLine) {
        if (laneLine == null) {
            return OptionalInt.empty();
        }
        Matcher matcher = LANE_REGIONS.matcher(laneLine);
        return matcher.find() ? OptionalInt.of(Integer.parseInt(matcher.group(1)))
                : OptionalInt.empty();
    }

    /**
     * Whether a {@code data get entity <p> Pos} reply parses and is within {@code tolerance} blocks
     * of {@code (x, z)}.
     *
     * <p>Unparseable counts as "not there": generating terrain far from spawn puts the server tens
     * of seconds behind on a two-core runner, and a read taken inside that window comes back empty
     * although the teleport had in fact worked.
     */
    public static boolean positionWithin(String reply, double x, double z, double tolerance) {
        if (reply == null) {
            return false;
        }
        Matcher matcher = POSITION_NUMBER.matcher(reply);
        List<Double> numbers = new ArrayList<>();
        while (matcher.find()) {
            numbers.add(Double.parseDouble(matcher.group(1)));
        }
        if (numbers.size() < 3) {
            return false;
        }
        return Math.abs(numbers.get(0) - x) <= tolerance && Math.abs(numbers.get(2) - z) <= tolerance;
    }

    // ---------------------------------------------------------------------------------------
    // Transcripts
    // ---------------------------------------------------------------------------------------

    /**
     * Append a line to one of the run's transcripts under the results directory.
     *
     * <p>The transcripts are the half of a live run a stage name cannot carry: what the world
     * actually replied, which is the only thing a reader can re-interpret months later.
     */
    public static void transcript(ScenarioContext context, String fileName, String text) {
        Path file = context.stack().resultsDir().resolve(fileName);
        try {
            createDirectories(file.getParent());
            Files.writeString(file, text + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new HarnessException("cannot append to the transcript " + file, e);
        }
    }

    // ---------------------------------------------------------------------------------------

    /** {@code ["a", "b"]} for a TOML config file. */
    public static String tomlArray(List<String> values) {
        return values.stream().map(value -> '"' + value + '"')
                .reduce((a, b) -> a + ", " + b).map(joined -> "[" + joined + "]").orElse("[]");
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HarnessException("interrupted while waiting", e);
        }
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + directory, e);
        }
    }

    private static void write(Path file, String content) {
        try {
            createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new HarnessException("cannot write " + file, e);
        }
    }

    private static void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: a file still held by a dying JVM is reaped with the directory.
                }
            });
        } catch (IOException e) {
            throw new HarnessException("cannot clear " + directory, e);
        }
    }

    private static void copyRecursively(Path from, Path to) {
        try (Stream<Path> walk = Files.walk(from)) {
            walk.forEach(source -> {
                Path target = to.resolve(from.relativize(source).toString());
                try {
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot copy " + source + " to " + target, e);
                }
            });
        } catch (IOException e) {
            throw new HarnessException("cannot copy " + from + " to " + to, e);
        }
    }
}
