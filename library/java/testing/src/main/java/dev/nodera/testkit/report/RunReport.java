package dev.nodera.testkit.report;

import dev.nodera.testkit.suite.ScenarioResult;
import dev.nodera.testkit.suite.StageResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One report for the whole testing ecosystem: acceptance scenarios, benchmarks, and structure.
 *
 * <h2>Why they are one document</h2>
 *
 * <p>These three lanes answer different questions about the same commit — does it work, how fast is
 * it, and how much of it is dead — and they used to be three separate artefacts in three formats
 * produced by three tools. Nobody read all three, so a change that passed every suite while
 * doubling a benchmark and adding two dead classes looked entirely clean. One document, produced by
 * one command, makes those three answers arrive together.
 *
 * <p>The benchmark and structure sections are summaries with links, not copies: their own reports
 * stay authoritative, and duplicating their tables here would guarantee the two drift.
 *
 * <p>Thread-context: built from finished results; pure rendering.
 */
public final class RunReport {

    private final List<ScenarioResult> scenarios;
    private final Path benchmarkResults;
    private final Path structureJson;
    private final Instant generatedAt;

    public RunReport(List<ScenarioResult> scenarios, Path benchmarkResults, Path structureJson) {
        this.scenarios = List.copyOf(scenarios);
        this.benchmarkResults = benchmarkResults;
        this.structureJson = structureJson;
        this.generatedAt = Instant.now();
    }

    /** {@code true} if any scenario failed. */
    public boolean anyFailed() {
        return scenarios.stream().anyMatch(ScenarioResult::failed);
    }

    /** {@code true} if any scenario did not run. */
    public boolean anySkipped() {
        return scenarios.stream().anyMatch(ScenarioResult::skipped);
    }

    /** How many scenarios did not run. */
    public long skippedCount() {
        return scenarios.stream().filter(ScenarioResult::skipped).count();
    }

    /**
     * {@code true} if any scenario was skipped for a reason the machine does not explain.
     *
     * <p>Separate from {@link #anySkipped} because the two have different verdicts: a structural
     * skip is never tolerable, and a circumstantial one is tolerable only when the operator says
     * so. Both used to be the same boolean and neither affected the exit status at all — a matrix
     * in which every leg skipped exited 0 and rendered green.
     */
    public boolean anyStructuralSkip() {
        return scenarios.stream().anyMatch(ScenarioResult::skippedStructurally);
    }

    /** The ids and reasons of every skipped scenario, one per line. */
    public String skipSummary() {
        return scenarios.stream().filter(ScenarioResult::skipped)
                .map(result -> "  " + result.id() + " ["
                        + (result.skipKind() == null ? "CIRCUMSTANTIAL" : result.skipKind())
                        + "] " + result.message())
                .reduce((a, b) -> a + "\n" + b).orElse("");
    }

    /** A one-line summary for a terminal or a CI step name. */
    public String headline() {
        long passed = scenarios.stream()
                .filter(s -> s.outcome() == StageResult.Outcome.PASSED).count();
        long failed = scenarios.stream().filter(ScenarioResult::failed).count();
        long skipped = scenarios.stream().filter(ScenarioResult::skipped).count();
        return passed + " passed, " + failed + " failed, " + skipped + " skipped";
    }

    /** The human-readable report. */
    public String markdown() {
        StringBuilder out = new StringBuilder();
        out.append("# Nodera test report\n\n");
        out.append("Generated ").append(generatedAt).append(" — **").append(headline())
                .append("**.\n\n");

        out.append("## 1. Acceptance scenarios\n\n");
        if (scenarios.isEmpty()) {
            out.append("_No scenarios ran._\n\n");
        } else {
            out.append("| scenario | outcome | stages | time | evidence |\n");
            out.append("|---|:--:|---:|---:|---|\n");
            for (ScenarioResult result : scenarios) {
                out.append("| `").append(result.id()).append("` — ").append(result.title())
                        .append(" | ").append(symbol(result.outcome()))
                        .append(" | ").append(result.passedStages()).append('/')
                        .append(result.stages().size())
                        .append(" | ").append(seconds(result.duration()))
                        .append(" | `").append(result.resultsDir()).append("` |\n");
            }
            out.append('\n');

            List<ScenarioResult> failures = scenarios.stream().filter(ScenarioResult::failed).toList();
            if (!failures.isEmpty()) {
                out.append("### Failures\n\n");
                for (ScenarioResult failure : failures) {
                    out.append("**`").append(failure.id()).append("`** — ").append(failure.message())
                            .append("\n\n");
                    appendStages(out, failure);
                }
            }
            List<ScenarioResult> skips = scenarios.stream().filter(ScenarioResult::skipped).toList();
            if (!skips.isEmpty()) {
                out.append("### Skipped\n\n");
                out.append("A skip is what this machine could not host, never a verdict on the "
                        + "product.\n\n");
                for (ScenarioResult skip : skips) {
                    out.append("- `").append(skip.id()).append("` — ").append(skip.message())
                            .append('\n');
                }
                out.append('\n');
            }

            out.append("### Stage detail\n\n");
            for (ScenarioResult result : scenarios) {
                if (result.stages().isEmpty()) {
                    continue;
                }
                out.append("<details><summary><code>").append(result.id())
                        .append("</code> — ").append(result.stages().size())
                        .append(" stages</summary>\n\n");
                appendStages(out, result);
                out.append("</details>\n\n");
            }
        }

        out.append("## 2. Benchmarks\n\n");
        out.append(benchmarkSection());

        out.append("## 3. Structure\n\n");
        out.append(structureSection());

        return out.toString();
    }

    /** The machine-readable half — what CI and the next run diff against. */
    public String json() {
        StringBuilder out = new StringBuilder("{\n");
        out.append("  \"generatedAt\": \"").append(generatedAt).append("\",\n");
        out.append("  \"headline\": \"").append(headline()).append("\",\n");
        out.append("  \"skipped\": ").append(skippedCount()).append(",\n");
        out.append("  \"structuralSkips\": ").append(anyStructuralSkip()).append(",\n");
        out.append("  \"scenarios\": [");
        for (int i = 0; i < scenarios.size(); i++) {
            ScenarioResult result = scenarios.get(i);
            out.append(i == 0 ? "\n" : ",\n");
            out.append("    {\n");
            out.append("      \"id\": \"").append(result.id()).append("\",\n");
            out.append("      \"title\": ").append(quote(result.title())).append(",\n");
            out.append("      \"outcome\": \"").append(result.outcome()).append("\",\n");
            out.append("      \"skipKind\": ").append(result.skipKind() == null ? "null"
                    : quote(result.skipKind().toString())).append(",\n");
            out.append("      \"message\": ").append(quote(result.message())).append(",\n");
            out.append("      \"durationSeconds\": ").append(result.duration().toSeconds()).append(",\n");
            out.append("      \"resultsDir\": ").append(quote(result.resultsDir().toString())).append(",\n");
            out.append("      \"stages\": [");
            List<StageResult> stages = result.stages();
            for (int s = 0; s < stages.size(); s++) {
                StageResult stage = stages.get(s);
                out.append(s == 0 ? "\n" : ",\n");
                out.append("        {\"name\": ").append(quote(stage.name()))
                        .append(", \"description\": ").append(quote(stage.description()))
                        .append(", \"outcome\": \"").append(stage.outcome())
                        .append("\", \"message\": ").append(quote(stage.message()))
                        .append(", \"durationSeconds\": ").append(stage.duration().toSeconds())
                        .append('}');
            }
            out.append(stages.isEmpty() ? "]" : "\n      ]").append('\n');
            out.append("    }");
        }
        out.append(scenarios.isEmpty() ? "]" : "\n  ]").append(",\n");
        out.append("  \"benchmarks\": ").append(quote(benchmarkHeadline())).append(",\n");
        out.append("  \"structure\": ").append(quote(structureHeadline())).append('\n');
        out.append("}\n");
        return out.toString();
    }

    /** Write both forms, and return the Markdown path. */
    public Path writeTo(Path directory) {
        try {
            Files.createDirectories(directory);
            Path markdown = directory.resolve("TEST-REPORT.md");
            Files.writeString(markdown, markdown());
            Files.writeString(directory.resolve("test-report.json"), json());
            return markdown;
        } catch (IOException e) {
            throw new IllegalStateException("cannot write the report into " + directory, e);
        }
    }

    // ------------------------------------------------------------------------------------------

    private void appendStages(StringBuilder out, ScenarioResult result) {
        out.append("| stage | what it proves | outcome | time |\n");
        out.append("|---|---|:--:|---:|\n");
        for (StageResult stage : result.stages()) {
            out.append("| `").append(stage.name()).append("` | ").append(stage.description())
                    .append(" | ").append(symbol(stage.outcome()))
                    .append(" | ").append(seconds(stage.duration())).append(" |\n");
            if (stage.outcome() != StageResult.Outcome.PASSED && !stage.message().isBlank()) {
                out.append("| | ").append(stage.message()).append(" | | |\n");
            }
        }
        out.append('\n');
    }

    private String benchmarkSection() {
        Optional<Map<String, Double>> scores = readBenchmarkScores();
        if (scores.isEmpty()) {
            return "_No JMH results at `" + benchmarkResults + "`. Run `./gradlew :peer:jmh "
                    + "-Pbench.quick`, or `nodera-test bench`, which does it for you._\n\n";
        }
        Map<String, Double> byName = scores.get();
        StringBuilder out = new StringBuilder();
        out.append(byName.size()).append(" measurements. Full report with load-scaling and the "
                + "baseline diff: `build/reports/nodera/BENCHMARKS.md`.\n\n");
        out.append("| slowest measurement | µs/op |\n|---|---:|\n");
        byName.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(5)
                .forEach(entry -> out.append("| `").append(entry.getKey()).append("` | ")
                        .append(String.format("%.3f", entry.getValue())).append(" |\n"));
        out.append('\n');
        return out.toString();
    }

    private String benchmarkHeadline() {
        return readBenchmarkScores()
                .map(scores -> scores.size() + " measurements")
                .orElse("not run");
    }

    private String structureSection() {
        Map<String, Long> counts = readStructureCounts();
        if (counts.isEmpty()) {
            return "_No structural report at `" + structureJson + "`. Run "
                    + "`./gradlew :worker:structureReport`, or `nodera-test structure`._\n\n";
        }
        StringBuilder out = new StringBuilder();
        out.append("From `build/reports/nodera/STRUCTURE.md` — code that ships and does nothing, "
                + "ratcheted by `fixtures/structure/budget.json`.\n\n");
        out.append("| metric | value |\n|---|---:|\n");
        counts.forEach((key, value) -> out.append("| `").append(key).append("` | ").append(value)
                .append(" |\n"));
        out.append('\n');
        return out.toString();
    }

    private String structureHeadline() {
        Map<String, Long> counts = readStructureCounts();
        if (counts.isEmpty()) {
            return "not run";
        }
        return counts.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    /**
     * Read the JMH result scores.
     *
     * <p>Deliberately a narrow regex rather than a JSON parser: this module has no JSON dependency,
     * the two fields needed are unambiguous in JMH's output, and a report that fails to render
     * because a benchmark file grew a field would be worse than one that shows fewer rows.
     */
    private Optional<Map<String, Double>> readBenchmarkScores() {
        if (benchmarkResults == null || !Files.isRegularFile(benchmarkResults)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(benchmarkResults);
            Matcher matcher = Pattern.compile(
                    "\"benchmark\"\\s*:\\s*\"([^\"]+)\".*?\"score\"\\s*:\\s*([0-9.eE+-]+)",
                    Pattern.DOTALL).matcher(json);
            Map<String, Double> scores = new LinkedHashMap<>();
            while (matcher.find()) {
                String name = matcher.group(1);
                scores.merge(name.substring(name.lastIndexOf('.', name.lastIndexOf('.') - 1) + 1),
                        Double.parseDouble(matcher.group(2)), Math::max);
            }
            return scores.isEmpty() ? Optional.empty() : Optional.of(scores);
        } catch (IOException | NumberFormatException unreadable) {
            return Optional.empty();
        }
    }

    private Map<String, Long> readStructureCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (structureJson == null || !Files.isRegularFile(structureJson)) {
            return counts;
        }
        try {
            String json = Files.readString(structureJson);
            int start = json.indexOf("\"counts\"");
            if (start < 0) {
                return counts;
            }
            int end = json.indexOf('}', start);
            Matcher matcher = Pattern.compile("\"([a-z_]+)\"\\s*:\\s*(\\d+)")
                    .matcher(json.substring(start, end < 0 ? json.length() : end));
            while (matcher.find()) {
                counts.put(matcher.group(1), Long.parseLong(matcher.group(2)));
            }
        } catch (IOException unreadable) {
            return counts;
        }
        return counts;
    }

    private static String symbol(StageResult.Outcome outcome) {
        return switch (outcome) {
            case PASSED -> "PASS";
            case FAILED -> "**FAIL**";
            case SKIPPED -> "skip";
        };
    }

    private static String seconds(Duration duration) {
        return duration.toSeconds() + "s";
    }

    private static String quote(String value) {
        String safe = value == null ? "" : value;
        StringBuilder out = new StringBuilder("\"");
        for (char c : safe.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Every scenario result in this report. */
    public List<ScenarioResult> scenarios() {
        return new ArrayList<>(scenarios);
    }
}
