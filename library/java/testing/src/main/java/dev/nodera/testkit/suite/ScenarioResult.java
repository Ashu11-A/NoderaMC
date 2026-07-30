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
 */
public record ScenarioResult(String id, String title, List<String> tags,
                             StageResult.Outcome outcome, String message, List<StageResult> stages,
                             Instant startedAt, Duration duration, Path resultsDir) {

    public boolean failed() {
        return outcome == StageResult.Outcome.FAILED;
    }

    public boolean skipped() {
        return outcome == StageResult.Outcome.SKIPPED;
    }

    /** How many stages passed — reported even for a failure, because partial progress locates it. */
    public long passedStages() {
        return stages.stream().filter(s -> s.outcome() == StageResult.Outcome.PASSED).count();
    }
}
