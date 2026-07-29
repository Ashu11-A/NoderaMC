package dev.nodera.testkit.harness;

import dev.nodera.testkit.mc.RconClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The live stack: services, workers, servers and clients, started once and torn down once.
 *
 * <h2>One launcher, one topology</h2>
 *
 * <p>No scenario starts a tracker, a rendezvous, a worker, a dedicated server or a client itself.
 * It asks this class, which is the single place the port plan, the process order, the readiness
 * conditions and the teardown live. When each of twenty shell suites did its own launching, they
 * drifted: two of them waited on different evidence for the same service, and a third had a port
 * the others did not know about.
 *
 * <h2>Order is a correctness property, not a convenience</h2>
 *
 * <ol>
 *   <li><b>Lock</b> — the suites share the port block and the Minecraft run directories, so they
 *       run strictly one at a time.</li>
 *   <li><b>Preflight</b> — every port the topology will bind must come free first, with a bounded
 *       wait for the previous run's JVMs.</li>
 *   <li><b>Discovery services</b>, then <b>workers</b> (each with its {@link PlayerRole}), then the
 *       game. A worker that starts before its tracker announces into the void and the run measures
 *       a network that never formed.</li>
 * </ol>
 *
 * <p>Teardown runs in reverse start order and is idempotent, so a scenario that fails halfway still
 * leaves the machine usable for the next one in the queue.
 *
 * <p>Thread-context: one stack per scenario, used from the scenario's thread.
 */
public final class LiveStack implements AutoCloseable {

    private final TestPaths paths;
    private final Topology topology;
    private final Path logDir;
    private final Path resultsDir;
    private final Deque<ManagedProcess> started = new ArrayDeque<>();
    private final List<ControlClient> workers = new ArrayList<>();
    private final Map<PlayerRole, ControlClient> workersByRole = new LinkedHashMap<>();
    private final boolean keepRunning;

    private FileChannel lockChannel;
    private FileLock lock;
    private ManagedProcess dedicatedServer;

    /**
     * Build a stack for one scenario. Called by the runner: a scenario is handed a stack that is
     * already up, so that "did the infrastructure start" is the runner's failure and not a stage
     * every scenario has to write for itself.
     */
    public LiveStack(TestPaths paths, Topology topology, Path resultsDir, boolean keepRunning) {
        this.paths = paths;
        this.topology = topology;
        this.resultsDir = resultsDir;
        this.logDir = resultsDir.resolve("logs");
        this.keepRunning = keepRunning;
        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + logDir, e);
        }
    }

    public TestPaths paths() {
        return paths;
    }

    public Topology topology() {
        return topology;
    }

    /** Where this run's service logs are written. */
    public Path logDir() {
        return logDir;
    }

    /** Where this run's artefacts are collected. */
    public Path resultsDir() {
        return resultsDir;
    }

    /** A watcher over one of this run's log files. */
    public LogWatcher log(String fileName) {
        return new LogWatcher(logDir.resolve(fileName), topology);
    }

    /** A watcher over an arbitrary file (a game directory's {@code latest.log}, for instance). */
    public LogWatcher watch(Path file) {
        return new LogWatcher(file, topology);
    }

    /** The control client of worker {@code index}. */
    public ControlClient worker(int index) {
        return workers.get(index);
    }

    /** The control client of the worker launched for {@code role}. */
    public ControlClient worker(PlayerRole role) {
        ControlClient client = workersByRole.get(role);
        if (client == null) {
            throw new HarnessException("no worker was started for role " + role.cliName()
                    + " — this topology has " + topology.players() + " player(s)");
        }
        return client;
    }

    /** Every worker's control client, in start order. */
    public List<ControlClient> workers() {
        return List.copyOf(workers);
    }

    /** An RCON client for the dedicated/Paper server this run started. */
    public RconClient rcon() {
        return new RconClient(topology.rconPort(), topology.rconPassword(), topology);
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    /**
     * Take the exclusive live-suite lock.
     *
     * <p>Two live runs on one machine do not fail cleanly: they fight over ports and over the
     * Minecraft run directories, and the loser reports a defect in the product. The lock is a file
     * lock rather than a PID file so that a killed run releases it without cleanup.
     */
    public void acquireLock(Duration timeout) {
        try {
            Files.createDirectories(paths.lockFile().getParent());
            lockChannel = FileChannel.open(paths.lockFile(), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                lock = lockChannel.tryLock();
                if (lock != null) {
                    return;
                }
                if (System.nanoTime() > deadline) {
                    throw new HarnessException("another live run holds " + paths.lockFile()
                            + " — live suites run one at a time");
                }
                Topology.sleep(Duration.ofSeconds(3));
            }
        } catch (IOException e) {
            throw new HarnessException("cannot take the live-suite lock " + paths.lockFile(), e);
        }
    }

    /** Start the discovery services and the workers, and wait until every one of them answers. */
    public void startInfrastructure() {
        topology.awaitFreePorts(Duration.ofSeconds(60));
        startTrackers();
        startRendezvous();
        startWorkers();
    }

    private void startTrackers() {
        requireExecutable(paths.trackerBinary(), "the tracker binary");
        for (int i = 0; i < topology.trackers(); i++) {
            int port = topology.trackerPortAt(i);
            started.push(ManagedProcess.start("tracker-" + i, paths.root(),
                    logDir.resolve("tracker-" + i + ".log"),
                    ManagedProcess.env("NODERA_TRACKER_BIND", "127.0.0.1:" + port,
                            "RUST_LOG", "info"),
                    List.of(paths.trackerBinary().toString(), "--bind", "127.0.0.1:" + port)));
        }
    }

    private void startRendezvous() {
        requireExecutable(paths.rendezvousBinary(), "the rendezvous binary");
        for (int i = 0; i < topology.rendezvous(); i++) {
            int port = topology.rendezvousPortAt(i);
            started.push(ManagedProcess.start("rendezvous-" + i, paths.root(),
                    logDir.resolve("rendezvous-" + i + ".log"),
                    ManagedProcess.env("NODERA_RENDEZVOUS_BIND", "127.0.0.1:" + port,
                            "RUST_LOG", "info"),
                    List.of(paths.rendezvousBinary().toString(), "--bind", "127.0.0.1:" + port)));
        }
    }

    /**
     * Start every worker in <b>test mode</b>, each carrying the role it plays in the run.
     *
     * <p>The role is what lets an assertion name its subject: {@code stack.worker(PLAYER_TWO)} is a
     * sentence, and the control port it resolves to is an implementation detail that can be
     * renumbered without rewriting a single scenario.
     */
    private void startWorkers() {
        requireExecutable(paths.workerDist(), "the worker distribution "
                + "(./gradlew :worker:installDist)");
        for (int i = 0; i < topology.workers(); i++) {
            PlayerRole role = topology.workerRole(i);
            int control = topology.workerControlPort(i);
            Path home = paths.runDir().resolve("worker-" + role.cliName() + "-" + i);
            recreate(home);

            Map<String, String> env = ManagedProcess.env(
                    "NODERA_CONTROL_HOST", "127.0.0.1",
                    "NODERA_CONTROL_PORT", String.valueOf(control),
                    "NODERA_P2P_BIND", "127.0.0.1",
                    "NODERA_P2P_ADVERTISE", "127.0.0.1",
                    "NODERA_P2P_PORT", String.valueOf(topology.workerP2pPort(i)),
                    "NODERA_STATE_DIR", home.toString(),
                    "NODERA_IDENTITY_FILE", home.resolve("identity.bin").toString(),
                    "NODERA_WORLDS_FILE", home.resolve("worlds.dat").toString(),
                    "NODERA_WORLD_KEYS_DIR", home.resolve("world-keys").toString(),
                    "NODERA_ARCHIVE_DIR", home.resolve("archive").toString(),
                    "NODERA_TRACKER_ENDPOINTS", String.join(",", topology.trackerEndpoints()),
                    "NODERA_RENDEZVOUS_ENDPOINTS", String.join(",", topology.rendezvousEndpoints()));

            started.push(ManagedProcess.start("worker-" + role.cliName(), paths.root(),
                    logDir.resolve("worker-" + role.cliName() + ".log"), env,
                    List.of(paths.workerDist().toString(),
                            "--test-mode", "--role", role.cliName())));

            ControlClient client = new ControlClient(control);
            workers.add(client);
            workersByRole.putIfAbsent(role, client);
        }
        for (int i = 0; i < workers.size(); i++) {
            workers.get(i).awaitUp(topology.scaled(Duration.ofSeconds(60)));
        }
    }

    // ---------------------------------------------------------------------------------------
    // The game
    // ---------------------------------------------------------------------------------------

    /**
     * Write a client's {@code nodera-client.toml}: which worker it must find, and where discovery
     * lives.
     *
     * <p>{@code required = true} on the companion is deliberate — a client that silently starts
     * without its worker is a client running a different product than the one under test.
     */
    public void writeClientConfig(String gameDirectory, PlayerRole role, String joinPassword) {
        Path configDir = paths.gameDir(gameDirectory).resolve("config");
        try {
            Files.createDirectories(configDir);
            Files.writeString(configDir.resolve("nodera-client.toml"), """
                    [join]
                    \tpassword = "%s"
                    [companion]
                    \tcontrolEndpoint = "127.0.0.1:%d"
                    \trequired = true
                    [tracker]
                    \tendpoints = %s
                    [rendezvous]
                    \tendpoints = %s
                    """.formatted(joinPassword == null ? "" : joinPassword,
                    worker(role).port(),
                    tomlArray(topology.trackerEndpoints()),
                    tomlArray(topology.rendezvousEndpoints())));
        } catch (IOException e) {
            throw new HarnessException("cannot write the client config for " + gameDirectory, e);
        }
    }

    /**
     * Stage the dedicated server's run directory: EULA, ports, RCON, and the host-side config.
     *
     * <p>The world is deleted first. A dedicated-server scenario that inherited the previous run's
     * world would inherit its certified genesis and its archive, and "the world came back" would be
     * proved by the world never having left.
     */
    public void stageDedicatedServer(Map<String, String> hostConfigOverrides) {
        Path run = paths.gameDir("run");
        deleteRecursively(run.resolve("world"));
        try {
            Files.createDirectories(run);
            Files.writeString(run.resolve("eula.txt"), "eula=true\n");
            Files.writeString(run.resolve("server.properties"), """
                    server-port=%d
                    online-mode=false
                    level-name=world
                    enable-rcon=true
                    rcon.port=%d
                    rcon.password=%s
                    broadcast-rcon-to-ops=false
                    """.formatted(topology.gamePort(), topology.rconPort(), topology.rconPassword()));

            Path serverConfig = run.resolve("world/serverconfig");
            Files.createDirectories(serverConfig);
            StringBuilder toml = new StringBuilder("""
                    [host]
                    \tgamePort = %d
                    \tonlineAuth = false
                    \tsharePassword = "%s"
                    [archive]
                    \tstreamIntervalTicks = %s
                    [entity]
                    \tlaneAutoActivate = true
                    \tmobCaptureDimensions = ["minecraft:overworld"]
                    """.formatted(topology.gamePort(),
                    hostConfigOverrides.getOrDefault("sharePassword", ""),
                    hostConfigOverrides.getOrDefault("streamIntervalTicks", "2400")));
            toml.append("[tracker]\n\tendpoints = ")
                    .append(tomlArray(topology.trackerEndpoints())).append('\n');
            toml.append("[rendezvous]\n\tendpoints = ")
                    .append(tomlArray(topology.rendezvousEndpoints())).append('\n');
            Files.writeString(serverConfig.resolve("nodera-server.toml"), toml.toString());
        } catch (IOException e) {
            throw new HarnessException("cannot stage the dedicated server run directory", e);
        }
    }

    /** Start {@code :neoforge-mod:runServer} and return its process. */
    public ManagedProcess startDedicatedServer(String logName) {
        dedicatedServer = startGradle("server", ":neoforge-mod:runServer", logName);
        return dedicatedServer;
    }

    /** The dedicated server, if this run started one. */
    public ManagedProcess dedicatedServer() {
        if (dedicatedServer == null) {
            throw new HarnessException("this run has no dedicated server");
        }
        return dedicatedServer;
    }

    /**
     * Start a NeoForge dev client through Gradle.
     *
     * @param task    {@code runClientHost}, {@code runClientJoin}, {@code runClientJoinTwo}, …
     * @param logName the file under {@link #logDir()} to capture into.
     */
    public ManagedProcess startClient(String task, String logName) {
        return startGradle("client-" + task, ":neoforge-mod:" + task, logName);
    }

    private ManagedProcess startGradle(String name, String task, String logName) {
        ManagedProcess process = ManagedProcess.start(name, paths.root(), logDir.resolve(logName),
                ManagedProcess.env("JAVA_TOOL_OPTIONS", ""),
                List.of(paths.root().resolve("gradlew").toString(), task, "--console=plain"));
        started.push(process);
        return process;
    }

    // ---------------------------------------------------------------------------------------
    // Artefacts + teardown
    // ---------------------------------------------------------------------------------------

    /** Snapshot every worker's state document into the results directory. */
    public void collectWorkerState() {
        for (int i = 0; i < workers.size(); i++) {
            PlayerRole role = topology.workerRole(i);
            workers.get(i).ask("NODERA-STATE 2").ifPresent(state -> write(
                    resultsDir.resolve("state-" + role.cliName() + ".json"), state));
        }
    }

    /**
     * Copy every artefact of the run into the results directory: service logs, each game
     * directory's {@code latest.log}, and any in-game self-test report.
     */
    public void collectArtefacts() {
        collectWorkerState();
        for (String gameDir : List.of("run", "run-host", "run-join", "run-join2", "run-join3")) {
            Path latest = paths.gameLog(gameDir);
            if (Files.isRegularFile(latest)) {
                copy(latest, resultsDir.resolve("client-" + gameDir + "-latest.log"));
            }
        }
    }

    /** Stop everything this stack started, in reverse order. */
    @Override
    public void close() {
        if (!keepRunning) {
            while (!started.isEmpty()) {
                started.pop().stop(Duration.ofSeconds(20));
            }
        }
        try {
            if (lock != null) {
                lock.release();
            }
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (IOException alreadyGone) {
            // The lock file is advisory; a failure to release it on a dying JVM releases it anyway.
        }
    }

    // ---------------------------------------------------------------------------------------

    private void requireExecutable(Path path, String what) {
        if (!Files.isExecutable(path)) {
            throw new HarnessException(what + " is missing at " + path
                    + " — run the build first (drop --no-build)");
        }
    }

    private static String tomlArray(List<String> values) {
        return values.stream().map(value -> '"' + value + '"')
                .reduce((a, b) -> a + ", " + b).map(joined -> "[" + joined + "]").orElse("[]");
    }

    private static void recreate(Path directory) {
        deleteRecursively(directory);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new HarnessException("cannot create " + directory, e);
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

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new HarnessException("cannot write " + file, e);
        }
    }

    private static void copy(Path from, Path to) {
        try {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new HarnessException("cannot copy " + from + " to " + to, e);
        }
    }
}
