package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.time.Duration;
import java.util.Set;

/**
 * The ALIGN-1 drive on a REGIONISED server (server task 1, L-61 · docs/server/REFERENCE.md §2).
 *
 * <pre>
 *   F0  preflight: is there a plugin jar and a Folia jar? Missing either is a SKIP — a nightly run
 *       reads as "not built yet", never as "broken"
 *   F1  Folia boots with nodera-endpoint at grid-exponent 4 (Folia's own default): the plugin must
 *       identify the platform as FOLIA — from the regionised scheduler, not from a name — and log
 *       an ALIGN-1 PASS
 *   F2  THE REFUSAL: the same jar on the same server at grid-exponent 2, where a Nodera region
 *       straddles two Folia sections, must REFUSE TO ENABLE and name the setting, the value and the
 *       fix. This is the stage that matters: a preflight that only ever passes has never been shown
 *       to be a check
 * </pre>
 *
 * <p>Version note: Folia has no 1.21.1 build (it skipped from 1.21.x to 1.21.4+), so this runs the
 * newest Folia available. That is sound for what it asserts — plugin enable and ALIGN-1 are platform
 * properties, not gameplay — and it is exactly why the MIXED-CLIENT scenarios stay blocked on L-66,
 * which is about a client being able to join at all.
 *
 * <p>Thread-context: stateless; the runner calls {@link #run} on its own thread.
 */
public final class FoliaScenario implements Scenario {

    /** The world the staged endpoint claims; see {@link EndpointScenario} for why it must name one. */
    private static final String WORLD_ID = "0000000000000f01";

    /**
     * The exponent at which a Nodera region straddles two Folia sections.
     *
     * <p>ALIGN-1 needs grid-exponent &gt;= 3, so 2 is the first value that must be refused: a region
     * there is written by two threads.
     */
    private static final int SPLITTING_GRID_EXPONENT = 2;

    @Override
    public String id() {
        return "folia";
    }

    @Override
    public String title() {
        return "the endpoint verifies ALIGN-1 on Folia and refuses to enable where it cannot hold";
    }

    @Override
    public Set<String> tags() {
        return Set.of("server", "folia");
    }

    @Override
    public Requirements requirements() {
        return Requirements.paperEndpoint();
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        try (ServerEndpointSupport server = new ServerEndpointSupport(ctx)) {
            // ---------------------------------------------------------------------------
            // F0 — preflight
            // ---------------------------------------------------------------------------
            ctx.stage("F0", "a plugin jar and a Folia jar are both present",
                    () -> server.preflight("folia"));

            // ---------------------------------------------------------------------------
            // F1 — ALIGN-1 holds at the platform default
            // ---------------------------------------------------------------------------
            ctx.stage("F1", "Folia is identified and ALIGN-1 passes with four whole regions per "
                    + "section", () -> {
                ctx.note("F1: Folia at grid-exponent " + ServerEndpointSupport.FOLIA_GRID_EXPONENT
                        + " (the platform default)");
                server.stageBukkitServer(WORLD_ID, ServerEndpointSupport.FOLIA_GRID_EXPONENT);
                server.startBukkitServer("folia-ok.log");
                LogWatcher log = ctx.log("folia-ok.log");
                log.await("Done (", Duration.ofSeconds(480));

                ctx.check(log.contains("Nodera endpoint on Folia"),
                        "F1: the plugin did not identify the platform as Folia — the scheduler "
                                + "probe missed");
                ctx.check(log.contains("ALIGN-1 preflight passed"),
                        "F1: no ALIGN-1 pass line at grid-exponent "
                                + ServerEndpointSupport.FOLIA_GRID_EXPONENT);
                ctx.check(log.contains("4 Nodera regions per Folia section, none split"),
                        "F1: the ALIGN-1 line did not state the nesting it verified");
                // Server task 3 / L-64: ALIGN-1 holding is not the same claim as the plugin being
                // able to SAY which regions one thread writes. Without that answer a cross-region
                // delta cannot even be detected, let alone refused, which is why it is asserted
                // here rather than left to the first delta to discover.
                ctx.check(log.contains("cross-region commit: regionised at grid-exponent "
                                + ServerEndpointSupport.FOLIA_GRID_EXPONENT),
                        "F1: the plugin did not report which Nodera regions share an execution "
                                + "thread (server task 3 — L-64)");
                // The gate exercised against the REAL regioniser, at enable: the regions that nest
                // in one Folia section are admitted, and a span the platform cannot justify fails
                // closed. A preflight that only ever passes has never been shown to be a check —
                // the same argument F2 makes about ALIGN-1 itself.
                ctx.check(log.contains("ALIGN-1 live: the gate admits"),
                        "F1: the cross-region gate was never exercised at enable (L-64 stage 1)");
                ctx.check(log.contains("a span it cannot justify fails closed"),
                        "F1: the gate admitted a span across two Folia sections that the platform "
                                + "could not justify — L-64's refusal did not fire");

                ctx.stack().rcon().send("stop");
                // Not fatal: the server may have exited before the plugin's disable line was
                // flushed, and F1's claim is about what it logged on the way UP.
                if (!log.pollFor("Nodera endpoint stopped", Duration.ofSeconds(180), 0)) {
                    ctx.note("F1: no clean disable line (the server may have exited first)");
                }
                ctx.settle(Duration.ofSeconds(5));
            });

            // ---------------------------------------------------------------------------
            // F2 — THE REFUSAL at an exponent that splits a region
            // ---------------------------------------------------------------------------
            ctx.stage("F2", "the plugin refuses to enable at grid-exponent 2 and says exactly what "
                    + "to change", () -> {
                server.stageBukkitServer(WORLD_ID, SPLITTING_GRID_EXPONENT);
                server.startBukkitServer("folia-refuse.log");
                LogWatcher log = ctx.log("folia-refuse.log");
                // The server still boots; it is the PLUGIN that must refuse.
                log.await("Done (", Duration.ofSeconds(480));

                ctx.check(log.contains("Nodera refuses to enable"),
                        "F2: the plugin enabled at grid-exponent " + SPLITTING_GRID_EXPONENT
                                + " — a region there is written by two threads");
                ctx.check(log.contains("threaded-regions.grid-exponent"),
                        "F2: the refusal did not name the setting to change");
                ctx.check(log.contains("config/paper-global.yml"),
                        "F2: the refusal did not name the file to change it in");
                ctx.check(!log.contains("ALIGN-1 preflight passed"),
                        "F2: the plugin claimed an ALIGN-1 pass at an exponent that splits regions");

                ctx.stack().rcon().send("stop");
                ctx.settle(Duration.ofSeconds(5));
            });

            ctx.stack().collectArtefacts();
        }
    }
}
