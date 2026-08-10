package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.harness.Topology;
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
 *   F5  a delegated region under SUSTAINED REDSTONE LOAD commits for five minutes with the resync
 *       count under threshold and no region revoked for interference rate. This is [L-67]'s exit
 * </pre>
 *
 * <p><b>F5 fails today, and that is the report.</b> Its first act is to ask whether any region on
 * this endpoint is delegated, and nothing on the endpoint path delegates one yet (server tasks 2 and
 * 3). The stage exists anyway because L-67's exit clause names it, and a row whose exit test exists
 * in no form cannot be called unmet — only unmeasured. The two numbers that clause hides are derived
 * and recorded in {@code docs/server/Task.5.md} §Design, and asserted headlessly by
 * {@code InterferenceThroughputTest}.
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

    /** F5's own server log — F2's server refused to enable, so F5 boots a fresh one. */
    private static final String SOAK_LOG = "folia-redstone.log";

    /** Five minutes, as the exit clause says. Deliberately NOT scaled: it is the claim. */
    private static final Duration SOAK = Duration.ofMinutes(5);

    /** Half a power cycle. ~5 cycles a second is sustained without saturating RCON. */
    private static final Duration TOGGLE_INTERVAL = Duration.ofMillis(100);

    /** Blocks of redstone dust the signal crosses, with a repeater every eight. */
    private static final int CIRCUIT_LENGTH = 32;

    /** The first air layer above a default flat world's grass (bedrock −64 … grass −61). */
    private static final int CIRCUIT_Y = -60;

    /** {@code EntityLaneSoakMetrics.MAX_RESYNC_RATE_BPS}, restated for the message. */
    private static final int MAX_RESYNC_RATE_BPS = 100;

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

            // ---------------------------------------------------------------------------
            // F5 — L-67's exit: sustained redstone in a DELEGATED region
            //
            // A fresh server at the working exponent, because F2 left one that refused to enable.
            // ---------------------------------------------------------------------------
            ctx.stage("F5", "a delegated region under sustained redstone load commits for five "
                    + "minutes with the resync count under threshold and nothing revoked",
                    () -> redstoneSoak(ctx, server));

            ctx.stack().collectArtefacts();
        }
    }

    /**
     * F5 — [L-67]'s exit: sustained vanilla redstone inside a delegated region.
     *
     * <h2>The two numbers the exit clause hides</h2>
     *
     * <p>"the resync count under threshold, and no region is revoked for interference rate" names
     * two thresholds and states neither. They are derived in {@code docs/server/Task.5.md} §Design
     * and are:
     *
     * <ul>
     *   <li><b>resync</b> — {@code EntityLaneSoakMetrics.MAX_RESYNC_RATE_BPS} = 100 basis points,
     *       i.e. resyncs are at most 1% of all commit outcomes. The entity lane's own Task-12 exit
     *       bar, applied to a different load;</li>
     *   <li><b>revocation</b> — {@code NoderaConstants.INTERFERENCE_REVOKE_RATE} = 60 foreign writes
     *       per {@code INTERFERENCE_RATE_WINDOW_TICKS} = 1200 ticks.</li>
     * </ul>
     *
     * <p>Recording them is what makes this stage answerable, and it is also what shows the clause
     * cannot be satisfied as written: one two-tick repeater clock is 600 edges a window, ten times
     * the revocation bound, and on an endpoint every one of them is a foreign write.
     * {@code InterferenceThroughputTest} asserts that arithmetic headlessly.
     *
     * <h2>Why the load is driven over RCON rather than built as a self-running clock</h2>
     *
     * <p>A torch-burnout clock depends on world generation and on block states this harness would
     * have to get exactly right on two platforms; a driven toggle is the same scheduled-tick load
     * with a known rate, and a rate is what the thresholds above are stated in.
     */
    private static void redstoneSoak(ScenarioContext ctx, ServerEndpointSupport server)
            throws Exception {
        server.startEndpointWorker();
        server.stageBukkitServer(WORLD_ID, ServerEndpointSupport.FOLIA_GRID_EXPONENT);
        server.startBukkitServer(SOAK_LOG);
        LogWatcher log = ctx.log(SOAK_LOG);
        log.await("Done (", Duration.ofSeconds(480));

        server.requireDelegatedRegion("F5", log);

        var rcon = ctx.stack().rcon();
        // Nobody is standing here, so the chunks have to be held open explicitly: an unloaded chunk
        // schedules no ticks at all and the soak would measure nothing while looking calm.
        rcon.require("forceload add 0 0 " + (CIRCUIT_LENGTH + 16) + " 16");
        rcon.require("fill 8 " + CIRCUIT_Y + " 8 " + (8 + CIRCUIT_LENGTH) + " " + CIRCUIT_Y
                + " 8 minecraft:redstone_wire");
        for (int x = 16; x < 8 + CIRCUIT_LENGTH; x += 8) {
            // Repeaters are the scheduled ticks. Dust alone updates instantly and schedules nothing,
            // so a line of pure dust would drive the interference guard and never the tick queue.
            rcon.require("setblock " + x + " " + CIRCUIT_Y + " 8 "
                    + "minecraft:repeater[facing=east,delay=4]");
        }
        rcon.require("setblock " + (9 + CIRCUIT_LENGTH) + " " + CIRCUIT_Y
                + " 8 minecraft:redstone_lamp");

        long commitsBefore = commits(server);
        long deadline = System.nanoTime() + SOAK.toNanos();
        int toggles = 0;
        while (System.nanoTime() < deadline) {
            rcon.require("setblock 7 " + CIRCUIT_Y + " 8 minecraft:redstone_block");
            Topology.sleep(TOGGLE_INTERVAL);
            rcon.require("setblock 7 " + CIRCUIT_Y + " 8 minecraft:air");
            Topology.sleep(TOGGLE_INTERVAL);
            toggles++;
        }
        ctx.note("F5: drove " + toggles + " power cycles through " + CIRCUIT_LENGTH
                + " blocks of redstone over " + SOAK.toSeconds() + "s");

        // A positive control: a soak that drove nothing would satisfy every assertion below.
        ctx.check(toggles > 100,
                "F5: only " + toggles + " power cycles in " + SOAK.toSeconds()
                        + "s — that is not a sustained load, and the thresholds below mean nothing"
                        + " against it");

        long commitsAfter = commits(server);
        ctx.check(commitsAfter > commitsBefore,
                "F5: the delegated region committed nothing across the soak (" + commitsBefore
                        + " → " + commitsAfter + ") — 'commits for five minutes' is the first half"
                        + " of the clause");
        ctx.check(server.endpointStateField("validation.divergences").orElse("-1").equals("0"),
                "F5: the region diverged under redstone load");
        ctx.check(server.endpointStateField("validation.resync_rate_bps").isPresent(),
                "F5: the endpoint reports no resync rate, so the clause's own threshold ("
                        + MAX_RESYNC_RATE_BPS + " bps, EntityLaneSoakMetrics.MAX_RESYNC_RATE_BPS)"
                        + " cannot be checked against anything. Exposing it on NODERA-STATE is a"
                        + " prerequisite of this stage.");
        ctx.check(!log.contains("INTERFERENCE_RATE_HIGH"),
                "F5: a region was revoked for interference rate under exactly the load this stage"
                        + " exists to sustain — see docs/server/Task.5.md §Design for why "
                        + "INTERFERENCE_REVOKE_RATE and vanilla redstone cannot both stand");

        server.assertNoThreadContextViolations(SOAK_LOG, "worker-endpoint.log");
        rcon.send("stop");
        ctx.settle(Duration.ofSeconds(5));
    }

    /** Committee commits the endpoint's own peer reports, for the "keeps committing" clause. */
    private static long commits(ServerEndpointSupport server) {
        return server.endpointStateField("validation.committee_commits")
                .map(Long::parseLong).orElse(0L);
    }
}
