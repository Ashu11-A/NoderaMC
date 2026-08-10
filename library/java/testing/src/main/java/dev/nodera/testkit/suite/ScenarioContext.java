package dev.nodera.testkit.suite;

import dev.nodera.testkit.harness.ControlClient;
import dev.nodera.testkit.harness.HarnessException;
import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.harness.PlayerRole;
import dev.nodera.testkit.harness.Topology;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What a scenario is handed: the live stack, and the stage recorder every assertion goes through.
 *
 * <h2>Stages are the report</h2>
 *
 * <p>The shell suites printed {@code S1: pass} lines and a reader reconstructed the run from
 * stdout. Here a stage is an object: it has a name, a description, a duration, an outcome, and — on
 * failure — the message and the log file to read. That is what makes a machine-readable report
 * possible at all, and it is why a scenario's body is a list of {@link #stage} calls rather than a
 * sequence of statements with prints between them.
 *
 * <p>A stage that throws stops the scenario: live stages are ordered, and a run whose third stage
 * failed cannot say anything trustworthy about its fourth.
 *
 * <p>Thread-context: single-threaded, owned by the runner.
 */
public final class ScenarioContext {

    /** A stage body that may fail. */
    @FunctionalInterface
    public interface StageBody {
        void run() throws Exception;
    }

    private final LiveStack stack;
    private final List<StageResult> stages = new ArrayList<>();
    private final System.Logger logger;

    public ScenarioContext(LiveStack stack, String scenarioId) {
        this.stack = stack;
        this.logger = System.getLogger("nodera.scenario." + scenarioId);
    }

    /** The live stack: services, workers, clients, servers. */
    public LiveStack stack() {
        return stack;
    }

    /** The topology this run was built with. */
    public Topology topology() {
        return stack.topology();
    }

    /** A watcher over one of this run's service logs. */
    public LogWatcher log(String fileName) {
        return stack.log(fileName);
    }

    /** A watcher over any file. */
    public LogWatcher watch(Path file) {
        return stack.watch(file);
    }

    /** The worker that plays {@code role}. */
    public ControlClient worker(PlayerRole role) {
        return stack.worker(role);
    }

    /** Stages recorded so far, in order. */
    public List<StageResult> stages() {
        return List.copyOf(stages);
    }

    /**
     * Run one stage of the scenario, recording what happened.
     *
     * @param name        the short label ("S1", "S2c") the run reads by.
     * @param description what a pass proves, in one line.
     * @param body        the work and its assertions.
     * @throws Exception whatever the body threw, after the failure has been recorded.
     */
    public void stage(String name, String description, StageBody body) throws Exception {
        logger.log(System.Logger.Level.INFO, () -> name + ": " + description);
        Instant started = Instant.now();
        try {
            body.run();
            stages.add(StageResult.passed(name, description, Duration.between(started, Instant.now())));
            logger.log(System.Logger.Level.INFO, () -> "  PASS " + name);
        } catch (SkipSignal skip) {
            stages.add(StageResult.skipped(name, description, skip.getMessage(),
                    Duration.between(started, Instant.now())));
            throw skip;
        } catch (Exception failure) {
            stages.add(StageResult.failed(name, description, failure.getMessage(),
                    Duration.between(started, Instant.now())));
            logger.log(System.Logger.Level.ERROR, () -> "  FAIL " + name + ": " + failure.getMessage());
            throw failure;
        }
    }

    /** Fail the current stage with a stated cause. */
    public void fail(String message) {
        throw new HarnessException(message);
    }

    /** Fail unless the condition holds. */
    public void check(boolean condition, String message) {
        if (!condition) {
            throw new HarnessException(message);
        }
    }

    /** Fail unless {@code haystack} contains {@code needle}. */
    public void checkContains(String haystack, String needle, String message) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new HarnessException(message + " — expected to find '" + needle + "' in: "
                    + abbreviate(haystack));
        }
    }

    /** Fail if {@code haystack} contains {@code needle}. */
    public void checkAbsent(String haystack, String needle, String message) {
        if (haystack != null && haystack.contains(needle)) {
            throw new HarnessException(message + " — found '" + needle + "' in: "
                    + abbreviate(haystack));
        }
    }

    /**
     * Skip the scenario from inside a stage: this machine or this build cannot host it.
     *
     * <p>{@link SkipKind#CIRCUMSTANTIAL} by construction. A stage that discovers a compiled-out
     * feature or an absent device is describing the environment it was handed, which is a reason a
     * reader can act on. Use {@link #skipStructurally} for the other kind — the one where nothing
     * about the environment explains it and the scenario could not have run anywhere.
     */
    public void skip(String reason) {
        throw new SkipSignal(SkipKind.CIRCUMSTANTIAL, reason);
    }

    /**
     * Skip because the harness itself did not give this scenario a chance.
     *
     * <p>Never tolerated by the runner, with or without {@code --allow-skips}: a structural skip is
     * a scenario that never runs, and it renders exactly like one that passed.
     */
    public void skipStructurally(String reason) {
        throw new SkipSignal(SkipKind.STRUCTURAL, reason);
    }

    /** A message in the run log, for context a stage name cannot carry. */
    public void note(String message) {
        logger.log(System.Logger.Level.INFO, message);
    }

    /** Sleep, scaled by the topology's timeout multiplier. */
    public void settle(Duration duration) {
        Topology.sleep(topology().scaled(duration));
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "<null>";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "…";
    }
}
