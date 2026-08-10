package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.harness.PlayerRole;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.nio.file.Files;
import java.time.Duration;
import java.util.Set;

/**
 * The world-continuity series: a shared world survives the player who was hosting it.
 *
 * <p>The procedure under test — the acceptance the headless {@code WorldContinuityIT} rehearses
 * Minecraft-free:
 *
 * <ol>
 *   <li>S1 player A shares a world on the Nodera network (tracker + rendezvous).</li>
 *   <li>S2 player B joins it — two real clients, each backed by its OWN peer worker.</li>
 *   <li>S2d player B's peer bring-up never held its client thread for more than a frame
 *       (MC-JOIN-2) — the join is the one lifecycle path that used to freeze a client outright.</li>
 *   <li>S3 the world's data enters the Nodera network (archive pieces on worker A).</li>
 *   <li>S4 player A disconnects (client killed).</li>
 *   <li>S5 PASS: player B's client recovers the world from the peer network and re-opens/re-shares
 *       it (the continuity rehost). FAIL: player B ends at the title screen with no recovery — i.e.
 *       the world died with its host.</li>
 * </ol>
 *
 * <p>THIS SCENARIO ALSO BAKES the shared {@code NoderaE2E} world every other host-client scenario
 * reuses (S1a) — it is the one to run first on a fresh checkout.
 *
 * <p>Topology: the standard one — 2 players, 1 tracker, 1 rendezvous, 3 headless peers (a companion
 * per player plus the spare that keeps the swarm at the quorum floor).
 *
 * <p>Thread-context: run on the runner's thread; stateless between runs.
 */
public final class ContinuityScenario implements Scenario {

    /**
     * The client-thread cost of the session hand-off, as the mod itself reports it
     * (`ModNetworking.handleSessionOnClient`). Pinned here because the two files are a pair: reword
     * the log line and this stage becomes a timeout that blames the behaviour.
     */
    private static final java.util.regex.Pattern HAND_OFF_MICROS =
            java.util.regex.Pattern.compile("session hand-off took (\\d+) us");

    /** One server tick at 20 TPS. The budget MC-JOIN-2's exit test names, in the line's own unit. */
    private static final long ONE_FRAME_MICROS = 50_000L;

    @Override
    public String id() {
        return "continuity";
    }

    @Override
    public String title() {
        return "a shared world survives the player who was hosting it";
    }

    @Override
    public Set<String> tags() {
        return Set.of("live", "continuity");
    }

    @Override
    public Requirements requirements() {
        // A GUI session for the two real clients, and about six gigabytes of free RAM: two full
        // Minecraft clients in one machine's memory is the real constraint of this scenario.
        return Requirements.liveClients(6);
    }

    @Override
    public void run(ScenarioContext context) throws Exception {
        LiveStack stack = context.stack();
        LogWatcher hostLog = context.log("client-host.log");
        LogWatcher joinLog = context.log("client-join.log");
        // A one-element holder rather than a field: the runner may execute this instance more than
        // once in a queue, so nothing about a run may survive on the scenario itself.
        HostWorldSupport.HostedPair[] players = new HostWorldSupport.HostedPair[1];

        context.stage("S0", "the tracker, the rendezvous and every peer answer", () ->
                HostWorldSupport.probeWorkers(context));

        context.stage("S1a", "the shared world NoderaE2E is staged for the host client", () ->
                HostWorldSupport.stagedWorld(context));

        context.stage("S1b", "player A is hosting NoderaE2E on the network", () -> {
            // The host client's own latest.log is removed first: the wait below is over evidence
            // that must be produced by THIS run, and a previous run's line would satisfy it.
            Files.deleteIfExists(stack.paths().gameLog("run-host"));
            players[0] = HostWorldSupport.hostedTwoPlayers(context);
            hostLog.await("Nodera: sharing world", Duration.ofSeconds(600));
        });

        context.stage("S3", "the world archive is on the Nodera network (worker A seeding)", () -> {
            hostLog.await("world archive seeded to the worker", Duration.ofSeconds(180));
            String state = context.worker(PlayerRole.PLAYER_ONE).state();
            context.checkAbsent(state, "\"maintained_pieces\":0,",
                    "host worker STATE reports zero maintained pieces");
            context.checkAbsent(state, "\"connected_worlds\":[]",
                    "host worker is not listing the world");
        });

        context.stage("S2", "player B is in the world (two players, every peer up)", () ->
                context.check(hostLog.contains("JoinerDev joined the game"),
                        "the host never saw player B join"));

        context.stage("S2c", "region ownership is per-player: player B re-executes and votes "
                + "for its own regions", () -> {
            // A world baked before a FlatWorldRules.RULES_VERSION bump carries a certified genesis
            // the current engine refuses, so the lane never boots and this wait would burn its whole
            // timeout with no stated cause — bail out the moment the bootstrap failure appears.
            HostWorldSupport.awaitMemberNodes(context, hostLog, "S2c");
            joinLog.await("client validation lane active", Duration.ofSeconds(180));
        });

        context.stage("S2d", "player B's peer bring-up never blocked its client thread", () -> {
            // MC-JOIN-2. The exit test is a claim about a THREAD — "the client never blocks for
            // more than one frame" — so it is measured on that thread and nowhere else: the mod
            // times its own session hand-off inside `IPayloadContext.enqueueWork`, which IS a frame
            // of the client's loop, and prints the microseconds it held it. Everything the join
            // actually costs (the companion delegation exchange, the relay reservation across every
            // configured endpoint at 5 s connect / 10 s read, the ephemeral bind, the bootstrap
            // dial) happens afterwards on `nodera-client-bringup`, where nothing is waiting.
            //
            // Before the fix this one line carried the whole bring-up, because there was no second
            // thread to carry it.
            //
            // HARNESS-GAP: what this stage does NOT yet reproduce is the row's own relay set. The
            // mod follows the WORKER's live rendezvous selection when it has one
            // (`NoderaPeerService.selectedRendezvousRoutes`), so black-holing only the client's
            // `nodera-client.toml` proves nothing — the joiner's worker must be started with an
            // unroutable `NODERA_RENDEZVOUS_ENDPOINTS` too, and `LiveStack.startWorkers` gives
            // every worker the same list. Until it can be set per role, this stage bounds the
            // client thread on the reachable topology, which still fails on the pre-fix code.
            joinLog.await("session hand-off took", Duration.ofSeconds(180));
            String micros = joinLog.lastCapture(HAND_OFF_MICROS).orElse("");
            context.check(!micros.isBlank(),
                    "player B's client never reported how long the session hand-off held its "
                            + "client thread");
            long held = Long.parseLong(micros);
            context.check(held <= ONE_FRAME_MICROS,
                    "player B's client thread was held for " + held + " us by the session "
                            + "hand-off; one tick at 20 TPS is " + ONE_FRAME_MICROS + " us");
            context.note("the session hand-off held player B's client thread for " + held + " us");
            // And the peer really did come up: an assertion on speed alone is satisfied by a
            // hand-off that did nothing at all.
            joinLog.await("Nodera client peer joined session via", Duration.ofSeconds(180));
        });

        context.stage("S4", "player A is gone", () ->
                // Only the game goes: player A's companion worker deliberately stays up, because
                // the world's data has to outlive the game that published it.
                HostWorldSupport.stopClient(players[0].host(), "clientHostRunProgramArgs"));

        context.stage("S5", "player B recovered the world from the network and re-hosts it", () -> {
            joinLog.await("Nodera: the host's connection ended", Duration.ofSeconds(120));
            joinLog.await("restored to saves/", Duration.ofSeconds(300));
            joinLog.await("Nodera: sharing world", Duration.ofSeconds(300));
            String state = context.worker(PlayerRole.PLAYER_TWO).state();
            context.checkAbsent(state, "\"connected_worlds\":[]",
                    "worker B is not hosting the recovered world");
        });

        stack.collectWorkerState();
    }
}
