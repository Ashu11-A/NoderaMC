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
        String bind = Topology.serviceBindAddress();
        for (int i = 0; i < topology.trackers(); i++) {
            int port = topology.trackerPortAt(i);
            started.push(ManagedProcess.start("tracker-" + i, paths.root(),
                    logDir.resolve("tracker-" + i + ".log"),
                    // BIND_ADDR, not BIND. The service's config contract is "every key has an exact
                    // environment twin, and nothing else": an unrecognised NODERA_TRACKER_* variable
                    // is a hard startup refusal, by design, so a typo cannot silently run a service
                    // on defaults. That is the right behaviour and it caught this — but the harness
                    // then launched a tracker that exited immediately, and every live scenario ran
                    // on a mesh with NO tracker and NO rendezvous. The symptom in the logs was never
                    // "the service is down"; it was "world 'world' is on NO tracker — 0 of 1
                    // answered", "0 seeder(s), 0 routable", and a soak that observed nothing.
                    ManagedProcess.env("NODERA_TRACKER_BIND_ADDR", bind + ":" + port,
                            "RUST_LOG", "info"),
                    List.of(paths.trackerBinary().toString(), "--bind", bind + ":" + port)));
            awaitListening("tracker-" + i, port, Duration.ofSeconds(20));
        }
    }

    /**
     * Wait until a service this stack just launched is actually answering, and fail loudly if it is
     * not.
     *
     * <p>{@link #startInfrastructure}'s own javadoc has always said it waits "until every one of
     * them answers", and for the workers that was true — {@code ControlClient} probes each one. For
     * the trackers and the rendezvous it was not: they were started and never checked. So a service
     * that exited during startup left a stack that looked launched, and every scenario ran against a
     * network with no discovery plane at all.
     *
     * <p>That was not hypothetical. The harness passed {@code NODERA_TRACKER_BIND} and
     * {@code NODERA_RENDEZVOUS_BIND}; both services take {@code *_BIND_ADDR} and refuse to start on
     * an unrecognised {@code NODERA_<SERVICE>_*} variable — deliberately, so a typo cannot silently
     * run a service on defaults. Both binaries therefore printed one line and exited, and every live
     * run since reported symptoms one layer up: "world 'world' is on NO tracker — 0 of 1 answered",
     * "0 seeder(s), 0 routable", and mesh soaks that observed nothing. The service's own refusal was
     * correct and the harness threw it away.
     *
     * <p>The failure message carries the process's log tail, because "tracker-0 never answered" on
     * its own sends the reader to the wrong layer — which is exactly what happened here.
     *
     * <p>The deadline is a parameter because the processes differ by two orders of magnitude. Twenty
     * seconds is right for a Rust binary that binds a socket and prints a line; it is wrong for a
     * Minecraft server behind a Gradle build, which compiles, resolves NeoForge, generates a world
     * and only then listens.
     */
    private void awaitListening(String name, int port, Duration base) {
        Duration timeout = topology.scaled(base);
        // The address everybody ELSE was told to use, not loopback. On a loopback run the two are
        // the same. On a LAN run they are not, and a service bound to 0.0.0.0 answers on loopback
        // whether or not the address in every peer's tracker list is right — so probing loopback
        // there would confirm a stack that no other machine can reach.
        String host = probeHost();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            requireStillStarting(name, "port " + port);
            try (java.net.Socket probe = new java.net.Socket()) {
                probe.connect(new java.net.InetSocketAddress(host, port), 500);
                return;
            } catch (IOException notYet) {
                Topology.sleep(Duration.ofMillis(250));
            }
        }
        throw new HarnessException(name + " never accepted a connection on " + host + ":" + port
                + " within " + timeout.toSeconds() + "s" + logTail(name));
    }

    /**
     * Fail now if the process this stack is waiting on has already exited.
     *
     * <p>Half of these processes are Gradle wrappers, and a failed Gradle build exits at once with a
     * non-zero status. Burning a ten-minute readiness budget on a build that stopped compiling in
     * the first thirty seconds produces the worst possible message — a timeout — for the most
     * mechanical possible cause, so the exit STATUS is reported as soon as it exists.
     */
    private void requireStillStarting(String name, String what) {
        ManagedProcess process = processNamed(name);
        if (process == null || process.isAlive()) {
            return;
        }
        int exit = process.exitValueOrUnknown();
        throw new HarnessException(name + (exit == 0
                ? " exited during startup (" + what + ")"
                : " exited " + exit + " during startup (" + what
                        + ") — the launcher failed rather than the process it starts")
                + logTail(name));
    }

    /**
     * The address to verify a service on.
     *
     * <p>{@code 0.0.0.0} is a bind address and never a destination, so an advertise address set to
     * it (or left at loopback) falls back to loopback rather than dialling a wildcard.
     */
    private static String probeHost() {
        String advertise = Topology.serviceAdvertiseAddress();
        return advertise.isBlank() || "0.0.0.0".equals(advertise) ? "127.0.0.1" : advertise;
    }

    private ManagedProcess processNamed(String name) {
        for (ManagedProcess process : started) {
            if (process.name().equals(name)) {
                return process;
            }
        }
        return null;
    }

    /** The last few lines of a managed process's log, for a failure message that names the cause. */
    private String logTail(String name) {
        ManagedProcess process = processNamed(name);
        // The process's OWN log file, not a file named after it: a client is called
        // `client-runClientHost` and writes `client-host.log`, so deriving the path from the name
        // would have sent every client failure to a file that does not exist.
        Path log = process == null ? logDir.resolve(name + ".log") : process.logFile();
        if (!Files.isReadable(log)) {
            return " — no log at " + log;
        }
        try {
            List<String> lines = Files.readAllLines(log);
            List<String> tail = lines.subList(Math.max(0, lines.size() - 8), lines.size());
            return " — " + log + " ends:\n    " + String.join("\n    ", tail);
        } catch (IOException unreadable) {
            return " — " + log + " could not be read: " + unreadable;
        }
    }

    private void startRendezvous() {
        requireExecutable(paths.rendezvousBinary(), "the rendezvous binary");
        String bind = Topology.serviceBindAddress();
        for (int i = 0; i < topology.rendezvous(); i++) {
            int port = topology.rendezvousPortAt(i);
            started.push(ManagedProcess.start("rendezvous-" + i, paths.root(),
                    logDir.resolve("rendezvous-" + i + ".log"),
                    // BIND_ADDR, not BIND — see the tracker's note above; the same refusal applied.
                    ManagedProcess.env("NODERA_RENDEZVOUS_BIND_ADDR", bind + ":" + port,
                            "RUST_LOG", "info"),
                    List.of(paths.rendezvousBinary().toString(), "--bind", bind + ":" + port)));
            awaitListening("rendezvous-" + i, port, Duration.ofSeconds(20));
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

            // The control socket stays on loopback whatever the P2P plane does. It is an
            // administrative channel with no authentication of its own — every verb that reaches it
            // is trusted — so "the peers must be reachable from the LAN" must never widen it. The
            // phone's own worker is reached over adb for exactly the same reason.
            Map<String, String> env = ManagedProcess.env(
                    "NODERA_CONTROL_HOST", "127.0.0.1",
                    "NODERA_CONTROL_PORT", String.valueOf(control),
                    "NODERA_P2P_BIND", Topology.p2pBindAddress(),
                    "NODERA_P2P_ADVERTISE", Topology.p2pAdvertiseAddress(),
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
        // Both halves of "stage this client", in one call. They used to be two, with the second one
        // in a scenario support class — so every scenario that called this method DIRECTLY (the
        // password and re-key lanes do) staged a game directory with no options.txt and, on a fresh
        // checkout, parked its client on the accessibility onboarding screen forever.
        stageClientOptions(gameDirectory);
        Path configDir = paths.gameDir(gameDirectory).resolve("config");
        try {
            Files.createDirectories(configDir);
            // [p2p] advertiseHost matters as much as the endpoints above. Without it the mod's own
            // peer resolves "auto" by enumerating this machine's interfaces — and on a developer
            // box carrying a container bridge and a VPN it can pick either, while every worker this
            // harness starts is pinned to one address. A run whose nodes disagree about where they
            // are reachable produces peers that are seated and then never reached, which reads as a
            // product defect and is not one.
            Files.writeString(configDir.resolve("nodera-client.toml"), """
                    [join]
                    \tpassword = "%s"
                    [companion]
                    \tcontrolEndpoint = "127.0.0.1:%d"
                    \trequired = true
                    [p2p]
                    \tadvertiseHost = "%s"
                    [tracker]
                    \tendpoints = %s
                    [rendezvous]
                    \tendpoints = %s
                    """.formatted(joinPassword == null ? "" : joinPassword,
                    worker(role).port(),
                    Topology.p2pAdvertiseAddress(),
                    tomlArray(topology.trackerEndpoints()),
                    tomlArray(topology.rendezvousEndpoints())));
        } catch (IOException e) {
            throw new HarnessException("cannot write the client config for " + gameDirectory, e);
        }
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
    public void stageClientOptions(String gameDirectory) {
        Path directory = paths.gameDir(gameDirectory);
        try {
            Files.createDirectories(directory);
            Path options = directory.resolve("options.txt");
            if (Files.isRegularFile(options)) {
                return;
            }
            // onboardAccessibility  — the blocker above; must be false before the first frame.
            // skipMultiplayerWarning — the third-party-server notice sits in front of the
            //                          multiplayer screen for any scenario that drives the GUI.
            // pauseOnLostFocus      — under Xvfb there is no window manager, so a client may never
            //                          be "active"; a paused singleplayer client stops ticking and
            //                          would never share its world.
            Files.writeString(options, """
                    onboardAccessibility:false
                    skipMultiplayerWarning:true
                    pauseOnLostFocus:false
                    """);
        } catch (IOException e) {
            throw new HarnessException("cannot stage options.txt for " + gameDirectory, e);
        }
    }

    /**
     * Set a key in a world's {@code nodera-server.toml}.
     *
     * <p>The counterpart of {@link #stageDedicatedServer}, which writes a FRESH file for the
     * dedicated server's world. Every host-client scenario instead tunes the STAGED world's own
     * copy, which already exists and must keep everything else in it — so the two are different
     * operations and both belong here.
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
    public static void setHostConfig(Path config, String section, String key, String value) {
        List<String> lines = new ArrayList<>(readLines(config));
        java.util.regex.Pattern existing =
                java.util.regex.Pattern.compile("^([ \\t]*)" + java.util.regex.Pattern.quote(key) + " = ");
        for (int i = 0; i < lines.size(); i++) {
            java.util.regex.Matcher matcher = existing.matcher(lines.get(i));
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

    private static List<String> readLines(Path file) {
        if (!Files.isReadable(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file);
        } catch (IOException unreadable) {
            return List.of();
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
                    // Zero, matching the product default. The harness used to write 2400 here and
                    // that number silently won every live run: a config FILE beats a code default,
                    // so every scenario measured the periodic whole-save repack whatever the
                    // product had decided. On a 76 MB save that is a 40-second pack inside the
                    // server every two minutes, which starved a joining player's handshake and
                    // read as "player B never joined". A scenario that wants the old cadence still
                    // asks for it by name (RekeyScenario does).
                    // Match the product default rather than asserting one. The harness used to
                    // write 2400 here and that number silently won every live run, because a config
                    // FILE beats a code default; then it wrote 0 and hid the rollback hole that
                    // turning the repack off had opened. Whatever the product decides is what the
                    // suites should measure.
                    hostConfigOverrides.getOrDefault("streamIntervalTicks", "12000")));
            toml.append("[tracker]\n\tendpoints = ")
                    .append(tomlArray(topology.trackerEndpoints())).append('\n');
            toml.append("[rendezvous]\n\tendpoints = ")
                    .append(tomlArray(topology.rendezvousEndpoints())).append('\n');
            Files.writeString(serverConfig.resolve("nodera-server.toml"), toml.toString());
        } catch (IOException e) {
            throw new HarnessException("cannot stage the dedicated server run directory", e);
        }
    }

    /**
     * Start {@code :neoforge-mod:runServer} and return its process, once it is answering.
     *
     * <p>Answering, not started. This method used to hand back a Gradle wrapper and let the calling
     * scenario decide what "ready" meant by watching a log line — which is the same shape of defect
     * the trackers and the rendezvous had, one process type over: a build that failed, or a server
     * that bound its port and never took a command, left a stack that looked launched and every
     * symptom appeared in whichever stage happened to assert first.
     *
     * <p>Two probes, because they answer different questions. The game port says the server socket
     * exists; an RCON round trip says the SERVER THREAD is running, and a Minecraft server that
     * binds but does not answer RCON is not ready — every scenario that drives one drives it through
     * exactly that channel.
     */
    public ManagedProcess startDedicatedServer(String logName) {
        dedicatedServer = startGradle("server", ":neoforge-mod:runServer", logName);
        awaitListening("server", topology.gamePort(), SERVER_BOOT);
        try {
            rcon().awaitUp(SERVER_RCON);
        } catch (HarnessException notAnswering) {
            throw new HarnessException(notAnswering.getMessage()
                    + " — the game port was open, so the server bound and then did not answer"
                    + logTail("server"), notAnswering);
        }
        return dedicatedServer;
    }

    /**
     * How long a dedicated server gets to compile, resolve NeoForge, generate a world and listen.
     *
     * <p>The scenarios' own "sharing world" waits are 420 s, and this has to be the outer bound of
     * the same sequence rather than a second, tighter opinion about it.
     */
    private static final Duration SERVER_BOOT = Duration.ofSeconds(600);

    /** How long the server thread gets to answer RCON once its game port is open. */
    private static final Duration SERVER_RCON = Duration.ofSeconds(180);

    /** How long a dev client gets to build and fork its game JVM. */
    private static final Duration CLIENT_BOOT = Duration.ofSeconds(600);

    /** The dedicated server, if this run started one. */
    public ManagedProcess dedicatedServer() {
        if (dedicatedServer == null) {
            throw new HarnessException("this run has no dedicated server");
        }
        return dedicatedServer;
    }

    /**
     * Start a NeoForge dev client through Gradle, and return once its game JVM exists.
     *
     * <p>A client has no listening port, so it cannot be proved the way a service is. What proves it
     * is the thing a client uniquely has: ModDevGradle passes each run's program arguments in an
     * {@code @argfile}, so the forked game JVM carries {@code <run>RunProgramArgs} on its command
     * line. A JVM carrying this run's token exists only after Gradle finished configuring,
     * compiling and forking — which is precisely the interval in which a client failure used to be
     * invisible. Before this, a Gradle build that failed to compile handed the scenario a dead
     * wrapper, and the first log wait after it burned its entire timeout and then reported that the
     * game had never said the thing it was asked for.
     *
     * <p>The wrapper's log is the fallback and the failure message. On a machine with no readable
     * {@code /proc} the token scan cannot see anything, so a Minecraft lifecycle marker in the
     * wrapper's own output stands in — and either way the failure quotes the log tail, because "the
     * client never started" without the build output sends the reader to the wrong layer.
     *
     * @param task    {@code runClientHost}, {@code runClientJoin}, {@code runClientJoinTwo}, …
     * @param logName the file under {@link #logDir()} to capture into.
     */
    public ManagedProcess startClient(String task, String logName) {
        String name = "client-" + task;
        ManagedProcess process = startGradle(name, ":neoforge-mod:" + task, logName);
        awaitGameJvm(name, runToken(task), logDir.resolve(logName), CLIENT_BOOT);
        return process;
    }

    /**
     * The {@code <run>RunProgramArgs} token of a ModDevGradle run task.
     *
     * <p>{@code runClientJoinTwo} → {@code clientJoinTwoRunProgramArgs}. Derived rather than passed
     * in: a scenario that had to name the token beside the task could name the wrong one, and the
     * consequence of the wrong token is a kill that reaches the dedicated server.
     */
    public static String runToken(String task) {
        String run = task.startsWith("run") ? task.substring(3) : task;
        if (run.isEmpty()) {
            throw new HarnessException("'" + task + "' is not a ModDevGradle run task");
        }
        return Character.toLowerCase(run.charAt(0)) + run.substring(1) + "RunProgramArgs";
    }

    /** Evidence in a wrapper's own output that the game got as far as starting. */
    private static final List<String> GAME_STARTED = List.of(
            "ModLauncher running", "Setting user:", "LWJGL", "Loading Minecraft");

    private void awaitGameJvm(String name, String token, Path log, Duration base) {
        Duration timeout = topology.scaled(base);
        long deadline = System.nanoTime() + timeout.toNanos();
        LogWatcher wrapper = watch(log);
        while (System.nanoTime() < deadline) {
            requireStillStarting(name, "run token " + token);
            if (ManagedProcess.tokenAlive(token)) {
                return;
            }
            if (GAME_STARTED.stream().anyMatch(wrapper::contains)) {
                return;
            }
            Topology.sleep(Duration.ofSeconds(2));
        }
        throw new HarnessException(name + " never forked a game JVM carrying '" + token
                + "' within " + timeout.toSeconds() + "s" + logTail(name));
    }

    /**
     * Start the desktop companion app <b>attached</b> to a worker this stack already launched.
     *
     * <p>Attach mode is the distinction that matters: the app normally supervises a worker of its
     * own, and an app that spawned a second worker on a port this stack is already using would
     * either fail to bind or — worse — quietly hand the scenario a peer nobody is asserting about.
     * {@code NODERA_APP_ATTACH} makes it adopt the running one instead, exactly as
     * {@code scripts/dev.sh}'s interactive stack does, and {@code NODERA_APP_MULTI} lifts the
     * single-instance guard so a second launcher can sit next to the first.
     *
     * <p>A missing binary is reported as an absent process rather than a failure. Every scenario
     * that does not ask for a launcher must keep running on a machine that has never built one, and
     * the scenarios that do ask can say so themselves.
     *
     * @param workerIndex the worker to attach to; its control port is what the app is pointed at.
     * @return the launcher process, or empty when the app has not been built.
     */
    public java.util.Optional<ManagedProcess> startCompanionApp(int workerIndex) {
        if (!Files.isExecutable(paths.companionApp())) {
            return java.util.Optional.empty();
        }
        PlayerRole role = topology.workerRole(workerIndex);
        ManagedProcess process = ManagedProcess.start("app-" + role.cliName(), paths.root(),
                logDir.resolve("app-" + role.cliName() + ".log"),
                ManagedProcess.env(
                        "NODERA_APP_ATTACH", "1",
                        "NODERA_APP_MULTI", "1",
                        "NODERA_APP_TITLE", "Nodera — " + role.cliName(),
                        "NODERA_CONTROL_PORT",
                        String.valueOf(topology.workerControlPort(workerIndex)),
                        "NODERA_WORKER_LOG",
                        logDir.resolve("worker-" + role.cliName() + ".log").toString()),
                List.of(paths.companionApp().toString()));
        started.push(process);
        // A launcher that exits during startup is the same defect as a service that does, and the
        // same rule applies: this stack does not hand back a process it has not seen survive. A
        // Tauri window has no port and no readiness line, so what is provable is that it is still
        // there a moment later — which is exactly what a missing WebKit runtime or a denied display
        // fails. The settle is short because the alternative is not "wait longer", it is "start the
        // scenario against a launcher that has already gone".
        Topology.sleep(Duration.ofSeconds(3));
        requireStillStarting("app-" + role.cliName(), "the companion launcher");
        return java.util.Optional.of(process);
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
