package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.ControlClient;
import dev.nodera.testkit.harness.HarnessException;
import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.harness.ManagedProcess;
import dev.nodera.testkit.harness.TestPaths;
import dev.nodera.testkit.harness.Topology;
import dev.nodera.testkit.suite.ScenarioContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Paper/Folia half of the live harness: a staged Bukkit server carrying {@code nodera-endpoint}.
 *
 * <p>This is the Java form of {@code scripts/lib/e2e-server.sh}. The launcher proper — the lock, the
 * port plan, the discovery services, the workers, the artefact collection — belongs to
 * {@link LiveStack}; this class adds exactly what the {@code server} category needs on top:
 *
 * <ul>
 *   <li>a Paper or Folia server, staged clean and started with {@code nodera-endpoint};</li>
 *   <li>the endpoint's own control port (the ControlProtocol v2 verbs);</li>
 *   <li>the skip contract.</li>
 * </ul>
 *
 * <h2>The skip contract (docs/server/TESTING.md §1.7)</h2>
 *
 * <p>Neither {@code java/paper-plugin}'s jar (docs/server/LIMITATIONS.md L-61) nor a pinned
 * Paper/Folia jar (L-66) is guaranteed on a fresh checkout. A suite that asserted anyway would burn
 * its whole timeout and report "never joined" about a server that was never built — the exact
 * failure shape L-45 cost the project thirty minutes a job to diagnose. So {@link #preflight}
 * skips with a stated reason and a nightly run reads as "not built yet", never as "broken".
 *
 * <h2>Platform jars are resolved, never downloaded by a suite</h2>
 *
 * <p>{@code NODERA_PAPER_JAR} / {@code NODERA_FOLIA_JAR}, else {@code run/servers/{paper,folia}.jar}.
 * CI downloads them once in a workflow step and caches them; a suite that downloaded its own would
 * make a local run and a CI run different code paths.
 *
 * <h2>Ports, on top of the topology's plan</h2>
 *
 * <pre>
 *   25599   the Bukkit server's game port (shared with the NeoForge dedicated-server scenarios;
 *           they never run at the same time — the live-suite lock sees to that)
 *   25575   RCON
 *   25613   the endpoint's control port  (worker control base + the worker count)
 *   25623   the endpoint's p2p port      (worker p2p base     + the worker count)
 * </pre>
 *
 * <p>The endpoint's ports are slotted straight after the workers so raising the spare-peer count
 * never walks onto them.
 *
 * <p>Thread-context: one instance per scenario, used from the scenario's thread. {@link #close}
 * stops everything this class started, in reverse order.
 */
public final class ServerEndpointSupport implements AutoCloseable {

    /** The Minecraft version the mod pins (docs/README.md §4.6). */
    public static final String MINECRAFT_VERSION = "1.21.1";

    /**
     * The protocol number of {@link #MINECRAFT_VERSION}.
     *
     * <p>The mixed-client scenarios are only meaningful when the Paper/Folia build matches it: a
     * 1.21.1 client cannot join a server of any other version (L-66).
     */
    public static final int MINECRAFT_PROTOCOL = 767;

    /**
     * Folia sizes its tick pool from the core count and CI runners are small; two threads is enough
     * to make two regions genuinely parallel, which is the only thing the Folia scenario needs.
     */
    public static final int FOLIA_THREADS = 2;

    /**
     * ALIGN-1 needs grid-exponent &gt;= 3. 4 is Folia's own default and what the plugin's preflight
     * expects; a scenario that changed it would be testing a configuration no operator runs.
     */
    public static final int FOLIA_GRID_EXPONENT = 4;

    private static final String SERVER_XMS = "1G";
    private static final String SERVER_XMX = "2G";

    /**
     * The single most important assertion on Folia. An uncaught exception on a tick thread HALTS THE
     * SCHEDULER AND STOPS THE SERVER (docs/minecraft/folia/06-schedulers.md), so a thread-context
     * violation is both the likeliest failure mode and the loudest. It is a grep, not a judgement
     * call, and that is the point.
     */
    private static final Pattern THREAD_CONTEXT_VIOLATION = Pattern.compile(
            "May not access .* off-thread|ensureTickThread|Cannot recursively operate in the "
                    + "regioniser|Thread failed main thread check");

    private final ScenarioContext context;
    private final LiveStack stack;
    private final TestPaths paths;
    private final Topology topology;

    private final Path serverStage;
    private final Path serverJars;
    private final Path pluginJar;
    private final int endpointControl;
    private final int endpointP2p;

    private final List<ManagedProcess> started = new ArrayList<>();
    private final List<String> corpusPresent = new ArrayList<>();
    private final List<String> corpusMissing = new ArrayList<>();

    private String platform = "paper";
    private Path serverJar;
    private ManagedProcess serverProcess;
    private ServerSparkSupport spark;
    private String sharePassword = "";
    private String streamIntervalTicks = "2400";

    public ServerEndpointSupport(ScenarioContext context) {
        this.context = context;
        this.stack = context.stack();
        this.paths = stack.paths();
        this.topology = stack.topology();
        this.serverStage = paths.runDir().resolve("bukkit");
        this.serverJars = paths.runDir().resolve("servers");
        this.pluginJar = paths.paperPluginJar();
        this.endpointControl = topology.workerControlPort(topology.workers());
        this.endpointP2p = topology.workerP2pPort(topology.workers());
    }

    /** Install the profiling overlay; {@code null} leaves every staged file byte-identical. */
    public ServerEndpointSupport withSpark(ServerSparkSupport profiler) {
        this.spark = profiler;
        return this;
    }

    /** The world password the staged endpoint config carries. */
    public ServerEndpointSupport withSharePassword(String password) {
        this.sharePassword = password == null ? "" : password;
        return this;
    }

    /** The archive streaming cadence the staged endpoint config carries, in ticks. */
    public ServerEndpointSupport withStreamIntervalTicks(int ticks) {
        this.streamIntervalTicks = String.valueOf(ticks);
        return this;
    }

    /** Where the staged server lives — the clean slate every endpoint scenario starts from. */
    public Path serverStage() {
        return serverStage;
    }

    /** The endpoint's own control port. */
    public int endpointControlPort() {
        return endpointControl;
    }

    /** The endpoint's own p2p port. */
    public int endpointP2pPort() {
        return endpointP2p;
    }

    /** The platform jar this run resolved ({@code run/servers/paper.jar}, or the override). */
    public Path serverJar() {
        return serverJar;
    }

    /** The server process this class last started. */
    public ManagedProcess serverProcess() {
        if (serverProcess == null) {
            throw new HarnessException("no Bukkit server has been started yet");
        }
        return serverProcess;
    }

    /** Corpus jars found and staged, by file name. */
    public List<String> corpusPresent() {
        return List.copyOf(corpusPresent);
    }

    /** Corpus jars named but absent, by file name. */
    public List<String> corpusMissing() {
        return List.copyOf(corpusMissing);
    }

    // ---------------------------------------------------------------------------------------
    // The skip contract
    // ---------------------------------------------------------------------------------------

    /**
     * The one place a scenario decides whether there is anything to test.
     *
     * <p>Call it before staging anything: skipping is free, and building a whole server to then skip
     * is not.
     *
     * @param wanted {@code paper} or {@code folia}.
     */
    public void preflight(String wanted) {
        if (!softPreflight(wanted)) {
            context.skip(skipReason(wanted));
        }
        context.note("platform: " + platform + " (" + serverJar + ") · plugin: " + pluginJar);
    }

    /**
     * The same check, as a QUESTION.
     *
     * <p>{@link #preflight} ends the scenario, which is right for one whose every stage needs the
     * platform. It is wrong for a scenario that has already asserted something real and is now
     * offering an extra platform stage on top: skipping there would throw away a passed stage and
     * report the whole run as skipped. This returns {@code false} instead, sets the same platform
     * and jar, and leaves the caller to say what it is not asserting.
     *
     * @return {@code true} when this machine can host the platform.
     */
    public boolean softPreflight(String wanted) {
        platform = wanted;
        serverJar = resolveServerJar(wanted);
        return Files.isRegularFile(pluginJar) && Files.isRegularFile(serverJar);
    }

    /** Why {@link #softPreflight} said no, in the shell suites' own words. */
    public String skipReason(String wanted) {
        if (!Files.isRegularFile(pluginJar)) {
            return "no nodera-paper jar at " + pluginJar + " (server task 1 — L-61).";
        }
        Path jar = resolveServerJar(wanted);
        return "no " + wanted + " jar at " + jar + " (L-66). Stage one at $NODERA_"
                + wanted.toUpperCase(Locale.ROOT) + "_JAR or " + serverJars + "/" + wanted + ".jar.";
    }

    private Path resolveServerJar(String wanted) {
        String override = System.getenv("NODERA_" + wanted.toUpperCase(Locale.ROOT) + "_JAR");
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }
        return switch (wanted) {
            case "paper", "folia" -> serverJars.resolve(wanted + ".jar");
            default -> throw new HarnessException("unknown platform '" + wanted + "' (paper|folia)");
        };
    }

    // ---------------------------------------------------------------------------------------
    // Staging + launch
    // ---------------------------------------------------------------------------------------

    /** Stage a clean-slate server at the platform's default grid exponent. */
    public void stageBukkitServer(String worldId) {
        stageBukkitServer(worldId, FOLIA_GRID_EXPONENT);
    }

    /**
     * A clean-slate Paper/Folia server pointed at this run's services, with {@code nodera-endpoint}
     * installed and configured.
     *
     * <p>Clean slate by design: every endpoint scenario asserts something about a world's first
     * certified state, and a leftover world folder from a previous run would make "the first action
     * ever on a fresh store" a lie.
     *
     * @param worldId      the world the endpoint claims custody of; the staged config is
     *                     {@code listed: true} + {@code custody: FULL}, so it must name one —
     *                     advertising full custody of a world nobody can name is an announce no
     *                     tracker can use, and the plugin refuses it.
     * @param gridExponent {@code threaded-regions.grid-exponent}; the Folia scenario lowers it to
     *                     the value at which a Nodera region straddles two Folia sections.
     */
    public void stageBukkitServer(String worldId, int gridExponent) {
        deleteRecursively(serverStage);
        try {
            Files.createDirectories(serverStage.resolve("plugins/NoderaEndpoint"));
            Files.createDirectories(serverStage.resolve("config"));

            Files.writeString(serverStage.resolve("eula.txt"), "eula=true\n");
            Files.writeString(serverStage.resolve("server.properties"), """
                    server-port=%d
                    online-mode=false
                    level-name=world
                    level-type=minecraft:flat
                    allow-nether=false
                    spawn-protection=0
                    enable-rcon=true
                    rcon.port=%d
                    rcon.password=%s
                    broadcast-rcon-to-ops=false
                    view-distance=10
                    simulation-distance=10
                    max-players=20
                    """.formatted(topology.gamePort(), topology.rconPort(), topology.rconPassword()));

            // Folia consumes threaded-regions at STARTUP, so the file has to exist before the first
            // boot — writing it afterwards changes nothing until a restart, and a scenario that did
            // so would silently test the default it thought it overrode.
            Files.writeString(serverStage.resolve("config/paper-global.yml"), """
                    _version: 29
                    threaded-regions:
                      threads: %d
                      grid-exponent: %d
                      scheduler: EDF
                    misc:
                      max-joins-per-tick: 10
                    %s""".formatted(FOLIA_THREADS, gridExponent,
                    spark == null ? "" : spark.paperGlobalBlock()));

            // Paper and Folia bundle spark; this only writes its config, and only when profiling is
            // on. It has to come after the delete above or it is removed with the stage.
            if (spark != null) {
                spark.stagePlugin(platform, serverStage);
            }

            Files.copy(pluginJar, serverStage.resolve("plugins/nodera-paper.jar"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(serverStage.resolve("plugins/NoderaEndpoint/nodera-endpoint.yml"), """
                    peer:
                      mode: external
                      control-port: %d
                      identity-file: identity.bin
                      p2p:
                        bind: 127.0.0.1
                        port: %d
                        advertise: 127.0.0.1
                    world:
                      id: "%s"
                      custody: FULL
                      password: "%s"
                      listed: true
                    discovery:
                      trackers: %s
                      rendezvous: %s
                    lane:
                      entity-capture: true
                      mob-capture-dimensions: ["minecraft:overworld"]
                      archive-stream-interval-ticks: %s
                    mods:
                      required: []
                      allowed: []
                    """.formatted(endpointControl, endpointP2p, worldId == null ? "" : worldId,
                    sharePassword, yamlArray(topology.trackerEndpoints()),
                    yamlArray(topology.rendezvousEndpoints()), streamIntervalTicks));
        } catch (IOException e) {
            throw new HarnessException("cannot stage the Bukkit server at " + serverStage, e);
        }
    }

    /**
     * Drop an extra plugin jar into the staged server (the compatibility corpus).
     *
     * <p>Missing jars are NAMED, never silently skipped: a compatibility scenario that quietly tests
     * four plugins instead of seven is worse than one that says which three were absent.
     */
    public void installCorpusPlugin(Path jar) {
        if (Files.isRegularFile(jar)) {
            try {
                Files.copy(jar, serverStage.resolve("plugins").resolve(jar.getFileName().toString()),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new HarnessException("cannot stage the corpus plugin " + jar, e);
            }
            corpusPresent.add(jar.getFileName().toString());
        } else {
            corpusMissing.add(jar.getFileName().toString());
        }
    }

    /**
     * Start the staged server.
     *
     * @param logName the file under the run's log directory to capture into.
     */
    public ManagedProcess startBukkitServer(String logName) {
        List<String> command = new ArrayList<>(List.of("java",
                "-Xms" + SERVER_XMS, "-Xmx" + SERVER_XMX));
        if (spark != null) {
            command.addAll(spark.bukkitJvmArguments(platform));
        }
        command.add("-Dcom.mojang.eula.agree=true");
        command.addAll(List.of("-jar", serverJar.toString(), "--nogui"));

        // HARNESS-GAP: LiveStack starts trackers, rendezvous, workers, the NeoForge dedicated
        // server and NeoForge clients, but has no hook for a foreign server JVM, so this class owns
        // the process and its teardown (see close()).
        serverProcess = ManagedProcess.start("bukkit-" + platform, serverStage,
                stack.logDir().resolve(logName), ManagedProcess.env(), command);
        started.add(serverProcess);
        return serverProcess;
    }

    /**
     * The always-on node the endpoint links to.
     *
     * <p>{@code peer.mode: external} is not a test convenience: it is the mode L-71 names as the fix
     * for a node that dies with the server JVM. The endpoint therefore gets a worker of its OWN —
     * not one of the players' — on the port its config points at, started the same way every other
     * worker in the stack is.
     *
     * @throws HarnessException if it never answers within 90 s, which is the shell's own bound.
     */
    public ControlClient startEndpointWorker() {
        Path home = paths.runDir().resolve("worker-endpoint");
        deleteRecursively(home);
        try {
            Files.createDirectories(home);
        } catch (IOException e) {
            throw new HarnessException("cannot create " + home, e);
        }

        // NODERA_STATE_DIR is the one that took a live debugging session to notice. Without it every
        // simulated peer defaults to ~/.nodera and therefore shares ONE worlds.dat and ONE
        // world-keys directory with every other peer AND with the operator's real installation.
        Map<String, String> env = ManagedProcess.env(
                "NODERA_CONTROL_HOST", "127.0.0.1",
                "NODERA_CONTROL_PORT", String.valueOf(endpointControl),
                "NODERA_P2P_BIND", "127.0.0.1",
                "NODERA_P2P_ADVERTISE", "127.0.0.1",
                "NODERA_P2P_PORT", String.valueOf(endpointP2p),
                "NODERA_STATE_DIR", home.toString(),
                "NODERA_IDENTITY_FILE", home.resolve("identity.bin").toString(),
                "NODERA_WORLDS_FILE", home.resolve("worlds.dat").toString(),
                "NODERA_WORLD_KEYS_DIR", home.resolve("world-keys").toString(),
                "NODERA_ARCHIVE_DIR", home.resolve("archive").toString(),
                "NODERA_TRACKER_ENDPOINTS", String.join(",", topology.trackerEndpoints()),
                "NODERA_RENDEZVOUS_ENDPOINTS", String.join(",", topology.rendezvousEndpoints()));

        // HARNESS-GAP: LiveStack.startWorkers is private and starts exactly the topology's workers,
        // so the endpoint's own worker is started here with the same environment contract.
        started.add(ManagedProcess.start("worker-endpoint", paths.root(),
                stack.logDir().resolve("worker-endpoint.log"), env,
                List.of(paths.workerDist().toString())));

        ControlClient client = endpoint();
        client.awaitUp(Duration.ofSeconds(90));
        context.note("endpoint worker up on control " + endpointControl + " · p2p " + endpointP2p);
        return client;
    }

    /**
     * Stage, start, and wait for the endpoint to be genuinely usable: the server accepting logins
     * AND its peer answering its control socket.
     *
     * <p>Waiting only for "Done" would hand the scenario a server whose node has not announced
     * anything yet, and every later assertion would race the boot.
     */
    public void bukkitUp(String wanted, String worldId) {
        platform = wanted;
        stageBukkitServer(worldId);
        startBukkitServer("server.log");
        LogWatcher log = stack.log("server.log");
        log.await("Done (", Duration.ofSeconds(420));
        log.await("NoderaEndpoint", Duration.ofSeconds(60));
        endpoint().awaitUp(topology.scaled(Duration.ofSeconds(120)));
        context.note("endpoint up: " + wanted + " on " + topology.gamePort() + " · control "
                + endpointControl + " · p2p " + endpointP2p);
    }

    // ---------------------------------------------------------------------------------------
    // Wire helpers
    // ---------------------------------------------------------------------------------------

    /**
     * The ENDPOINT's own peer, over ControlProtocol v2.
     *
     * <p>Deliberately the same verb table the headless workers answer — that is the whole point of
     * the endpoint serving the loopback socket, and it means every existing probe in the harness
     * works against a Paper server unchanged.
     */
    public ControlClient endpoint() {
        return new ControlClient(endpointControl);
    }

    /**
     * One field out of the endpoint's {@code NODERA-STATE}, by dotted path.
     *
     * @return the field, or empty when the worker did not answer or the path is absent.
     */
    public Optional<String> endpointStateField(String path) {
        return endpoint().ask("NODERA-STATE 2")
                .flatMap(ServerJson::tryParse)
                .flatMap(document -> ServerJson.text(document, path));
    }

    /** The endpoint claims what the primary-host contract (C1) requires of it. */
    public void assertCustodyFull() {
        String custody = endpointStateField("custody.class").orElse("<absent>");
        if (!"FULL".equals(custody)) {
            throw new HarnessException("the endpoint reports custody '" + custody
                    + "', expected FULL (docs/server/Task.3.md C1)");
        }
    }

    /**
     * No thread-context violation in any of the named logs.
     *
     * @throws HarnessException quoting up to three offending lines.
     */
    public void assertNoThreadContextViolations(String... logNames) {
        List<String> hits = new ArrayList<>();
        for (String name : logNames) {
            Path file = stack.logDir().resolve(name);
            if (!Files.isReadable(file)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(file)) {
                    if (THREAD_CONTEXT_VIOLATION.matcher(line).find() && hits.size() < 3) {
                        hits.add(line);
                    }
                }
            } catch (IOException unreadable) {
                // A log being appended to as it is read can hand back a malformed tail; the next
                // caller reads a complete file. Treat it as "nothing found here" rather than as a
                // harness failure.
            }
        }
        if (!hits.isEmpty()) {
            throw new HarnessException("thread-context violation(s) on a tick thread:\n"
                    + String.join("\n", hits));
        }
    }

    // ---------------------------------------------------------------------------------------

    /** Stop everything this class started, in reverse order. Idempotent. */
    @Override
    public void close() {
        for (int i = started.size() - 1; i >= 0; i--) {
            started.get(i).stop(Duration.ofSeconds(20));
        }
        started.clear();
        serverProcess = null;
    }

    private static String yamlArray(List<String> values) {
        return values.stream().map(value -> '"' + value + '"')
                .reduce((a, b) -> a + ", " + b).map(joined -> "[" + joined + "]").orElse("[]");
    }

    static void deleteRecursively(Path directory) {
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
}
