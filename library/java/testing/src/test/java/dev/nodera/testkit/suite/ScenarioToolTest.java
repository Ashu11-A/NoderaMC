package dev.nodera.testkit.suite;

import dev.nodera.testkit.harness.PlayerRole;
import dev.nodera.testkit.harness.Topology;
import dev.nodera.testkit.report.RunReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The test tool, tested.
 *
 * <p>The tool decides what every live run means, so the parts of it that can be checked without
 * starting Minecraft are checked here: that a typo in a scenario name fails loudly instead of
 * running nothing, that the registry orders headless work before live work, that the port plan and
 * the roles agree with each other, and that a failing run renders a report that names the failure.
 *
 * <p>What is deliberately NOT here: anything that starts a process. A unit test that launched the
 * stack would be a live suite wearing a unit test's clothes — slow, machine-dependent, and outside
 * the lock every live run takes.
 *
 * <p>Thread-context: ordinary JUnit.
 */
class ScenarioToolTest {

    private static Scenario scenario(String id, Set<String> tags) {
        return new Scenario() {
            @Override public String id() {
                return id;
            }

            @Override public String title() {
                return "what " + id + " proves";
            }

            @Override public Set<String> tags() {
                return tags;
            }

            @Override public void run(ScenarioContext context) {
                // Never executed by these tests.
            }
        };
    }

    @Test
    void anUnknownScenarioNameFailsAndListsTheKnownOnes() {
        ScenarioRegistry registry = ScenarioRegistry.of(List.of(
                scenario("continuity", Set.of("live")), scenario("telemetry", Set.of("headless"))));

        assertThatThrownBy(() -> registry.require("continuty"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("continuty")
                .hasMessageContaining("continuity")
                .hasMessageContaining("telemetry");
    }

    @Test
    void headlessScenariosRunBeforeLiveOnes() {
        // A batch on a machine without a display should still prove the control and measurement
        // planes before spending twenty minutes on Minecraft — and if those are broken, every live
        // failure after them is a symptom of the same thing.
        ScenarioRegistry registry = ScenarioRegistry.of(List.of(
                scenario("crash", Set.of("live")),
                scenario("telemetry", Set.of("headless")),
                scenario("continuity", Set.of("live"))));

        assertThat(registry.all().stream().map(Scenario::id))
                .containsExactly("telemetry", "continuity", "crash");
    }

    @Test
    void theDefaultBatchLeavesOutHardwareScenarios() {
        ScenarioRegistry registry = ScenarioRegistry.of(List.of(
                scenario("android-mesh", Set.of("hardware")), scenario("crash", Set.of("live"))));

        assertThat(registry.defaultBatch()).extracting(Scenario::id).containsExactly("crash");
        assertThat(registry.withTag("hardware")).extracting(Scenario::id)
                .containsExactly("android-mesh");
        // defaultBatch is excludingTag("hardware") named for the decision it encodes; the general
        // form is what `nodera-test list --exclude-tag` and the e2e-live matrix are built on.
        assertThat(registry.excludingTag("hardware")).isEqualTo(registry.defaultBatch());
        assertThat(registry.excludingTag("live")).extracting(Scenario::id)
                .containsExactly("android-mesh");
    }

    @Test
    void twoScenariosCannotClaimTheSameId() {
        assertThatThrownBy(() -> ScenarioRegistry.of(List.of(
                scenario("crash", Set.of("live")), scenario("crash", Set.of("live")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("crash");
    }

    @Test
    void theStandardTopologyIsTheQuorumFloorAndItsPortsDoNotCollide() {
        Topology topology = Topology.standard();

        assertThat(topology.workers())
                .as("three peers is the floor: deriveHealth reports DEGRADED below three members")
                .isEqualTo(3);
        assertThat(topology.workerRole(0)).isEqualTo(PlayerRole.PLAYER_ONE);
        assertThat(topology.workerRole(1)).isEqualTo(PlayerRole.PLAYER_TWO);
        assertThat(topology.workerRole(2))
                .as("the third peer has no client — it is swarm ballast and a standby gateway")
                .isEqualTo(PlayerRole.SPARE);
        assertThat(topology.allPorts()).doesNotHaveDuplicates();
    }

    @Test
    void aRoleIsParsedFromEverySpellingTheCommandLineAccepts() {
        assertThat(PlayerRole.parse("player1")).isEqualTo(PlayerRole.PLAYER_ONE);
        assertThat(PlayerRole.parse("PLAYER_TWO")).isEqualTo(PlayerRole.PLAYER_TWO);
        assertThat(PlayerRole.parse("player-three")).isEqualTo(PlayerRole.PLAYER_THREE);
        assertThat(PlayerRole.parse("spare")).isEqualTo(PlayerRole.SPARE);
        // A typo must not start a healthy-looking worker answering as the wrong player.
        assertThatThrownBy(() -> PlayerRole.parse("player4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("player1");
    }

    @Test
    void timeoutsScaleTogetherRatherThanOneAtATime() {
        Topology slow = new Topology(2, 1, 1, 1, 25599, 25600, 25601, 25610, 25620, 25575,
                "nodera-dev", Duration.ofSeconds(300), 3);

        assertThat(slow.scaled(Duration.ofSeconds(60))).isEqualTo(Duration.ofSeconds(180));
    }

    @Test
    void aFailedRunRendersAReportThatNamesTheFailingStage() {
        StageResult passed = StageResult.passed("S1", "the host shares the world",
                Duration.ofSeconds(4));
        StageResult failed = StageResult.failed("S5", "player B recovers the world",
                "player B never started recovery", Duration.ofSeconds(120));
        ScenarioResult result = new ScenarioResult("continuity", "a world survives its host",
                List.of("live"), StageResult.Outcome.FAILED, "player B never started recovery",
                List.of(passed, failed), Instant.now(), Duration.ofSeconds(124),
                Path.of("run/results/continuity/x"));

        RunReport report = new RunReport(List.of(result), Path.of("nowhere.json"),
                Path.of("nowhere.json"));

        assertThat(report.anyFailed()).isTrue();
        assertThat(report.headline()).isEqualTo("0 passed, 1 failed, 0 skipped");
        assertThat(report.markdown())
                .contains("continuity")
                .contains("S5")
                .contains("player B never started recovery");
        assertThat(report.json())
                .contains("\"outcome\": \"FAILED\"")
                .contains("\"id\": \"continuity\"");
    }

    @Test
    void aSkippedRunSaysWhatTheMachineLackedRatherThanFailing() {
        ScenarioResult skipped = new ScenarioResult("endpoint", "an unmodified client joins",
                List.of("server"), StageResult.Outcome.SKIPPED, "no DISPLAY", List.of(),
                Instant.now(), Duration.ofSeconds(1), Path.of("run/results/endpoint/x"));

        RunReport report = new RunReport(List.of(skipped), Path.of("nowhere.json"),
                Path.of("nowhere.json"));

        assertThat(report.anyFailed())
                .as("a skip is what this machine could not host, never a verdict on the product")
                .isFalse();
        assertThat(report.markdown()).contains("Skipped").contains("no DISPLAY");
    }

    @Test
    void requirementsReportEveryUnmetConditionAtOnce(@TempDir Path emptyTree) {
        // One unmet condition per run would mean three runs to learn what the machine is missing.
        // The paths point at an empty tree so the jar is missing whatever this machine has built —
        // a test whose result depends on what happened to be compiled first is not a test.
        Requirements requirements = new Requirements(false, true, true, 0);

        assertThat(requirements.unmet(dev.nodera.testkit.harness.TestPaths.of(emptyTree)))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("endpoint plugin")
                        .contains("ANDROID_SERIAL"));
    }

    @Test
    void requirementsAreSilentWhenTheMachineHasWhatTheScenarioNeeds() {
        assertThat(Requirements.none().unmet(dev.nodera.testkit.harness.TestPaths.discover()))
                .isEmpty();
    }
}
