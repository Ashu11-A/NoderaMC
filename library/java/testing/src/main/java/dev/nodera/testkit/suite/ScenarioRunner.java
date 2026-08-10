package dev.nodera.testkit.suite;

import dev.nodera.testkit.harness.LayoutManifest;
import dev.nodera.testkit.harness.HarnessException;
import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.ManagedProcess;
import dev.nodera.testkit.harness.TestPaths;
import dev.nodera.testkit.harness.Topology;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runs a queue of scenarios: one build, one lock, one stack per scenario, one report at the end.
 *
 * <h2>Why the queue keeps going after a failure</h2>
 *
 * <p>A batch that stops at the first red suite tells you about one lane and nothing about the other
 * nineteen — and the first red lane is often the flakiest, not the most important. Every scenario
 * runs, the queue's exit status is the worst outcome in it, and the report says which of them
 * failed and where the evidence is.
 *
 * <h2>Why each scenario gets a fresh stack</h2>
 *
 * <p>Scenarios kill hosts, corrupt archives, and re-key worlds on purpose. Sharing one stack across
 * a queue would let the third scenario inherit the second's damage and report it as its own — which
 * is exactly how a shell batch produced failures nobody could reproduce alone.
 *
 * <p>Thread-context: single-threaded by design. Live scenarios share the port block and the game
 * directories; running two at once is not a speed-up, it is a different test.
 */
public final class ScenarioRunner {

    private final TestPaths paths;
    private final boolean build;
    private final boolean keepRunning;

    public ScenarioRunner(TestPaths paths, boolean build, boolean keepRunning) {
        this.paths = paths;
        this.build = build;
        this.keepRunning = keepRunning;
    }

    /**
     * Run a queue.
     *
     * @param scenarios the scenarios, in the order they should execute.
     * @return one result per scenario, in the same order.
     */
    public List<ScenarioResult> runAll(List<Scenario> scenarios) {
        if (build) {
            buildWhatTheQueueNeeds(scenarios);
        }
        List<ScenarioResult> results = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            results.add(run(scenario));
        }
        return results;
    }

    /** Run one scenario, whatever happens. */
    public ScenarioResult run(Scenario scenario) {
        Instant started = Instant.now();
        Optional<Skip> unmet = scenario.requirements().unmet(paths);
        var resultsDir = paths.newRunDirectory(scenario.id());
        if (unmet.isPresent()) {
            return new ScenarioResult(scenario.id(), scenario.title(),
                    List.copyOf(scenario.tags()), StageResult.Outcome.SKIPPED,
                    unmet.get().reason(), List.of(), started,
                    Duration.between(started, Instant.now()), resultsDir, unmet.get().kind());
        }

        Topology topology = scenario.topology();
        ScenarioContext context = null;
        try (LiveStack stack = new LiveStack(paths, topology, resultsDir, keepRunning)) {
            context = new ScenarioContext(stack, scenario.id());
            stack.acquireLock(Duration.ofMinutes(15));
            stack.startInfrastructure();
            scenario.run(context);
            stack.collectArtefacts();
            return new ScenarioResult(scenario.id(), scenario.title(), List.copyOf(scenario.tags()),
                    StageResult.Outcome.PASSED, "", context.stages(), started,
                    Duration.between(started, Instant.now()), resultsDir);
        } catch (SkipSignal skip) {
            return new ScenarioResult(scenario.id(), scenario.title(), List.copyOf(scenario.tags()),
                    StageResult.Outcome.SKIPPED, skip.getMessage(),
                    context == null ? List.of() : context.stages(), started,
                    Duration.between(started, Instant.now()), resultsDir, skip.kind());
        } catch (Exception failure) {
            return new ScenarioResult(scenario.id(), scenario.title(), List.copyOf(scenario.tags()),
                    StageResult.Outcome.FAILED, describe(failure),
                    context == null ? List.of() : context.stages(), started,
                    Duration.between(started, Instant.now()), resultsDir);
        }
    }

    /**
     * Build what this queue needs, before the lock is taken.
     *
     * <p>The endpoint plugin jar is on the list because without it the server-category scenarios
     * could only ever skip: their requirement looks for a jar that nothing else in the harness
     * produces. A skip that is structural rather than circumstantial is not a skip — it is a
     * scenario that never runs.
     *
     * <p>The Minecraft-side builds are conditional for the opposite reason. A queue of one headless
     * scenario does not need a NeoForge mod jar, and making it wait for one turns a three-minute
     * consent-lane job into a fifteen-minute one that fails for reasons in a module it never
     * touches.
     */
    private void buildWhatTheQueueNeeds(List<Scenario> scenarios) {
        boolean needsMinecraft = scenarios.stream().anyMatch(scenario ->
                scenario.tags().contains("live") || scenario.tags().contains("server"));
        // Every service binary a scenario can ask for, including the telemetry collector. It is on
        // this list for the same reason the endpoint plugin jar is on the Gradle one: the telemetry
        // scenario's first stage looks for a collector nothing else in the harness builds, so
        // leaving it out turns a scenario into one that can only ever fail on its own preflight.
        // Selected by PACKAGE, not by binary name: `--bin` after a `-p` is scoped to that package,
        // so mixing the two forms asks cargo for a tracker binary inside the telemetry crate and it
        // refuses — correctly, and confusingly.
        run("cargo", List.of("cargo", "build", "--release",
                "-p", "nodera-tracker", "-p", "nodera-rendezvous", "-p", "nodera-telemetry"),
                LayoutManifest.load().dir("cargoWorkspace"));
        List<String> gradle = new ArrayList<>(List.of(paths.root().resolve("gradlew").toString(),
                ":peer:installDist"));
        if (needsMinecraft) {
            gradle.add(":neoforge-mod:build");
            gradle.add(":paper-plugin:jar");
        }
        gradle.addAll(List.of("-x", "test", "-x", "check", "--console=plain"));
        run("gradle", gradle, paths.root());
    }

    private void run(String what, List<String> command, java.nio.file.Path workingDir) {
        try {
            Files.createDirectories(paths.runDir());
        } catch (Exception ignored) {
            // The build log directory is best effort; the build itself reports its own failure.
        }
        ManagedProcess process = ManagedProcess.start("build-" + what, workingDir,
                paths.runDir().resolve("build-" + what + ".log"), ManagedProcess.env(), command);
        int exit = process.awaitExit(Duration.ofMinutes(45));
        if (exit != 0) {
            throw new HarnessException("the " + what + " build failed (exit " + exit + ") — see "
                    + paths.runDir().resolve("build-" + what + ".log"));
        }
    }

    private static String describe(Exception failure) {
        String message = failure.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return failure.getClass().getSimpleName() + " with no message";
    }
}
