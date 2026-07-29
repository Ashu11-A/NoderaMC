package dev.nodera.testkit.suite;

import java.time.Duration;

/**
 * What one stage of a scenario did.
 *
 * @param name        the short label ("S1", "S2c").
 * @param description what a pass proves.
 * @param outcome     pass, fail, or skip.
 * @param message     the cause, on anything but a pass.
 * @param duration    how long the stage took — a stage that suddenly takes four times as long is
 *                    the first sign of a regression the assertions still tolerate.
 */
public record StageResult(String name, String description, Outcome outcome, String message,
                          Duration duration) {

    /** What became of a stage or a whole scenario. */
    public enum Outcome {
        PASSED, FAILED, SKIPPED
    }

    public static StageResult passed(String name, String description, Duration duration) {
        return new StageResult(name, description, Outcome.PASSED, "", duration);
    }

    public static StageResult failed(String name, String description, String message,
                                     Duration duration) {
        return new StageResult(name, description, Outcome.FAILED,
                message == null ? "(no message)" : message, duration);
    }

    public static StageResult skipped(String name, String description, String reason,
                                      Duration duration) {
        return new StageResult(name, description, Outcome.SKIPPED, reason, duration);
    }
}
