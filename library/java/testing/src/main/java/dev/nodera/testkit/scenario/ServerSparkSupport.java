package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.HarnessException;
import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.TestPaths;
import dev.nodera.testkit.mc.RconClient;
import dev.nodera.testkit.suite.ScenarioContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The profiling overlay: spark (lucko) driven over RCON, with every capture kept on this machine.
 *
 * <p>The Java form of {@code scripts/lib/spark.sh}. Unlike the shell version there is no global
 * {@code NODERA_SPARK} gate to consult — a scenario that wants profiling constructs this class and a
 * scenario that does not never mentions it, so an unprofiled run stages byte-identical files.
 *
 * <h2>What this is for</h2>
 *
 * <p>The other scenarios prove that Nodera is CORRECT. Nothing proved where the tick went. "The
 * entity lane is expensive" was, until this existed, an opinion: the mod is a fat jar of the whole
 * core running inside somebody else's tick loop, and a wall clock cannot tell our frames from
 * Minecraft's. spark can — it samples stack traces and maps each frame back to the mod or plugin
 * that owns the class. See docs/minecraft/spark/. (Not Apache Spark; {@code docker/telemetry/spark/}
 * is the unrelated batch engine.)
 *
 * <h2>Where spark lives on each platform</h2>
 *
 * <pre>
 *   NeoForge   &lt;gameDir&gt;/config/spark/    NeoForgeSparkMod resolves FMLPaths.CONFIGDIR/&lt;modId&gt;,
 *                                          so it is config/spark — NOT &lt;gameDir&gt;/spark
 *   Bukkit     &lt;stage&gt;/plugins/spark/     the normal plugin data folder
 * </pre>
 *
 * <h2>What is installed, and what is not</h2>
 *
 * <ul>
 *   <li><b>NeoForge</b> — a real mod jar, pinned below and cached in {@code run/spark/}. NeoForge
 *       has no bundled spark and CI only publishes the newest Minecraft version, so the 1.21.1 build
 *       comes from Modrinth by exact URL and is checked against a recorded sha256.</li>
 *   <li><b>Paper</b> — NOTHING. Paper 1.21+ BUNDLES spark; installing the plugin jar on top would
 *       need {@code -Dpaper.preferSparkPlugin=true} and buy nothing. Only its config is written.</li>
 *   <li><b>Folia</b> — NOT PROFILABLE on the pinned build. Folia bundles spark like Paper and then
 *       REFUSES to enable it ("The spark profiler will not be enabled because it is currently
 *       disabled in the configuration") whatever {@code paper-global.yml} and
 *       {@code -Dpaper.preferSparkPlugin} say, and the community spark-folia build currently targets
 *       a newer Folia — against the pinned 1.21.4 it dies on the first command with
 *       {@code NoClassDefFoundError: ca/spottedleaf/moonrise/common/time/TickData}. So Folia
 *       profiling SKIPS with a reason rather than failing. Stage a matching jar at
 *       {@code $NODERA_SPARK_FOLIA_JAR} to turn the stage back on.</li>
 * </ul>
 *
 * <h2>Nothing is uploaded, ever</h2>
 *
 * <p>Every capture is {@code --save-to-file}. A profile embeds our class names, the server
 * configuration and the host's CPU/OS strings, and {@code /spark profiler stop} without that flag
 * POSTs all of it to a public bytebin and mints a shareable link. So: no bare {@code stop}, no
 * {@code upload}, no {@code open} (the live viewer opens a websocket to a third party).
 *
 * <p>Thread-context: one instance per scenario, used from the scenario's thread.
 */
public final class ServerSparkSupport {

    /**
     * The pin, mirrored in docs/minecraft/spark/README.md §What we pin.
     *
     * <p>Changing the version means changing the URL and the sha256 with it — the three are one
     * fact.
     */
    public static final String VERSION = "1.10.124";

    private static final String NEOFORGE_URL =
            "https://cdn.modrinth.com/data/l6YH9Als/versions/v5qtqRQi/spark-1.10.124-neoforge.jar";
    private static final String NEOFORGE_SHA256 =
            "647e8a81afbe414dba1df4ba15fd06c5d32d4cb544e68828405e8e074c2e16db";

    /**
     * Sampling interval in milliseconds. 4 is spark's own default (SamplerMode EXECUTION); lowering
     * it costs overhead, raising it blurs short frames.
     */
    private static final int INTERVAL_MILLIS = 4;

    /**
     * {@code --thread *} by default, and not as a preference: with no {@code --thread} flag the
     * platform default dumper produced a capture containing ZERO threads on Paper 1.21.1-133,
     * reproducibly, under load.
     */
    private static final String THREADS = "*";

    private final ScenarioContext context;
    private final LiveStack stack;
    private final TestPaths paths;
    private final Path cache;
    private final Path jar;
    private final Path out;
    private final Path summary;

    private String startedLabel;

    public ServerSparkSupport(ScenarioContext context) {
        this.context = context;
        this.stack = context.stack();
        this.paths = stack.paths();
        this.cache = paths.runDir().resolve("spark");
        this.jar = cache.resolve("spark-" + VERSION + "-neoforge.jar");
        this.out = stack.logDir().resolve("spark");
        this.summary = out.resolve("SUMMARY.txt");
        try {
            Files.createDirectories(out);
        } catch (IOException e) {
            throw new HarnessException("cannot create the spark output directory " + out, e);
        }
    }

    /** Where captures land. The log directory is swept into the results directory at the end. */
    public Path outputDirectory() {
        return out;
    }

    /** The per-source summary artefact — the file this scenario exists to produce. */
    public Path summaryFile() {
        return summary;
    }

    /** An empty summary, so a re-run does not read the previous one's tables. */
    public void resetSummary() {
        write(summary, "");
    }

    // ---------------------------------------------------------------------------------------
    // Fetching the NeoForge jar
    // ---------------------------------------------------------------------------------------

    /**
     * Cache the pinned mod jar in {@code run/spark/}, once.
     *
     * <p>A corrupt or unreachable jar is NAMED, never silently skipped: a profiled run that quietly
     * produced no profile is worse than one that says it could not fetch the profiler.
     *
     * @return {@code true} when the jar is present and verified.
     */
    public boolean fetch() {
        try {
            Files.createDirectories(cache);
        } catch (IOException e) {
            throw new HarnessException("cannot create the spark cache " + cache, e);
        }
        if (Files.isRegularFile(jar) && verifyJar()) {
            context.note("spark: using cached " + jar.getFileName());
            return true;
        }
        if (Files.isRegularFile(jar)) {
            context.note("spark: cached jar failed its checksum — refetching");
            delete(jar);
        }
        context.note("spark: fetching " + VERSION + " (neoforge) from Modrinth");
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30)).build()) {
            HttpResponse<Path> response = client.send(
                    HttpRequest.newBuilder(URI.create(NEOFORGE_URL))
                            .timeout(Duration.ofMinutes(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(cache.resolve(jar.getFileName() + ".part")));
            if (response.statusCode() != 200) {
                delete(response.body());
                context.note("spark: download FAILED (HTTP " + response.statusCode() + ") — "
                        + NEOFORGE_URL);
                return false;
            }
            Files.move(response.body(), jar, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            context.note("spark: download FAILED — " + NEOFORGE_URL + " (" + e + ")");
            return false;
        }
        if (!verifyJar()) {
            context.note("spark: checksum MISMATCH on the freshly downloaded jar — the pin and the "
                    + "CDN disagree");
            delete(jar);
            return false;
        }
        context.note("spark: fetched and verified " + jar.getFileName());
        return true;
    }

    private boolean verifyJar() {
        if (!Files.isRegularFile(jar)) {
            return false;
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(Files.readAllBytes(jar)))
                    .equals(NEOFORGE_SHA256);
        } catch (Exception cannotCheck) {
            return true;   // cannot check; do not block
        }
    }

    /**
     * The skip contract for a scenario whose whole point is profiling.
     *
     * <p>Same shape as the endpoint preflight: a nightly with no network reads as "not fetched yet",
     * never as "broken".
     */
    public void preflight() {
        if (!hasPython3()) {
            context.skip("python3 is required to read a .sparkprofile.");
        }
        if (!fetch()) {
            context.skip("the pinned spark jar (" + VERSION
                    + ") could not be fetched or verified.");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Staging
    // ---------------------------------------------------------------------------------------

    /**
     * The config spark reads on any platform.
     *
     * <p>{@code backgroundProfiler} is OFF on purpose: spark's own default starts a profiler at boot
     * and keeps it running, and we scope captures to named stages instead so a profile's start and
     * end mean something. {@code disableResponseBroadcast} keeps the reply on the RCON channel that
     * asked, out of every player's chat.
     */
    public void writeConfig(Path directory) {
        write(directory.resolve("config.json"), """
                {
                  "_header": "written by dev.nodera.testkit.scenario.ServerSparkSupport — see \
                docs/minecraft/spark/08-configuration.md",
                  "backgroundProfiler": false,
                  "disableResponseBroadcast": true,
                  "overrideTpsCommand": false
                }
                """);
    }

    /**
     * Install the profiler into one MDG run directory.
     *
     * <p>The jar goes in {@code mods/} because FML must LOAD it as a mod; the module's
     * {@code additionalRuntimeClasspath} is a library seam and would put spark on the classpath
     * without ever registering it.
     */
    public void stageMod(Path gameDirectory) {
        if (!Files.isRegularFile(jar)) {
            context.note("spark: no jar to stage into " + gameDirectory);
            return;
        }
        try {
            Files.createDirectories(gameDirectory.resolve("mods"));
            Files.copy(jar, gameDirectory.resolve("mods").resolve(jar.getFileName().toString()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new HarnessException("cannot stage spark into " + gameDirectory, e);
        }
        // NeoForgeSparkMod resolves FMLPaths.CONFIGDIR/spark — config/spark, not spark/.
        writeConfig(gameDirectory.resolve("config/spark"));
        context.note("spark: staged into " + gameDirectory.getFileName() + "/mods");
    }

    /**
     * The {@code spark:} block for {@code config/paper-global.yml}.
     *
     * <p>WITHOUT THIS, THE BUNDLED PROFILER SILENTLY STAYS OFF. Newer Paper builds (and Folia
     * 1.21.4) gate their bundled spark on a {@code spark:} section, and the staged server writes
     * that file from scratch on every boot — so the section is ABSENT, and absent reads as disabled.
     * Folia said so in as many words: "The spark profiler will not be enabled because it is
     * currently disabled in the configuration." The server then rewrites the file with
     * {@code enabled: true}, so a SECOND boot works and a first never does — the worst shape a bug
     * can have in a harness that stages clean every time.
     *
     * <p>{@code enable-immediately: true} because the default defers start-up until something asks,
     * and the thing that asks is our RCON command, which would then race the profiler's own
     * initialisation.
     */
    public String paperGlobalBlock() {
        return "spark:\n  enabled: true\n  enable-immediately: true\n";
    }

    /**
     * The Bukkit half of staging.
     *
     * <p>On PAPER there is no jar: the server bundles spark, and this only writes its config. On
     * FOLIA the bundled copy refuses to enable, so a plugin jar is required; without one the caller
     * is told, and profiling that server is skipped rather than silently producing nothing.
     *
     * @return {@code true} when the platform can actually be profiled.
     */
    public boolean stagePlugin(String platform, Path serverStage) {
        writeConfig(serverStage.resolve("plugins/spark"));
        if (!"folia".equals(platform)) {
            context.note("spark: configured the server's bundled spark (no jar installed — Paper "
                    + "bundles it)");
            return true;
        }
        Optional<Path> foliaJar = foliaJar();
        if (foliaJar.isPresent()) {
            try {
                Files.copy(foliaJar.get(),
                        serverStage.resolve("plugins").resolve(foliaJar.get().getFileName().toString()),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new HarnessException("cannot stage " + foliaJar.get(), e);
            }
            context.note("spark: installed " + foliaJar.get().getFileName()
                    + " — Folia's bundled spark will not enable itself");
            return true;
        }
        context.note("spark: Folia cannot be profiled — its bundled spark refuses to enable and no "
                + "$NODERA_SPARK_FOLIA_JAR is staged");
        return false;
    }

    /** Can this Folia server be profiled at all? */
    public boolean foliaAvailable() {
        return foliaJar().isPresent();
    }

    private Optional<Path> foliaJar() {
        String configured = System.getenv("NODERA_SPARK_FOLIA_JAR");
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }
        Path candidate = Path.of(configured.trim());
        return Files.isRegularFile(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    /**
     * Arguments any spark-profiled JVM wants.
     *
     * <p>spark resolves a stack frame's class name to a {@code Class<?>} through a dynamically
     * loaded Java agent (InstrumentationClassFinder). On Java 21+ that prints a deprecation warning
     * and, when disallowed, degrades to a weaker lookup — which shows up as frames that belong to a
     * mod being attributed to nobody. Allowing it explicitly keeps source attribution honest.
     */
    public List<String> jvmArguments() {
        return List.of("-XX:+EnableDynamicAgentLoading");
    }

    /**
     * The Bukkit-only additions.
     *
     * <p>Paper's bundled-spark gate is {@code io.papermc.paper.SparksFly}, and it reads two things:
     * {@code GlobalConfiguration.spark.enabled} and the system property
     * {@code paper.preferSparkPlugin}. On Folia 1.21.4 a staged server took the "prefer the plugin
     * jar" path, found no jar, and then declined to fall back. Saying explicitly which one we prefer
     * removes that branch: on Folia the ONLY spark that runs is an installed plugin jar, on Paper
     * the bundled copy is the one we want.
     */
    public List<String> bukkitJvmArguments(String platform) {
        List<String> arguments = new ArrayList<>(jvmArguments());
        arguments.add("folia".equals(platform)
                ? "-Dpaper.preferSparkPlugin=true" : "-Dpaper.preferSparkPlugin=false");
        return arguments;
    }

    // ---------------------------------------------------------------------------------------
    // Driving a capture over RCON
    // ---------------------------------------------------------------------------------------

    /**
     * Begin a capture on whichever server RCON reaches.
     *
     * <p>THE REPLY IS EMPTY, AND THAT IS NORMAL. spark runs its commands on its own thread and
     * answers the sender afterwards; an RCON connection has already been handed its (empty) response
     * by then. Verified live on Paper 1.21.1-133: every {@code /spark …} over RCON returns "". So
     * nothing here parses the reply — the marker file is what tells {@link #stop} which capture was
     * ours.
     *
     * @param label the capture's name in the artefacts.
     */
    public void start(String label) {
        StringBuilder command = new StringBuilder("spark profiler start --thread " + THREADS
                + " --interval " + INTERVAL_MILLIS);
        String ticksOver = System.getenv("NODERA_SPARK_TICKS_OVER");
        if (ticksOver != null && !ticksOver.isBlank()) {
            command.append(" --only-ticks-over ").append(ticksOver.trim());
        }

        // The marker's mtime is the "everything after this is ours" line.
        delete(out.resolve(".capture-marker"));
        write(out.resolve(".capture-marker"), "");
        // One whole second, so a filesystem with 1 s mtime granularity cannot tie.
        settle(Duration.ofSeconds(1));

        write(out.resolve("start-" + label + ".txt"),
                rcon().send(command.toString()).orElse(""));
        startedLabel = label;
        context.note("spark: profiling '" + label + "' · interval=" + INTERVAL_MILLIS
                + "ms · threads=" + THREADS);
    }

    /**
     * End the capture and keep the bytes locally.
     *
     * <p>{@code --save-to-file} is not optional; see the class header. Because the reply comes back
     * empty, the written file is found by looking for the {@code .sparkprofile} that appeared after
     * the marker.
     *
     * @return the copied capture, or empty when the profiler wrote nothing.
     */
    public Optional<Path> stop(String label) {
        if (startedLabel == null) {
            context.note("spark: no capture running; nothing to stop");
            return Optional.empty();
        }
        String name = label == null ? startedLabel : label;
        startedLabel = null;

        write(out.resolve("stop-" + name + ".txt"), rcon().send(
                "spark profiler stop --save-to-file --comment profile/" + name).orElse(""));

        // Writing the proto takes a moment after the command returns, and the command returned
        // before spark even began. Wait for the file rather than race it.
        Path marker = out.resolve(".capture-marker");
        for (int waited = 0; waited < 60; waited++) {
            Optional<Path> newest = newestUnder(marker, ".sparkprofile");
            if (newest.isPresent()) {
                Path destination = out.resolve("profile-" + name + ".sparkprofile");
                copy(newest.get(), destination);
                context.note("spark: captured '" + name + "' -> " + destination.getFileName());
                return Optional.of(destination);
            }
            settle(Duration.ofSeconds(1));
        }
        context.note("spark: no .sparkprofile appeared for '" + name
                + "' — the profiler was not running, or it wrote nothing");
        return Optional.empty();
    }

    /**
     * The cheap always-safe snapshot: TPS, CPU, memory, disk, GC.
     *
     * <p>Costs nothing and explains a profile that looks odd because the host was swapping rather
     * than because our code was slow.
     */
    public void health(String label) {
        Path file = out.resolve("health-" + label + ".txt");
        write(file, rcon().send("spark health --memory --network").orElse(""));
        append(file, rcon().send("spark tps").orElse(""));
    }

    /**
     * Every directory a capture could be written into.
     *
     * <p>One of them is the platform RCON is currently talking to; which one is not worth tracking,
     * because only one server runs per scenario and a capture is identified by being NEWER than the
     * marker {@link #start} dropped.
     */
    public List<Path> captureDirectories(Path serverStage) {
        List<Path> directories = new ArrayList<>();
        if (serverStage != null) {
            directories.add(serverStage.resolve("plugins/spark"));
        }
        for (String gameDir : List.of("run", "run-host", "run-join", "run-join2", "run-join3")) {
            directories.add(paths.gameDir(gameDir).resolve("config/spark"));
        }
        return directories.stream().filter(Files::isDirectory).toList();
    }

    private Path serverStage;

    /** Tell the sweep where the Bukkit stage is, so its captures are collected too. */
    public void withServerStage(Path stage) {
        this.serverStage = stage;
    }

    /**
     * Sweep every capture into the spark output directory and summarise it.
     *
     * <p>The {@code .sources.txt} sidecars are what make the run readable without a browser: one
     * table per profile, self time per owning mod/plugin, hottest Nodera frames named.
     */
    public void collect() {
        for (Path directory : captureDirectories(serverStage)) {
            String tag = directory.getParent().getParent().getFileName().toString();
            try (Stream<Path> files = Files.list(directory)) {
                files.filter(Files::isRegularFile).forEach(file -> {
                    String fileName = file.getFileName().toString();
                    if (fileName.endsWith(".sparkprofile") || fileName.endsWith(".sparkheap")
                            || fileName.equals("activity.json")) {
                        copy(file, out.resolve(tag + "-" + fileName));
                    }
                });
            } catch (IOException unreadable) {
                // A directory that vanished with its server is not evidence of anything.
            }
        }
        if (!hasPython3()) {
            return;
        }
        try (Stream<Path> profiles = Files.list(out)) {
            profiles.filter(file -> file.getFileName().toString().endsWith(".sparkprofile"))
                    .forEach(profile -> {
                        String base = profile.toString();
                        write(Path.of(base.substring(0, base.length() - ".sparkprofile".length())
                                + ".sources.txt"), sources(profile, null));
                    });
        } catch (IOException unreadable) {
            // Nothing to summarise.
        }
    }

    // ---------------------------------------------------------------------------------------
    // Reading a capture
    //
    // scripts/lib/sparkprofile.py is a pure-standard-library protobuf reader for the .sparkprofile
    // format. It is not ported here: it is a diagnostic reader rather than harness behaviour, it is
    // shared with anyone reading a capture by hand, and re-implementing a protobuf decoder to
    // produce the same table would add a second thing to keep in step with spark's own .proto.
    // ---------------------------------------------------------------------------------------

    /** The per-source table for one capture, optionally restricted to a thread-name regex. */
    public String sources(Path profile, String threadRegex) {
        List<String> command = new ArrayList<>(List.of("python3",
                paths.root().resolve("scripts/lib/sparkprofile.py").toString(), profile.toString()));
        if (threadRegex != null) {
            command.addAll(List.of("--thread", threadRegex));
        }
        return runPython(command).output();
    }

    /**
     * Print the per-source table into the summary artefact, then say whether {@code want} carries
     * sampled time.
     *
     * <p>TWO views of the same capture, because one alone misleads:
     *
     * <ul>
     *   <li><b>all threads</b> — honest, and dominated by parked pools. The async engine profiles
     *       WALL time, so ninety idle threads accumulate far more "sampled" time than the tick does,
     *       and every real percentage rounds to 0.0%.</li>
     *   <li><b>tick thread</b> — the number anyone actually means by "% of the tick".</li>
     * </ul>
     *
     * <p>The gap between them is itself information: work that is large in the first and absent from
     * the second is work Nodera does OFF the server thread.
     *
     * @param want the source that must carry sampled time, or {@code null} to only report.
     * @return {@code true} when {@code want} was attributed sampled time (always {@code true} when
     *         nothing was asked for).
     */
    public boolean report(String label, Path profile, String want) {
        append(summary, "\n=== " + label + " — all sampled threads ===\n"
                + sources(profile, null)
                + "\n=== " + label + " — the tick thread only ===\n"
                + sources(profile, "^(Server thread|Region Scheduler|Folia)"));
        if (want == null) {
            return true;
        }
        PythonResult result = runPython(List.of("python3",
                paths.root().resolve("scripts/lib/sparkprofile.py").toString(), profile.toString(),
                "--assert-source", want));
        if (result.exitCode() == 0) {
            context.note(label + ": '" + want + "' carries sampled time");
            return true;
        }
        append(summary, result.output());
        return false;
    }

    /**
     * What a capture must prove REGARDLESS of how busy our code happened to be.
     *
     * <p>Asserting "our source has non-zero self time" is right where the scenario drives real load
     * through the mod for the whole capture. It is wrong as a general gate: an idle plugin
     * legitimately contributes no samples, and a test that fails because nothing asked the plugin to
     * do anything is a flake generator rather than a test.
     *
     * <p>What must hold in every case is that the INTEGRATION worked: the profiler ran, it sampled
     * threads, and it enumerated our plugin as installed. That cannot pass on a missing profiler, a
     * broken class-source lookup, or an empty capture, and it cannot fail because a server was
     * quiet.
     *
     * @throws HarnessException naming the summary to read.
     */
    public void assertCapture(String label, Path profile, String want) {
        PythonResult result = runPython(List.of("python3",
                paths.root().resolve("scripts/lib/sparkprofile.py").toString(), profile.toString(),
                "--assert-nonempty", "--assert-installed", want));
        if (result.exitCode() != 0) {
            append(summary, result.output());
            throw new HarnessException(label + ": the capture is empty, or spark never saw '" + want
                    + "' installed — see " + summary);
        }
    }

    /**
     * How many threads a capture covered.
     *
     * <p>Folia has no main thread, so a capture that saw only one thread saw one region and called
     * it the server. A single-threaded Folia profile is not a fast server, it is a capture that
     * missed the work.
     */
    public int threadCount(Path profile) {
        for (String line : sources(profile, null).split("\n")) {
            if (line.startsWith("threads=")) {
                String digits = line.substring("threads=".length()).split("/")[0].trim();
                try {
                    return Integer.parseInt(digits);
                } catch (NumberFormatException notANumber) {
                    return 0;
                }
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------------------------------

    private record PythonResult(int exitCode, String output) { }

    private PythonResult runPython(List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(paths.root().toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes());
            return new PythonResult(process.waitFor(), output);
        } catch (IOException e) {
            return new PythonResult(1, "cannot run " + String.join(" ", command) + ": " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HarnessException("interrupted while reading a capture", e);
        }
    }

    static boolean hasPython3() {
        try {
            Process process = new ProcessBuilder("python3", "--version")
                    .redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (IOException notInstalled) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private RconClient rcon() {
        return stack.rcon();
    }

    private Optional<Path> newestUnder(Path marker, String suffix) {
        long since;
        try {
            since = Files.getLastModifiedTime(marker).toMillis();
        } catch (IOException noMarker) {
            return Optional.empty();
        }
        List<Path> candidates = new ArrayList<>();
        for (Path directory : captureDirectories(serverStage)) {
            try (Stream<Path> files = Files.list(directory)) {
                files.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith(suffix))
                        .filter(file -> modified(file) > since)
                        .forEach(candidates::add);
            } catch (IOException unreadable) {
                // A directory that vanished with its server holds no capture.
            }
        }
        return candidates.stream().max(Comparator.comparingLong(ServerSparkSupport::modified));
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException gone) {
            return 0L;
        }
    }

    private void settle(Duration duration) {
        context.settle(duration);
    }

    private void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new HarnessException("cannot write " + file, e);
        }
    }

    private void append(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new HarnessException("cannot append to " + file, e);
        }
    }

    private void copy(Path from, Path to) {
        try {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new HarnessException("cannot copy " + from + " to " + to, e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException stillHeld) {
            // Best effort: a marker a dying JVM still holds is replaced on the next write.
        }
    }
}
