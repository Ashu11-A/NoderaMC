package dev.nodera.testkit.cli;

import dev.nodera.testkit.harness.TestPaths;
import dev.nodera.testkit.report.RunReport;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.ScenarioResult;
import dev.nodera.testkit.suite.SkipKind;
import dev.nodera.testkit.suite.StageResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run in which nothing ran must not exit zero.
 *
 * <h2>The hole this closes</h2>
 *
 * <p>The tool's exit status was {@code report.anyFailed() ? 1 : 0}, and {@code SKIPPED} is not
 * {@code failed}. So a live matrix in which every leg skipped — no display, a wrong artefact path,
 * a machine below the memory floor — exited 0 and rendered as a passing build. The unit gate had
 * carried the opposite rule for months ({@code .github/workflows/build.yml}, "Nothing skipped into
 * green"); the live lane, which produces the expensive evidence seventy other issues cite, had no
 * equivalent. The rule belongs in the tool, because a workflow can forget it and a second workflow
 * never had it.
 *
 * <h2>The two kinds are not the same verdict</h2>
 *
 * <p>The category charter's rule: a skip must be circumstantial (this machine lacks a display) and
 * never structural (nothing builds the jar the scenario looks for). {@code --allow-skips} is the
 * operator's acceptance of the first kind on a particular box. Nothing accepts the second, because
 * there is no property of the machine for an operator to be accepting.
 *
 * <p>Thread-context: ordinary JUnit; pure verdict logic over synthetic results.
 */
class SkipGateTest {

    @Test
    void aFullySkippedRunIsRed() {
        assertThat(NoderaTestMain.verdict(report(skipped(SkipKind.CIRCUMSTANTIAL)), false))
                .as("every leg skipped, nothing was asserted — this used to exit 0")
                .isEqualTo(1);
    }

    @Test
    void anOperatorMayAcceptACircumstantialSkip() {
        assertThat(NoderaTestMain.verdict(report(skipped(SkipKind.CIRCUMSTANTIAL)), true))
                .isZero();
    }

    @Test
    void aStructuralSkipIsRedEvenWithAllowSkips() {
        assertThat(NoderaTestMain.verdict(report(skipped(SkipKind.STRUCTURAL)), true))
                .as("nothing about the machine explains it, so there is nothing to accept")
                .isEqualTo(1);
    }

    @Test
    void aCleanRunIsStillGreen() {
        assertThat(NoderaTestMain.verdict(report(passed()), false)).isZero();
        assertThat(NoderaTestMain.verdict(report(passed()), true)).isZero();
    }

    @Test
    void aFailureIsRedWhateverTheSkipFlagSays() {
        assertThat(NoderaTestMain.verdict(report(failed()), true)).isEqualTo(1);
        assertThat(NoderaTestMain.verdict(report(failed()), false)).isEqualTo(1);
    }

    @Test
    void theSkipSummaryNamesTheScenarioAndItsKind() {
        assertThat(report(skipped(SkipKind.STRUCTURAL)).skipSummary())
                .contains("endpoint")
                .contains("STRUCTURAL")
                .contains("no endpoint plugin");
    }

    /**
     * A missing artefact the RUNNER builds is structural; a missing display is not.
     *
     * <p>The two used to be one string, which is how a suite that could not run anywhere read
     * exactly like one this box merely could not host.
     */
    @Test
    void requirementsClassifyWhatTheyCouldNotFind(@TempDir Path emptyTree) {
        assertThat(Requirements.paperEndpoint().unmet(TestPaths.of(emptyTree)))
                .hasValueSatisfying(skip -> assertThat(skip.kind()).isEqualTo(SkipKind.STRUCTURAL));
        assertThat(Requirements.device().unmet(TestPaths.of(emptyTree)))
                .hasValueSatisfying(skip ->
                        assertThat(skip.kind()).isEqualTo(SkipKind.CIRCUMSTANTIAL));
        assertThat(Requirements.none().unmet(TestPaths.of(emptyTree))).isEmpty();
    }

    // ---------------------------------------------------------------------------------------

    private static RunReport report(ScenarioResult result) {
        return new RunReport(List.of(result), null, null);
    }

    private static ScenarioResult skipped(SkipKind kind) {
        return new ScenarioResult("endpoint", "an unmodified client joins", List.of("server"),
                StageResult.Outcome.SKIPPED, "no endpoint plugin at build/libs/nodera.jar",
                List.of(), Instant.now(), Duration.ofSeconds(1), Path.of("run/results/endpoint"),
                kind);
    }

    private static ScenarioResult passed() {
        return new ScenarioResult("continuity", "a world survives its host", List.of("live"),
                StageResult.Outcome.PASSED, "", List.of(), Instant.now(), Duration.ofSeconds(1),
                Path.of("run/results/continuity"));
    }

    private static ScenarioResult failed() {
        return new ScenarioResult("continuity", "a world survives its host", List.of("live"),
                StageResult.Outcome.FAILED, "S5 never happened", List.of(), Instant.now(),
                Duration.ofSeconds(1), Path.of("run/results/continuity"));
    }
}
