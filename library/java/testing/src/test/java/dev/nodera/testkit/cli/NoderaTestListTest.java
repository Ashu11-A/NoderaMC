package dev.nodera.testkit.cli;

import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;
import dev.nodera.testkit.suite.ScenarioRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The machine-readable form of {@code nodera-test list}.
 *
 * <p>These flags exist for a caller that is not a person: the {@code e2e-live} workflow builds its
 * job matrix from {@code list --ids --exclude-tag hardware}. It used to carry its own array of
 * thirteen ids instead, checked against the tool — which proved every id in the array existed and
 * proved nothing about the six scenarios the array had never heard of. So the assertion that
 * matters here is the last one: the set this command yields on the REAL registry is the set the
 * nightly dispatches, and it is complete by construction rather than by somebody remembering.
 *
 * <p>Thread-context: ordinary JUnit. Nothing here starts a process.
 */
class NoderaTestListTest {

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

    private static final ScenarioRegistry SAMPLE = ScenarioRegistry.of(List.of(
            scenario("android-mesh", Set.of("hardware", "android", "live")),
            scenario("crash", Set.of("live")),
            scenario("telemetry", Set.of("headless"))));

    @Test
    void idsOnlyPrintsBareIdsWithNoHeaderAndNoFooter() {
        // The table has a header row, a blank line and two trailing sentences. A matrix built from
        // it would dispatch jobs called SCENARIO, Tags and The — which is what the workflow's
        // `awk 'NR > 1 { print $1 }'` was actually collecting.
        assertThat(NoderaTestMain.listing(SAMPLE, true, null))
                .containsExactly("telemetry", "android-mesh", "crash");
    }

    @Test
    void excludeTagDropsEveryScenarioCarryingIt() {
        assertThat(NoderaTestMain.listing(SAMPLE, true, "hardware"))
                .containsExactly("telemetry", "crash");
    }

    @Test
    void theHumanTableIsUnchangedByTheNewFlags() {
        List<String> table = NoderaTestMain.listing(SAMPLE, false, null);

        assertThat(table.get(0)).contains("SCENARIO").contains("TAGS").contains("WHAT A PASS PROVES");
        assertThat(table).anySatisfy(line -> assertThat(line)
                .contains("crash").contains("live").contains("what crash proves"));
        assertThat(table).last().asString().contains("not tagged 'hardware'");
    }

    @Test
    void theFlagsAreParsedOffTheCommandLineAndNotOnlyHonouredInternally() {
        String printed = capturingStdout(() ->
                assertThat(NoderaTestMain.run(new String[] {"list", "--ids", "--exclude-tag", "hardware"}))
                        .isZero());

        List<String> ids = printed.lines().toList();
        assertThat(ids).isNotEmpty().doesNotContain("SCENARIO", "");
        assertThat(ids).allSatisfy(id -> assertThat(id).matches("[a-z0-9-]+"));
    }

    @Test
    void theRealRegistryYieldsEveryUnattendedScenarioToTheNightlyMatrix() {
        ScenarioRegistry real = ScenarioRegistry.discover();

        List<String> matrix = NoderaTestMain.listing(real, true, "hardware");

        // The six the hand-kept workflow array had drifted past. Named individually rather than by
        // a count, so a scenario deleted and another added cannot keep this test green.
        assertThat(matrix)
                .as("the ids a hand-kept workflow array had never dispatched")
                .contains("telemetry", "churn", "ownership", "endpoint", "folia", "plugins");
        // And still the thirteen it did dispatch.
        assertThat(matrix).contains("continuity", "crash", "pickup", "mobs", "pearl", "password",
                "rekey", "commands", "farlands", "ownership-follow", "mesh-soak", "determinism",
                "profile");
        assertThat(matrix)
                .as("a runner with no phone attached must not be handed a hardware scenario")
                .doesNotContain("android-mesh", "chunk-continuity", "mobile-continuity");
        assertThat(matrix)
                .containsExactlyElementsOf(real.defaultBatch().stream().map(Scenario::id).toList());
    }

    private static String capturingStdout(Runnable body) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
