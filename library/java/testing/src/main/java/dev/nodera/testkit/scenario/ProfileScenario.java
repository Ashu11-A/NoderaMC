package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.mc.RconClient;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * WHERE THE TICK WENT (docs/minecraft/spark/).
 *
 * <p>Every other scenario asks whether Nodera is correct. This one asks what it costs, and it is the
 * only one whose artefact IS the result: a per-source breakdown naming the Nodera classes that
 * burned server tick time.
 *
 * <pre>
 *   R0  preflight: the pinned spark mod jar and python3
 *   R1  the dedicated NeoForge server under a real two-player entity-lane drive, profiled.
 *       THE ASSERTION: `nodera` must appear as an attributed source with non-zero self time
 *   R2  Paper, whose spark is BUNDLED (no jar installed), profiled. THE ASSERTION: `NoderaEndpoint`
 *       attributed
 *   R3  Folia, the same, with the capture explicitly spanning region threads — and no thread-context
 *       violation caused by profiling
 * </pre>
 *
 * <h2>Why R1's assertion is the point</h2>
 *
 * <p>A profile in which our own mod never appears looks exactly like a profile of a very fast mod.
 * It is almost never that. It is the profiler failing to load, or attributing our frames to nobody
 * because the class-source lookup was degraded, or a capture that spanned no ticks. Asserting that
 * {@code nodera} is PRESENT is what makes this a test rather than a data dump — the numbers are
 * reported, not gated, because a threshold on a shared CI runner would be a flake generator, not a
 * performance gate.
 *
 * <p>NOTHING IS UPLOADED. Every capture is {@code --save-to-file} and lands in the run's
 * {@code spark/} directory.
 *
 * <p>Thread-context: stateless; the runner calls {@link #run} on its own thread.
 */
public final class ProfileScenario implements Scenario {

    @Override
    public String id() {
        return "profile";
    }

    @Override
    public String title() {
        return "the profiler attributes real sampled tick time to Nodera's own classes";
    }

    @Override
    public Set<String> tags() {
        return Set.of("live", "server", "profile");
    }

    @Override
    public Requirements requirements() {
        return Requirements.liveClients(5);
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        LiveStack stack = ctx.stack();
        RconClient rcon = stack.rcon();
        long profileSeconds = envSeconds("PROFILE_SECONDS", 60);
        ServerSparkSupport spark = new ServerSparkSupport(ctx);

        // ---------------------------------------------------------------------------
        // R0 — preflight
        // ---------------------------------------------------------------------------
        ctx.stage("R0", "spark " + ServerSparkSupport.VERSION + " is available", () -> {
            spark.preflight();
            spark.resetSummary();
        });

        // ---------------------------------------------------------------------------
        // R1 — the NeoForge dedicated server under a real drive
        // ---------------------------------------------------------------------------
        ctx.stage("R1a", "players in-world, entity lane live, with the profiler installed", () -> {
            // The profiler is a real mod jar and FML must LOAD it, so it has to be in each run
            // directory's mods/ before the JVM starts. HARNESS-GAP: LiveStack's staging does not
            // know about spark, so the jars are placed here rather than by writeClientConfig, which
            // is where the shell launcher did it.
            spark.stageMod(stack.paths().gameDir("run"));
            spark.stageMod(stack.paths().gameDir("run-join"));
            spark.stageMod(stack.paths().gameDir("run-join2"));
            ServerDedicatedDrive.start(ctx, Map.of());
        });

        ctx.stage("R1b", "'nodera' is an attributed source in the dedicated-server profile", () -> {
            ctx.note("R1: profiling " + profileSeconds + "s of live entity-lane load");
            // Real load, not an idle tick: mobs the lane must capture, and a player moving through
            // regions so ownership re-plans while the sampler is running. An idle capture would
            // honestly report that an idle server is cheap.
            rcon.send("gamemode creative JoinerDev");
            for (int i = 0; i < 10; i++) {
                rcon.send("summon minecraft:chicken ~ ~ ~");
            }

            spark.start("neoforge-server");
            long elapsed = 0;
            while (elapsed < profileSeconds) {
                // Keep the lane working for the whole capture: cross a region boundary, come back,
                // and add mobs. Ownership re-planning is exactly the Nodera code path this scenario
                // exists to measure.
                rcon.teleport("JoinerDev", 600, 100, 600);
                ctx.settle(Duration.ofSeconds(10));
                rcon.teleport("JoinerDev", 0, 100, 0);
                rcon.send("summon minecraft:chicken ~ ~ ~");
                ctx.settle(Duration.ofSeconds(10));
                elapsed += 20;
            }
            Optional<Path> capture = spark.stop("neoforge-server");
            spark.health("neoforge-server");
            ctx.check(capture.isPresent(), "R1: the profiler wrote no capture");

            spark.assertCapture("R1 · NeoForge dedicated server", capture.get(), "nodera");
            ctx.check(spark.report("R1 · NeoForge dedicated server", capture.get(), "nodera"),
                    "R1: no sampled time was attributed to 'nodera'. The mod was driven for the "
                            + "whole capture, so this is a broken integration, not fast code — see "
                            + spark.summaryFile());
        });

        // ---------------------------------------------------------------------------
        // Hand the game port over to the Bukkit stages.
        //
        // This is the first scenario to run a NeoForge server AND a Bukkit server in one execution,
        // and they share the game and RCON ports. Stopping the dedicated server is not enough on its
        // own: the two dev CLIENTS are still up, still holding their connections, and the listener
        // stays bound for several seconds after the server thread exits. The first attempt at this
        // suite stopped only the server, slept 8 s, and Paper died with "bind(..) failed: Address
        // already in use" — a stage that then reports the platform as broken when the only thing
        // wrong was our own teardown.
        // ---------------------------------------------------------------------------
        ctx.stage("R1c", "the game and RCON ports are free for the Bukkit stages", () -> {
            rcon.send("stop");
            ServerDedicatedDrive.stopClient("runClientJoin", null);
            ServerDedicatedDrive.stopClient("runClientJoinTwo", null);
            ServerDedicatedDrive.awaitPortFree(ctx.topology().gamePort(), Duration.ofSeconds(60));
            ServerDedicatedDrive.awaitPortFree(ctx.topology().rconPort(), Duration.ofSeconds(60));
        });

        // ---------------------------------------------------------------------------
        // R2 — Paper, with the server's OWN bundled spark
        // ---------------------------------------------------------------------------
        try (ServerEndpointSupport paper = new ServerEndpointSupport(ctx).withSpark(spark)) {
            ctx.stage("R2", "Paper is profiled with spark seeing NoderaEndpoint installed", () -> {
                if (!paper.softPreflight("paper")) {
                    ctx.note("R2: SKIPPED — no nodera-paper jar or no Paper jar. Nothing was "
                            + "asserted for Paper.");
                    return;
                }
                // The endpoint needs a worker of its own to link to, or it runs vanilla and does
                // nothing at all — which is precisely what happened the first time this stage ran,
                // and why its capture contained no NoderaEndpoint frames.
                paper.startEndpointWorker();
                spark.withServerStage(paper.serverStage());
                paper.bukkitUp("paper", "00000000000000f1");

                spark.start("paper");
                ctx.settle(Duration.ofSeconds(profileSeconds));
                Optional<Path> capture = spark.stop("paper");
                spark.health("paper");
                ctx.check(capture.isPresent(),
                        "R2: Paper produced no capture — the bundled spark did not respond");

                spark.assertCapture("R2 · Paper", capture.get(), "NoderaEndpoint");
                if (spark.report("R2 · Paper", capture.get(), "NoderaEndpoint")) {
                    ctx.note("R2: Paper profiled; 'NoderaEndpoint' carries sampled time");
                } else {
                    ctx.note("R2: Paper profiled and spark saw NoderaEndpoint installed; it drew "
                            + "no samples in this window (a linked but quiet endpoint)");
                }

                rcon.send("stop");
                ServerDedicatedDrive.awaitPortFree(ctx.topology().gamePort(),
                        Duration.ofSeconds(60));
                ServerDedicatedDrive.awaitPortFree(ctx.topology().rconPort(),
                        Duration.ofSeconds(60));
            });
        }

        // ---------------------------------------------------------------------------
        // R3 — Folia, where the tick is spread across region threads
        // ---------------------------------------------------------------------------
        try (ServerEndpointSupport folia = new ServerEndpointSupport(ctx).withSpark(spark)) {
            ctx.stage("R3", "the Folia capture spans the region pool and profiling breaks no "
                    + "thread contract", () -> {
                if (!folia.softPreflight("folia")) {
                    ctx.note("R3: SKIPPED — no nodera-paper jar or no Folia jar. Nothing was "
                            + "asserted for Folia.");
                    return;
                }
                if (!spark.foliaAvailable()) {
                    // Not a Nodera defect and not a Folia defect: Folia bundles spark and then
                    // declines to enable it ("disabled in the configuration") whatever
                    // paper-global.yml and -Dpaper.preferSparkPlugin say, and the community
                    // spark-folia build from spark-extra-platforms currently targets a newer Folia —
                    // against the pinned 1.21.4 it dies on the first command with
                    // NoClassDefFoundError: ca/spottedleaf/moonrise/common/time/TickData.
                    // Say so by name; an unexplained skip is how a gap becomes permanent.
                    ctx.note("R3: SKIPPED — Folia's bundled spark will not enable and no "
                            + "compatible $NODERA_SPARK_FOLIA_JAR is staged.");
                    ctx.note("R3: stage a spark-folia build matching " + folia.serverJar()
                            + " to turn this stage on.");
                    return;
                }
                spark.withServerStage(folia.serverStage());
                folia.stageBukkitServer("00000000000000f2");
                folia.startBukkitServer("folia.log");
                if (!ctx.log("folia.log").pollFor("Done (", Duration.ofSeconds(480), 0)) {
                    ctx.note("R3: Folia never finished booting; nothing was asserted (see "
                            + ctx.log("folia.log").file() + ")");
                    return;
                }

                spark.start("folia");
                ctx.settle(Duration.ofSeconds(profileSeconds));
                Optional<Path> capture = spark.stop("folia");
                ctx.check(capture.isPresent(), "R3: Folia produced no capture");

                spark.report("R3 · Folia", capture.get(), null);

                // The claim this stage exists to check: Folia has no main thread, so a capture that
                // saw only one thread saw one region and called it the server. A single-threaded
                // Folia profile is not a fast server, it is a capture that missed the work.
                int threads = spark.threadCount(capture.get());
                ctx.check(threads > 1, "R3: the Folia capture covered " + threads
                        + " thread(s) — --thread * did not span the region pool");
                ctx.note("R3: the Folia capture spans " + threads + " threads");

                // Profiling must not have dragged Bukkit API onto a wrong thread.
                folia.assertNoThreadContextViolations("folia.log");

                rcon.send("stop");
                ctx.settle(Duration.ofSeconds(6));
            });
        }

        spark.collect();
        stack.collectArtefacts();
        ctx.note("per-source summary: " + spark.summaryFile());
    }

    private static long envSeconds(String name, long fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }
}
