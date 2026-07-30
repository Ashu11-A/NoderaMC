package dev.nodera.testkit.suite;

import dev.nodera.testkit.harness.Topology;

import java.util.Set;

/**
 * One live acceptance scenario — the Java form of what used to be a {@code scripts/e2e-*.sh}.
 *
 * <h2>Why these stopped being shell scripts</h2>
 *
 * <p>Twenty shell suites shared a launcher and nothing else. Each re-implemented its own waiting,
 * its own JSON poking with {@code grep}, its own idea of what "the world is shared" looks like in a
 * log, and its own exit conventions — so a change to an evidence line fixed thirteen suites and
 * silently broke seven, and a failure was a stage number with no structure a report could read. As
 * Java scenarios they share one harness, one assertion vocabulary, and one result model that the
 * report renders without parsing anybody's stdout.
 *
 * <h2>The contract</h2>
 *
 * <p>A scenario declares what it is, what it needs, and what topology it runs on; the runner
 * decides whether the machine can host it, builds the stack, and calls {@link #run}. Everything a
 * scenario asserts goes through {@link ScenarioContext#stage}, so every run produces the same
 * stage-by-stage evidence whether it passed, failed, or was skipped.
 *
 * <p>Implementations must be stateless between runs: the runner may execute the same instance more
 * than once in a queue.
 *
 * <p>Thread-context: {@link #run} is called on the runner's thread; scenarios never run in parallel
 * (they share the port block and the game directories).
 */
public interface Scenario {

    /** Stable identifier, used on the command line and as the results-directory name. */
    String id();

    /** One line describing what a pass proves. */
    String title();

    /**
     * Tags the runner filters on.
     *
     * <p>Conventional values: {@code live} (starts Minecraft), {@code headless} (does not),
     * {@code server} (needs the Paper/Folia endpoint), {@code hardware} (needs a device this
     * machine may not have — never part of a default batch).
     */
    Set<String> tags();

    /** What the machine must provide; unmet requirements are a skip, never a failure. */
    default Requirements requirements() {
        return Requirements.none();
    }

    /** The topology to build for this scenario. */
    default Topology topology() {
        return Topology.standard();
    }

    /**
     * Run the scenario.
     *
     * @param context the harness, plus the stage recorder every assertion goes through.
     * @throws Exception any failure; the runner records it against the open stage.
     */
    void run(ScenarioContext context) throws Exception;
}
