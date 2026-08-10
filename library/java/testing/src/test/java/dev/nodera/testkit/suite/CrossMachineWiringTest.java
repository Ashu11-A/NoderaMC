package dev.nodera.testkit.suite;

import dev.nodera.testkit.harness.TestPaths;
import dev.nodera.testkit.harness.Topology;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three pieces of wiring the cross-machine run needs, none of which starts a process.
 *
 * <p>The scenario itself cannot be unit-tested — it builds container networks and a Minecraft
 * client, and a "unit test" that did that would be a live suite in disguise. What CAN be held here
 * is everything the scenario would fail on silently: whether the harness will accept an address it
 * only learns at run time, whether the lifecycle hook it depends on is really called in the order it
 * assumes, and whether the image it builds from is still where it thinks it is.
 *
 * <p>Thread-context: ordinary JUnit. The addressing test mutates system properties and restores
 * them, because {@code HarnessAddressingTest} asserts the defaults and would read whatever was left
 * behind.
 */
class CrossMachineWiringTest {

    private static final String ADVERTISE = "NODERA_SERVICE_ADVERTISE_ADDR";

    @Test
    void aScenarioCanHandTheStackAnAddressItOnlyLearnedAtRunTime() {
        // The cross-machine run creates two container networks and MEASURES the address the stack
        // has to bind — Docker chose the subnet, so nothing could have exported it beforehand. Java
        // has no way for a process to add a variable to its own environment, so without this the
        // scenario would have to hard-code a subnet (which collides with whatever else the machine
        // runs) or re-exec itself.
        String previous = System.getProperty(ADVERTISE);
        try {
            System.setProperty(ADVERTISE, "172.31.60.1");

            assertThat(Topology.serviceAdvertiseAddress())
                    .as("the environment is unset in this JVM, so the property is what is left")
                    .isEqualTo("172.31.60.1");
            assertThat(Topology.isLanBound())
                    .as("a stack advertising a bridge address is not a loopback stack")
                    .isTrue();
            assertThat(Topology.standard().trackerEndpoints())
                    .as("and every peer is told that address, not loopback")
                    .containsExactly("172.31.60.1:" + Topology.standard().trackerPort());
        } finally {
            if (previous == null) {
                System.clearProperty(ADVERTISE);
            } else {
                System.setProperty(ADVERTISE, previous);
            }
        }
        assertThat(Topology.serviceAdvertiseAddress())
                .as("and the next scenario in the queue is back on loopback")
                .isEqualTo("127.0.0.1");
    }

    @Test
    void prepareRunsBeforeTheTopologyIsAskedForAndCleanUpAlwaysRuns() {
        // The order is the whole reason the hook exists: the stack binds using the topology, so an
        // address decided after `topology()` is an address the stack never sees. And `cleanUp` runs
        // on every outcome because what `prepare` builds is OUTSIDE this JVM — a container network
        // survives the process that forgot it, and the next run fails creating one that exists.
        List<String> calls = new ArrayList<>();
        Scenario scenario = new Scenario() {
            @Override public String id() {
                return "fake";
            }

            @Override public String title() {
                return "never runs";
            }

            @Override public Set<String> tags() {
                return Set.of("headless");
            }

            @Override public void prepare(TestPaths paths) {
                calls.add("prepare");
                throw new SkipSignal("this machine has no second network");
            }

            @Override public Topology topology() {
                calls.add("topology");
                return Topology.standard();
            }

            @Override public void cleanUp() {
                calls.add("cleanUp");
            }

            @Override public void run(ScenarioContext context) {
                calls.add("run");
            }
        };

        ScenarioResult result = new ScenarioRunner(TestPaths.discover(), false, false)
                .run(scenario);

        assertThat(result.outcome()).isEqualTo(StageResult.Outcome.SKIPPED);
        assertThat(result.message()).contains("no second network");
        assertThat(calls)
                .as("prepare first, no topology because it skipped, and cleanUp regardless")
                .containsExactly("prepare", "cleanUp");
    }

    @Test
    void theCrossMachineScenarioIsDiscoverableAndAsksForWhatItActuallyNeeds() {
        // A scenario missing from META-INF/services is invisible — that file's own comment says so
        // and calls `nodera-test list` its only smoke test. This is that smoke test, held where a
        // wrong answer is red rather than merely absent from a listing nobody re-read.
        Scenario crossMachine = ScenarioRegistry.discover().require("cross-machine");

        assertThat(crossMachine.tags()).contains("live");
        assertThat(crossMachine.requirements().needsDisplay())
                .as("the hosting side is a real Minecraft client, which needs a GUI session")
                .isTrue();
        assertThat(crossMachine.topology().workers())
                .as("one player plus the spare here; the third peer is the one on the other network")
                .isEqualTo(2);
    }
}
