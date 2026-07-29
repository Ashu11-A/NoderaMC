package dev.nodera.structure;

import dev.nodera.structure.CodeGraph.Finding;
import dev.nodera.structure.HeadlessDebugProbe.RuntimeProfile;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The structural code report, and the ratchet that keeps it from getting worse.
 *
 * <h2>What this suite is for</h2>
 *
 * <p>Every other suite in this repository asserts that something works. This one asserts something
 * about the shape of the code itself: that it does not accumulate parts which ship, load, and do
 * nothing. That failure mode has no test that can fail — the code is correct, it is covered, and it
 * is simply never called — which is why it has been found by hand, repeatedly, and why it needed a
 * gate of its own.
 *
 * <h2>How to run it</h2>
 *
 * <pre>
 *   ./gradlew :worker:structureReport                  # analysis + debugger probe + report
 *   ./gradlew :worker:structureReport -Pstructure.debug=false   # static analysis only (fast)
 * </pre>
 *
 * <p>It is tagged {@code structure} and excluded from {@code :worker:test}, because it reads the
 * compiled output of EVERY module: running it inside the normal unit-test task would make one
 * module's fast feedback depend on nine modules being compiled first.
 *
 * <p>Outputs, both under {@code build/reports/nodera/}: {@code STRUCTURE.md} (for people) and
 * {@code structure.json} (complete, for tools and for the next run's diff).
 */
@Tag("structure")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StructuralReportTest {

    /**
     * Modules whose compiled output MUST be present, or the analysis would invent dead code.
     *
     * <p>The Minecraft-bound modules are on the list for exactly that reason: the mod and the Paper
     * plugin are production callers of the peer libraries, and analysing the tree without them
     * would report everything only they call as dead. `:testing` is absent because its main source
     * set is scanned as test code, not as a caller.
     */
    private static final List<String> REQUIRED_MODULES = List.of(
            "core", "engine", "transport", "storage", "peer", "worker", "neoforge-mod",
            "paper-plugin");

    /**
     * Reports land in the REPOSITORY's build directory, beside `BENCHMARKS.md`: both artefacts
     * describe the whole tree, and burying them under the module that happened to generate them
     * would make them look like worker-specific output.
     */
    private static final Path REPORT_DIR = repoRoot().resolve("build/reports/nodera");
    private static final Path PROFILE_FILE = REPORT_DIR.resolve("runtime-profile.json");
    private static final Path MARKDOWN_FILE = REPORT_DIR.resolve("STRUCTURE.md");
    private static final Path JSON_FILE = REPORT_DIR.resolve("structure.json");
    private static final Path BUDGET_FILE = Path.of("fixtures/structure/budget.json");

    private static RuntimeProfile profile = RuntimeProfile.empty();
    private static StructureReport.Counts counts;

    /**
     * Step 1 — run the real worker under a debugger and record what executes.
     *
     * <p>Enabled by default under {@code :worker:structureReport}; turn it off with
     * {@code -Pstructure.debug=false} when you only want the static half. When it IS enabled it
     * must succeed: a probe that silently produced nothing would turn section 1 of the report into
     * a blank page that looks like good news.
     */
    @Test
    @Order(1)
    void theHeadlessWorkerIsProfiledUnderADebugger() throws Exception {
        if (!Boolean.parseBoolean(System.getProperty("nodera.structure.debug", "false"))) {
            System.out.println("[structure] debugger probe disabled "
                    + "(-Dnodera.structure.debug=true to enable); static analysis only");
            return;
        }
        Path work = Files.createTempDirectory("nodera-structure-probe");
        Path log = REPORT_DIR.resolve("runtime-probe-worker.log");
        try {
            profile = HeadlessDebugProbe.run(work, log, HeadlessDebugProbe.DEFAULT_TRACED_PACKAGES);
        } catch (Exception failed) {
            String tail = tail(log, 40);
            throw new AssertionError("the debugger probe failed. Worker output:\n" + tail, failed);
        } finally {
            deleteRecursively(work);
        }

        HeadlessDebugProbe.write(PROFILE_FILE, profile);

        assertThat(profile.classesLoaded())
                .as("the probe must observe the worker loading its own classes")
                .isNotEmpty();
        assertThat(profile.methodEntries())
                .as("the probe must observe methods executing — an empty profile means the "
                        + "class filters no longer match any package that runs")
                .isNotEmpty();
        assertThat(profile.scenario())
                .as("every control verb in the scenario must have been driven")
                .hasSizeGreaterThanOrEqualTo(12);
    }

    /** Step 2 — read every module's bytecode and write the report. */
    @Test
    @Order(2)
    void theStructuralReportIsGenerated() throws Exception {
        Path root = repoRoot();
        List<CodeGraph.ScanRoot> roots = CodeGraph.outputRoots(root);
        CodeGraph graph = CodeGraph.scan(roots);

        List<String> missing = new ArrayList<>();
        for (String module : REQUIRED_MODULES) {
            if (!graph.productionModules().contains(module)) {
                missing.add(module);
            }
        }
        if (!missing.isEmpty()) {
            fail("no compiled output for " + missing + ". Every module must be compiled before the "
                    + "structural report runs, or code called only from a missing module is "
                    + "reported as dead. Run `./gradlew classes testClasses` (the "
                    + "`:worker:structureReport` task does this for you).");
        }

        DeadCodeAnalysis analysis = DeadCodeAnalysis.of(graph);
        List<Finding> findings = graph.findings();
        if (profile.isEmpty() && Files.exists(PROFILE_FILE)) {
            // A profile from an earlier run in this same build (or a previous one) still describes
            // this tree; using it is better than pretending the runtime half does not exist.
            profile = readProfile(PROFILE_FILE);
        }

        Files.createDirectories(REPORT_DIR);
        Files.writeString(MARKDOWN_FILE, StructureReport.render(graph, analysis, findings, profile));
        StructureReport.writeJson(JSON_FILE, graph, analysis, findings, profile);
        counts = StructureReport.count(analysis, findings);

        System.out.println("[structure] " + MARKDOWN_FILE.toAbsolutePath());
        counts.asMap().forEach((key, value) -> System.out.println("[structure]   " + key + " = " + value));

        assertThat(analysis.analysedMethods())
                .as("the analysis must actually reach the tree's methods")
                .isGreaterThan(1_000);
        assertThat(Files.readString(MARKDOWN_FILE)).contains("# Structural code report");
    }

    /**
     * Step 3 — the ratchet.
     *
     * <p>Each metric may drop freely and may never rise. That direction is the whole design: a
     * budget that can be raised to make a build green is a budget that will be, and the numbers it
     * guards are all "code that ships and does nothing".
     */
    @Test
    @Order(3)
    void deadCodeAndCostFindingsStayWithinBudget() throws Exception {
        assertThat(counts).as("the report step must run first").isNotNull();
        Map<String, Integer> budget = readBudget(repoRoot().resolve(BUDGET_FILE));
        Map<String, Integer> current = counts.asMap();

        List<String> regressions = new ArrayList<>();
        List<String> improvements = new ArrayList<>();
        current.forEach((metric, value) -> {
            Integer limit = budget.get(metric);
            if (limit == null) {
                regressions.add(metric + " is not in " + BUDGET_FILE + " (current " + value + ")");
            } else if (value > limit) {
                regressions.add(metric + ": " + value + " > budget " + limit);
            } else if (value < limit) {
                improvements.add(metric + ": " + value + " < budget " + limit);
            }
        });

        if (!improvements.isEmpty()) {
            System.out.println("[structure] the tree improved — tighten " + BUDGET_FILE + ":");
            improvements.forEach(line -> System.out.println("[structure]   " + line));
            System.out.println("[structure] suggested limits: " + current);
        }

        assertThat(regressions)
                .as("new dead code / cost findings, see " + MARKDOWN_FILE + ". Delete the code, or "
                        + "if it is genuinely reachable, teach the analysis the rule that proves it "
                        + "(DeadCodeAnalysis#analysable).")
                .isEmpty();
    }

    // ----------------------------------------------------------------------------------------

    private static Path repoRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.exists(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("cannot find the repository root from "
                    + Path.of("").toAbsolutePath());
        }
        return candidate;
    }

    /** Flat {@code "key": number} reader — the budget is a flat file and stays one. */
    private static Map<String, Integer> readBudget(Path file) throws IOException {
        if (!Files.exists(file)) {
            fail("no budget at " + file + ". Create it from the numbers this run printed.");
        }
        Map<String, Integer> budget = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("\"([a-z_]+)\"\\s*:\\s*(\\d+)")
                .matcher(Files.readString(file));
        while (matcher.find()) {
            budget.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
        }
        return budget;
    }

    private static RuntimeProfile readProfile(Path file) throws IOException {
        String json = Files.readString(file);
        Map<String, Long> entries = new LinkedHashMap<>();
        Matcher methods = Pattern.compile("\"([^\"]+#[^\"]+)\"\\s*:\\s*(\\d+)").matcher(json);
        while (methods.find()) {
            entries.put(methods.group(1), Long.parseLong(methods.group(2)));
        }
        List<String> classes = new ArrayList<>();
        Matcher loaded = Pattern.compile("\"classesLoaded\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL)
                .matcher(json);
        if (loaded.find()) {
            Matcher names = Pattern.compile("\"([^\"]+)\"").matcher(loaded.group(1));
            while (names.find()) {
                classes.add(names.group(1));
            }
        }
        if (entries.isEmpty() && classes.isEmpty()) {
            return RuntimeProfile.empty();
        }
        return new RuntimeProfile(entries, new java.util.LinkedHashSet<>(classes), List.of(),
                entries.values().stream().mapToLong(Long::longValue).sum(), 0L);
    }

    private static String tail(Path file, int lines) {
        try {
            List<String> all = Files.readAllLines(file);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException unreadable) {
            return "(no worker log at " + file + ")";
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: a temp directory the OS will reap anyway.
                }
            });
        }
    }
}
