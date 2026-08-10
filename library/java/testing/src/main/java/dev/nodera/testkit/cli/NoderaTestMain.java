package dev.nodera.testkit.cli;

import dev.nodera.testkit.harness.HarnessException;
import dev.nodera.testkit.harness.ManagedProcess;
import dev.nodera.testkit.harness.TestPaths;
import dev.nodera.testkit.report.RunReport;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioRegistry;
import dev.nodera.testkit.suite.ScenarioResult;
import dev.nodera.testkit.suite.ScenarioRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code nodera-test} — the one command that runs the acceptance scenarios, the benchmarks, and the
 * structural report.
 *
 * <h2>Why one tool</h2>
 *
 * <p>There used to be twenty shell suites, a batch runner, a benchmark Gradle task, a report
 * script, and a structural report task: five ways to ask "is this commit any good", each with its
 * own arguments, output format and idea of what a failure looks like. Everything here is a
 * subcommand of one binary, every lane writes into one report, and the exit status means the same
 * thing in all of them.
 *
 * <pre>
 *   nodera-test list                       # every scenario, its tags and what it proves
 *   nodera-test list --ids --exclude-tag hardware   # the same set, machine-readable
 *   nodera-test run                        # the default queue (everything but hardware)
 *   nodera-test run continuity crash       # a selection, in the order given
 *   nodera-test run --tag server           # everything carrying a tag
 *   nodera-test bench [--full]             # the JMH lanes + the ranked benchmark report
 *   nodera-test structure [--no-debug]     # dead code + cost findings + the debugger probe
 *   nodera-test all                        # scenarios, then benchmarks, then structure
 *
 *   --no-build      use what is already built (fast, and wrong if you just changed code)
 *   --keep-running  leave the stack up after the last scenario, to poke at it by hand
 *   --report DIR    where the report goes (default build/reports/nodera)
 * </pre>
 *
 * <p>Thread-context: a process entry point.
 */
public final class NoderaTestMain {

    private NoderaTestMain() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (HarnessException failure) {
            System.err.println("nodera-test: " + failure.getMessage());
            System.exit(2);
        } catch (IllegalArgumentException badUsage) {
            System.err.println("nodera-test: " + badUsage.getMessage());
            System.exit(64);
        }
    }

    static int run(String[] args) {
        TestPaths paths = TestPaths.discover();
        ScenarioRegistry registry = ScenarioRegistry.discover();

        List<String> rest = new ArrayList<>(List.of(args));
        String command = rest.isEmpty() ? "help" : rest.remove(0);

        boolean build = !rest.remove("--no-build");
        boolean keepRunning = rest.remove("--keep-running");
        boolean full = rest.remove("--full");
        boolean noDebug = rest.remove("--no-debug");
        Path reportDir = takeOption(rest, "--report")
                .map(Path::of)
                .orElse(paths.root().resolve("build/reports/nodera"));
        List<String> tags = new ArrayList<>();
        takeOption(rest, "--tag").ifPresent(tags::add);
        boolean idsOnly = rest.remove("--ids");
        String excludeTag = takeOption(rest, "--exclude-tag").orElse(null);

        return switch (command) {
            case "list" -> list(registry, idsOnly, excludeTag);
            case "run" -> runScenarios(paths, registry, rest, tags, build, keepRunning, reportDir);
            case "bench" -> bench(paths, full);
            case "structure" -> structure(paths, noDebug);
            case "all" -> {
                int scenarios = runScenarios(paths, registry, rest, tags, build, keepRunning, reportDir);
                int benchmarks = bench(paths, full);
                int structure = structure(paths, noDebug);
                yield Math.max(scenarios, Math.max(benchmarks, structure));
            }
            case "help", "--help", "-h" -> help(registry);
            default -> throw new IllegalArgumentException("unknown command '" + command
                    + "'. Try: list, run, bench, structure, all");
        };
    }

    private static int list(ScenarioRegistry registry, boolean idsOnly, String excludeTag) {
        listing(registry, idsOnly, excludeTag).forEach(System.out::println);
        return 0;
    }

    /**
     * What {@code list} prints, as lines, so it can be asserted without capturing stdout.
     *
     * <p>{@code --ids} drops the table and prints one bare id per line, and {@code --exclude-tag}
     * filters. Together they exist for one caller: the {@code e2e-live} workflow, which builds its
     * job matrix from
     * {@code nodera-test list --ids --exclude-tag hardware}. That workflow used to keep its own
     * array of ids beside a check that validated the array against this tool — so a scenario added
     * here was "known" to the check and still never dispatched, and the array had drifted six
     * scenarios behind the registry. A machine-readable form of the list the tool already owns
     * deletes the second table rather than re-synchronising it.
     *
     * <p>The human table is unchanged: it is what a person reading {@code list} wants, and no
     * parser should have been reading it. The old workflow parsed it with
     * {@code awk 'NR > 1 && NF {print $1}'}, which returned {@code Tags} and {@code The} from the
     * footer alongside the scenario ids — and {@code SCENARIO} too whenever a build note occupied
     * line 1, which on a cold CI runner it always did.
     */
    static List<String> listing(ScenarioRegistry registry, boolean idsOnly, String excludeTag) {
        List<Scenario> scenarios = excludeTag == null
                ? registry.all()
                : registry.excludingTag(excludeTag);
        if (idsOnly) {
            return scenarios.stream().map(Scenario::id).toList();
        }
        List<String> lines = new ArrayList<>();
        lines.add(String.format("%-18s %-34s %s", "SCENARIO", "TAGS", "WHAT A PASS PROVES"));
        for (Scenario scenario : scenarios) {
            lines.add(String.format("%-18s %-34s %s", scenario.id(),
                    String.join(",", new java.util.TreeSet<>(scenario.tags())), scenario.title()));
        }
        lines.add("");
        lines.add("Tags in use: " + String.join(", ", registry.tags()));
        lines.add("The default queue is everything not tagged 'hardware'.");
        return lines;
    }

    private static int runScenarios(TestPaths paths, ScenarioRegistry registry, List<String> ids,
                                    List<String> tags, boolean build, boolean keepRunning,
                                    Path reportDir) {
        List<Scenario> queue;
        if (!tags.isEmpty()) {
            queue = registry.withTag(tags.get(0));
            if (queue.isEmpty()) {
                throw new IllegalArgumentException("no scenario carries the tag '" + tags.get(0)
                        + "'. Known tags: " + String.join(", ", registry.tags()));
            }
        } else if (ids.isEmpty()) {
            queue = registry.defaultBatch();
        } else {
            queue = registry.require(ids);
        }

        System.out.println("nodera-test: running " + queue.size() + " scenario(s): "
                + queue.stream().map(Scenario::id).reduce((a, b) -> a + ", " + b).orElse(""));

        List<ScenarioResult> results = new ScenarioRunner(paths, build, keepRunning).runAll(queue);
        RunReport report = new RunReport(results,
                paths.root().resolve("java/peer/build/results/jmh/results.json"),
                paths.root().resolve("build/reports/nodera/structure.json"));
        Path written = report.writeTo(reportDir);

        System.out.println();
        for (ScenarioResult result : results) {
            System.out.printf("%-8s %-18s %s%n", result.outcome(), result.id(),
                    result.failed() || result.skipped() ? result.message() : result.title());
        }
        System.out.println();
        System.out.println("nodera-test: " + report.headline() + " — " + written);
        return report.anyFailed() ? 1 : 0;
    }

    /**
     * The benchmark lane.
     *
     * <p>Shelling out to Gradle rather than embedding JMH is deliberate: the benchmarks must run in
     * their own forked JVMs with their own JIT state, which is exactly what the Gradle task already
     * arranges. Re-implementing that here would produce numbers that are not comparable with the
     * committed baseline.
     */
    private static int bench(TestPaths paths, boolean full) {
        List<String> command = new ArrayList<>(List.of(
                paths.root().resolve("gradlew").toString(), ":peer:jmh", "--console=plain"));
        if (!full) {
            command.add("-Pbench.quick");
        }
        int exit = gradle(paths, "bench", command, Duration.ofHours(2));
        if (exit != 0) {
            return exit;
        }
        return exec(paths, "bench-report", List.of("python3",
                paths.root().resolve("scripts/bench-report.py").toString()), Duration.ofMinutes(5));
    }

    /** The structural report lane, debugger probe included unless {@code --no-debug}. */
    private static int structure(TestPaths paths, boolean noDebug) {
        List<String> command = new ArrayList<>(List.of(
                paths.root().resolve("gradlew").toString(), ":worker:structureReport",
                "--console=plain"));
        if (noDebug) {
            command.add("-Pstructure.debug=false");
        }
        return gradle(paths, "structure", command, Duration.ofHours(1));
    }

    private static int gradle(TestPaths paths, String what, List<String> command, Duration timeout) {
        return exec(paths, what, command, timeout);
    }

    private static int exec(TestPaths paths, String what, List<String> command, Duration timeout) {
        System.out.println("nodera-test: " + String.join(" ", command));
        Path log = paths.runDir().resolve("nodera-test-" + what + ".log");
        ManagedProcess process = ManagedProcess.start(what, paths.root(), log,
                ManagedProcess.env(), command);
        int exit = process.awaitExit(timeout);
        System.out.println("nodera-test: " + what + " exited " + exit + " (log: " + log + ")");
        return exit;
    }

    private static int help(ScenarioRegistry registry) {
        System.out.println("""
                nodera-test — the acceptance, benchmark and structure tool.

                  nodera-test list                    every scenario, its tags, what it proves
                  nodera-test run [ids…]              the default queue, or the ids given
                  nodera-test run --tag <tag>         everything carrying a tag
                  nodera-test bench [--full]          JMH lanes + the ranked benchmark report
                  nodera-test structure [--no-debug]  dead code, cost findings, debugger probe
                  nodera-test all                     scenarios, then benchmarks, then structure

                  --no-build       use what is already built
                  --keep-running   leave the stack up after the run
                  --report DIR     where the report goes (default build/reports/nodera)
                  --ids            list: bare ids, one per line, for scripts
                  --exclude-tag T  list: leave out everything carrying the tag
                """);
        return list(registry, false, null);
    }

    private static java.util.Optional<String> takeOption(List<String> args, String name) {
        int index = args.indexOf(name);
        if (index < 0) {
            return java.util.Optional.empty();
        }
        if (index + 1 >= args.size()) {
            throw new IllegalArgumentException(name + " needs a value");
        }
        args.remove(index);
        return java.util.Optional.of(args.remove(index));
    }
}
