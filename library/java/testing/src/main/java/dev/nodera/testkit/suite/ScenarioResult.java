package dev.nodera.testkit.suite;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * What one scenario did, start to finish — the unit the report is built from.
 *
 * @param id          the scenario's identifier.
 * @param title       what a pass proves.
 * @param tags        its tags, so a report can group live and headless runs.
 * @param outcome     the scenario's own verdict.
 * @param message     the failure or skip reason.
 * @param stages      every stage, in order.
 * @param startedAt   when the run began.
 * @param duration    total wall time.
 * @param resultsDir  where the logs and state snapshots of this run were collected.
 * @param skipKind    why it did not run, when it did not; {@code null} otherwise.
 */
public record ScenarioResult(String id, String title, List<String> tags,
                             StageResult.Outcome outcome, String message, List<StageResult> stages,
                             Instant startedAt, Duration duration, Path resultsDir,
                             SkipKind skipKind) {

    /** A result that ran (or failed); nothing was skipped. */
    public ScenarioResult(String id, String title, List<String> tags, StageResult.Outcome outcome,
                          String message, List<StageResult> stages, Instant startedAt,
                          Duration duration, Path resultsDir) {
        this(id, title, tags, outcome, message, stages, startedAt, duration, resultsDir, null);
    }

    public boolean failed() {
        return outcome == StageResult.Outcome.FAILED;
    }

    public boolean skipped() {
        return outcome == StageResult.Outcome.SKIPPED;
    }

    /**
     * A skip nothing about this machine explains — banned outright.
     *
     * <p>This is the one outcome the tool refuses to pass even with {@code --allow-skips}: the
     * scenario could not have run anywhere, so the run measured nothing and said so in a colour that
     * reads as success.
     */
    public boolean skippedStructurally() {
        return skipped() && skipKind == SkipKind.STRUCTURAL;
    }

    /** How many stages passed — reported even for a failure, because partial progress locates it. */
    public long passedStages() {
        return stages.stream().filter(s -> s.outcome() == StageResult.Outcome.PASSED).count();
    }
}
