package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.LogWatcher;
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
 * </pre>
 *
 * <p>What this scenario does NOT assert yet, deliberately: nothing about validation, hosting or
 * capture beyond L-71's own claim. Those are server tasks 2 and 3, and each stage lands with the
 * capability rather than ahead of it.
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

            ctx.stack().collectArtefacts();
        }
    }

    /** Whether the endpoint's worker reports {@link #WORLD_ID} among the worlds it holds. */
    private static boolean hostsWorld(ServerEndpointSupport server) {
        return server.endpoint().ask("NODERA-STATE 2")
                .map(state -> state.contains("\"world_id\":\"" + WORLD_ID + "\""))
                .orElse(false);
    }
}
