package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.harness.Topology;
import dev.nodera.testkit.mc.RconClient;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The PAPER ENDPOINT drive (server task 1, L-61).
 *
 * <pre>
 *   E0  preflight: is there a plugin jar, and a Paper jar to run it on? Missing either is a SKIP,
 *       never red: a nightly run must read as "not built yet", not as "broken"
 *   E1  the stack, then a clean-slate Paper server with nodera-endpoint installed — the plugin must
 *       ENABLE and say which platform it is on
 *   E2  ALIGN-1: on Paper the preflight is not applicable and the plugin says so; the enable path
 *       must never claim an invariant it did not check
 *   E4  the world is hosted BY THE WORKER and survives the server being killed (L-71)
 *   E3  the plugin survives a full server lifecycle — stop leaves a clean disable line rather than a
 *       stack trace
 *   P6  a validated item neither despawns nor drifts over a hold window LONGER than vanilla's own
 *       despawn age, and picking it up credits EXACTLY ONCE. This is [L-69]'s exit, it runs LAST on
 *       a server of its own, and it fails today
 * </pre>
 *
 * <p>What this scenario does NOT assert yet, deliberately: nothing about validation, hosting or
 * capture beyond L-71's own claim. Those are server tasks 2 and 3, and each stage lands with the
 * capability rather than ahead of it.
 *
 * <p><b>P6 fails today, and that is the report.</b> Its first act is to ask whether any region on
 * this endpoint is delegated, and nothing on the endpoint path delegates one yet. The stage exists
 * anyway because L-69's exit clause names it: a row whose exit test does not exist cannot be said to
 * be unmet — only unmeasured — and those are different claims about the project. See
 * {@link ServerEndpointSupport#requireDelegatedRegion}.
 *
 * <p>Thread-context: stateless; the runner calls {@link #run} on its own thread.
 */
public final class EndpointScenario implements Scenario {

    /**
     * The world the staged endpoint claims.
     *
     * <p>The staged config is {@code listed: true} + {@code custody: FULL}, so it must name a world:
     * advertising full custody of a world nobody can name is an announce no tracker can use, and the
     * plugin refuses it. Passing an id here is what a real operator does; the task-2 scenarios will
     * pass the id their host flow minted.
     */
    private static final String WORLD_ID = "0000000000000e2e";

    /** An exception thrown by the endpoint, however Bukkit chose to report it. */
    private static final Pattern ENDPOINT_EXCEPTION =
            Pattern.compile("\\[NoderaEndpoint\\].*Exception|Could not pass event.*NoderaEndpoint");

    /** P6's own server log: E4 killed the first server and E3 stopped the second. */
    private static final String PIN_LOG = "server-pin.log";

    /** The unmodified client P6 drives — nothing installed, joining at the game port. */
    private static final String TENANT = "P6Tenant";

    /** The item P6 validates. Any item works; a stackable one makes "exactly once" countable. */
    private static final String PINNED_ITEM = "minecraft:diamond";

    /** Vanilla's despawn age. The hold window is this long for the reason stated on P6. */
    private static final int VANILLA_DESPAWN_TICKS = 6000;

    /** {@link #VANILLA_DESPAWN_TICKS} at 20 TPS. Deliberately NOT scaled — see P6. */
    private static final Duration HOLD_WINDOW = Duration.ofMinutes(5);

    /**
     * The drift a pinned projection may show, in blocks — the same number as
     * {@code ProjectionPinner.MAX_OBSERVED_DRIFT_BLOCKS}, restated because {@code :testing} does not
     * depend on {@code :paper-plugin}.
     */
    private static final double MAX_DRIFT_BLOCKS = 1.0;

    /** {@code [1.5d, -59.0d, 6.5d]} out of a {@code data get} reply. */
    private static final Pattern VECTOR = Pattern.compile(
            "\\[\\s*(-?[0-9.eE+-]+)d?,\\s*(-?[0-9.eE+-]+)d?,\\s*(-?[0-9.eE+-]+)d?\\s*]");

    /** The trailing short in {@code … entity data: 42s}. */
    private static final Pattern SHORT_VALUE = Pattern.compile("data:\\s*(-?\\d+)s?\\s*$");

    /** {@code Found 1 matching item(s) on player X} — {@code /clear … 0} counts without removing. */
    private static final Pattern FOUND_COUNT = Pattern.compile("Found (\\d+) matching");

    @Override
    public String id() {
        return "endpoint";
    }

    @Override
    public String title() {
        return "nodera-endpoint enables on Paper and keeps its world hosted when the server dies";
    }

    @Override
    public Set<String> tags() {
        return Set.of("server", "endpoint");
    }

    @Override
    public Requirements requirements() {
        return Requirements.paperEndpoint();
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        try (ServerEndpointSupport server = new ServerEndpointSupport(ctx)) {
            // ---------------------------------------------------------------------------
            // E0 — preflight BEFORE staging a server we might not use
            // ---------------------------------------------------------------------------
            ctx.stage("E0", "a plugin jar and a Paper jar are both present",
                    () -> server.preflight("paper"));

            // ---------------------------------------------------------------------------
            // E1 — a Paper server carrying the endpoint
            //
            // The stack is already up: the runner started the tracker, the rendezvous and the
            // workers before this method was called. What is left is the endpoint's OWN always-on
            // worker and the server itself.
            // ---------------------------------------------------------------------------
            ctx.stage("E1", "Paper boots with the plugin installed, enabled, and naming its platform",
                    () -> {
                server.startEndpointWorker();
                server.stageBukkitServer(WORLD_ID);
                server.startBukkitServer("server.log");
                LogWatcher log = ctx.log("server.log");
                log.await("Done (", Duration.ofSeconds(420));

                ctx.check(log.contains("[NoderaEndpoint] Enabling"),
                        "E1: the plugin never enabled (see " + log.file() + ")");
                ctx.check(log.contains("Nodera endpoint on Paper"),
                        "E1: the plugin did not identify its platform as Paper");
                // The plugin refuses to enable on a configuration it cannot honour, which is correct
                // behaviour and would otherwise pass this scenario silently: every later stage reads
                // log lines the refusal path also writes. Assert it STAYED enabled against the
                // config the harness itself stages.
                ctx.check(!log.contains("Nodera refuses to enable"),
                        "E1: the plugin refused the configuration the harness stages (see "
                                + log.file() + ")");
                ctx.check(!log.contains("[NoderaEndpoint] Disabling"),
                        "E1: the plugin disabled itself during boot");
            });

            // ---------------------------------------------------------------------------
            // E2 — ALIGN-1 is claimed only where it was checked
            // ---------------------------------------------------------------------------
            ctx.stage("E2", "ALIGN-1 is reported as not applicable on a single-threaded platform",
                    () -> {
                LogWatcher log = ctx.log("server.log");
                ctx.check(!log.contains("ALIGN-1 preflight passed"),
                        "E2: the plugin claimed an ALIGN-1 pass on Paper, where there are no "
                                + "regions to nest");
                ctx.check(log.contains("ALIGN-1 preflight not applicable"),
                        "E2: the plugin said nothing about ALIGN-1 — silence is not a check");
            });

            // ---------------------------------------------------------------------------
            // E4 — the world is hosted BY THE WORKER, and survives the server being killed
            //
            // This is L-71's exit: a node inside the server JVM dies with it, taking the world off
            // the network exactly when it most needs to still be there. The kill is a real SIGKILL —
            // a graceful stop would prove the opposite of what is claimed.
            // ---------------------------------------------------------------------------
            ctx.stage("E4", "the endpoint linked, handed the world to its worker, and the world "
                    + "survives a SIGKILL of the server JVM", () -> {
                LogWatcher log = ctx.log("server.log");
                ctx.check(log.contains("linked to the Nodera worker at 127.0.0.1:"
                                + server.endpointControlPort()),
                        "E4: the endpoint never linked to its worker on "
                                + server.endpointControlPort());
                ctx.check(!log.contains("no Nodera worker answering"),
                        "E4: the endpoint reported no worker — it linked late or not at all");
                ctx.check(log.contains("the worker is hosting world"),
                        "E4: the endpoint never asked its worker to host the configured world");

                // Assert on the STATE field rather than grepping the id anywhere in the reply: an
                // error message that happens to quote the id would otherwise read as a hosted world.
                ctx.check(hostsWorld(server),
                        "E4: the worker does not report hosting " + WORLD_ID
                                + " in connected_worlds");

                ctx.note("E4: SIGKILLing the server JVM — the world must stay on the network");
                server.serverProcess().kill();
                ctx.settle(Duration.ofSeconds(10));

                ctx.check(server.endpoint().isUp(),
                        "E4: the worker died with the server — that is exactly L-71");
                ctx.check(hostsWorld(server),
                        "E4: the world stopped being hosted when the server JVM was killed (L-71)");
            });

            // ---------------------------------------------------------------------------
            // E3 — a clean shutdown (on a FRESH server: E4 killed the first one)
            // ---------------------------------------------------------------------------
            ctx.stage("E3", "a fresh server stops cleanly and the endpoint leaves no exception",
                    () -> {
                server.startBukkitServer("server-restart.log");
                LogWatcher restart = ctx.log("server-restart.log");
                restart.await("Done (", Duration.ofSeconds(420));
                ctx.stack().rcon().send("stop");
                restart.await("Nodera endpoint stopped", Duration.ofSeconds(180));
                ctx.check(restart.lastCapture(ENDPOINT_EXCEPTION).isEmpty()
                                && ctx.log("server.log").lastCapture(ENDPOINT_EXCEPTION).isEmpty(),
                        "E3: the server log carries an exception from the endpoint");
            });

            // ---------------------------------------------------------------------------
            // P6 — a validated item is pinned: no despawn, a NUMERIC drift bound, one credit
            //
            // LAST, and on a server of its own, deliberately. P6 fails today (nothing delegates a
            // region), and the runner stops a scenario at its first failing stage — so putting it
            // anywhere earlier would take E4 down with it, and E4 is L-71's RETIRED exit test. A
            // stage that is expected to fail must never be upstream of one that is expected to
            // pass, or a retirement quietly stops being checked.
            // ---------------------------------------------------------------------------
            ctx.stage("P6", "a validated item neither despawns nor drifts over the hold window, "
                    + "and picking it up credits exactly once", () -> pinnedItemStage(ctx, server));

            ctx.stack().collectArtefacts();
        }
    }

    /**
     * P6 — [L-69]'s exit, in the player's own terms.
     *
     * <h2>Why the hold window is five minutes and not "a while"</h2>
     *
     * <p>Vanilla despawns an item at 6000 ticks. A hold window shorter than that cannot tell a
     * pinned item from an unpinned one, so the stage would pass against a projection nothing was
     * doing anything to. Five minutes is exactly that age, which is the shortest window that asserts
     * anything at all.
     *
     * <h2>Why the drift assertion is a number</h2>
     *
     * <p>"The item is still there" is satisfied by an item that despawned and was respawned by
     * something else, and by one that slid twenty blocks down a slope. So the stage records the
     * position at the start and compares it at the end against
     * {@link #MAX_DRIFT_BLOCKS} — the bound the pin's own hysteresis band makes true
     * ({@code ProjectionPinner.MAX_OBSERVED_DRIFT_BLOCKS}; the two are the same number, restated
     * here because {@code :testing} does not depend on {@code :paper-plugin}). It is deliberately
     * NOT zero: a pin that wrote the position back on every tick would be client-visible jitter, so
     * the assertion has to tolerate motion between ticks without becoming vacuous.
     *
     * <h2>Why the credit is counted rather than observed</h2>
     *
     * <p>{@code /clear <player> <item> 0} counts without removing, which is the only way to ask
     * "exactly how many" over RCON. Two is the failure this clause exists for.
     */
    private static void pinnedItemStage(ScenarioContext ctx, ServerEndpointSupport server)
            throws Exception {
        // E4 SIGKILLed the first server and E3 stopped the second, so P6 boots its own.
        server.startBukkitServer(PIN_LOG);
        LogWatcher log = ctx.log(PIN_LOG);
        log.await("Done (", Duration.ofSeconds(420));
        server.requireDelegatedRegion("P6", log);

        RconClient rcon = ctx.stack().rcon();
        Thread bot = startTenant(ctx, server);
        try {
            rcon.awaitPlayer(TENANT, ctx.topology().scaled(Duration.ofSeconds(120)));
            rcon.awaitReady(TENANT);

            // Four blocks away: inside the same loaded, ticking chunk, and outside vanilla's
            // one-block pickup reach, so the hold window measures the pin rather than a race with
            // the tenant standing on the item.
            rcon.require("execute at " + TENANT + " run summon item ^ ^ ^4 "
                    + "{Item:{id:\"" + PINNED_ITEM + "\",count:1}}");
            ctx.settle(Duration.ofSeconds(5));

            double[] before = itemPosition(rcon)
                    .orElseThrow(() -> new IllegalStateException(
                            "P6: the summoned item was not in the world five seconds later"));
            ctx.note("P6: the item is at " + java.util.Arrays.toString(before)
                    + "; holding for " + HOLD_WINDOW.toSeconds() + "s ("
                    + VANILLA_DESPAWN_TICKS + " ticks — vanilla's own despawn age)");

            Topology.sleep(HOLD_WINDOW);

            double[] after = itemPosition(rcon).orElseThrow(() -> new IllegalStateException(
                    "P6: the validated item is gone after " + HOLD_WINDOW.toSeconds()
                            + "s — a pinned projection has its lifetime reset every region tick, so"
                            + " it cannot reach vanilla's " + VANILLA_DESPAWN_TICKS
                            + "-tick despawn"));
            double drift = distance(before, after);
            ctx.check(drift <= MAX_DRIFT_BLOCKS,
                    "P6: the validated item drifted " + String.format(java.util.Locale.ROOT,
                            "%.3f", drift) + " blocks, over the " + MAX_DRIFT_BLOCKS
                            + "-block bound the pin's hysteresis band makes true");

            long age = itemAge(rcon).orElse(Long.MAX_VALUE);
            ctx.check(age < VANILLA_DESPAWN_TICKS,
                    "P6: the item's Age is " + age + " ticks — the pin never reset its lifetime, so"
                            + " it survived by luck rather than by being pinned");

            // The credit. Standing on it is the only way a player takes an item.
            rcon.require("tp " + TENANT + " " + after[0] + " " + after[1] + " " + after[2]);
            ctx.settle(Duration.ofSeconds(10));

            int credited = credited(rcon);
            ctx.check(credited == 1,
                    "P6: the tenant holds " + credited + " " + PINNED_ITEM
                            + " after picking up one validated item — the clause is EXACTLY once,"
                            + " and 0 is a lost item while 2 is a duplicated one");
            ctx.check(itemPosition(rcon).isEmpty(),
                    "P6: the projection is still in the world after being credited");
        } finally {
            bot.interrupt();
            bot.join(Duration.ofSeconds(20).toMillis());
        }
    }

    /** The unmodified client, held online on its own thread while RCON drives the world. */
    private static Thread startTenant(ScenarioContext ctx, ServerEndpointSupport server) {
        ServerVanillaBot tenant = new ServerVanillaBot(
                "127.0.0.1", ctx.topology().gamePort(), TENANT,
                ServerEndpointSupport.MINECRAFT_PROTOCOL,
                ctx.topology().scaled(Duration.ofSeconds(60)),
                ctx.stack().logDir().resolve("vanilla-bot-p6.log"));
        Thread thread = new Thread(() -> {
            try {
                tenant.run(java.util.List.of("wait:joined",
                        "hold:" + (HOLD_WINDOW.toSeconds() + 120), "quit"));
            } catch (RuntimeException stopped) {
                // The stage tears the bot down when it is finished with it; a socket closed under
                // it is the expected end of this thread, not a failure of the stage.
                ctx.note("P6: the tenant connection ended: " + stopped);
            }
        }, "p6-tenant");
        thread.setDaemon(true);
        thread.start();
        // The plugin is not consulted about the bot's presence; the server is, over RCON.
        ctx.note("P6: tenant " + TENANT + " dialling " + ctx.topology().gamePort()
                + " with nothing installed (endpoint " + server.endpointControlPort() + ")");
        return thread;
    }

    /** The nearest item entity's position, or empty when there is no item in the world. */
    private static java.util.Optional<double[]> itemPosition(RconClient rcon) {
        return rcon.send("execute as @e[type=item,limit=1] run data get entity @s Pos")
                .flatMap(EndpointScenario::parseVector);
    }

    /** The nearest item entity's Age in ticks. */
    private static java.util.Optional<Long> itemAge(RconClient rcon) {
        return rcon.send("execute as @e[type=item,limit=1] run data get entity @s Age")
                .flatMap(reply -> {
                    var matcher = SHORT_VALUE.matcher(reply);
                    return matcher.find()
                            ? java.util.Optional.of(Long.parseLong(matcher.group(1)))
                            : java.util.Optional.<Long>empty();
                });
    }

    /** How many {@link #PINNED_ITEM} the tenant holds — counted, never removed. */
    private static int credited(RconClient rcon) {
        String reply = rcon.require("clear " + TENANT + " " + PINNED_ITEM + " 0");
        var matcher = FOUND_COUNT.matcher(reply);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static java.util.Optional<double[]> parseVector(String reply) {
        var matcher = VECTOR.matcher(reply);
        if (!matcher.find()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new double[] {
                Double.parseDouble(matcher.group(1)),
                Double.parseDouble(matcher.group(2)),
                Double.parseDouble(matcher.group(3))});
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Whether the endpoint's worker reports {@link #WORLD_ID} among the worlds it holds. */
    private static boolean hostsWorld(ServerEndpointSupport server) {
        return server.endpoint().ask("NODERA-STATE 2")
                .map(state -> state.contains("\"world_id\":\"" + WORLD_ID + "\""))
                .orElse(false);
    }
}
