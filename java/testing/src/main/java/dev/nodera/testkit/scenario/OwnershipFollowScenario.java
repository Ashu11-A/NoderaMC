package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.mc.RconClient;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Ownership follows the player.
 *
 * <p>The regression this pins: region ownership used to be planned once, from the positions players
 * held when the last of them joined, because the re-plan key hashed only (player → node) pairs.
 * Walking then took you out of the regions you owned and the ground under your feet read FOREIGN
 * (owned by whoever was nearest at join time) or UNASSIGNED (never claimed), and the live lane could
 * disagree with what other players' clients had derived.
 *
 * <ol>
 *   <li>W0 clean-slate dedicated server plus BOTH players, entity lane live. The second player is
 *       parked far away so the two field-of-view discs cannot overlap: the drive must measure the
 *       walking player's own ownership, not a tie broken by whoever happens to stand nearer to
 *       spawn.</li>
 *   <li>W1 baseline: the PLAYER's own client lane owns a non-empty region set.</li>
 *   <li>W2 THE DRIVE: the player is teleported thousands of blocks away, crossing many region
 *       boundaries. Within the movement re-plan window the session must re-plan AND rebroadcast, and
 *       the player's client must re-derive a fresh region set — the ownership followed the player
 *       instead of staying frozen at the join position.</li>
 *   <li>W3 the player really changed region (the teleport landed).</li>
 *   <li>W4 no errors accumulated across the re-plan swaps, and the parked player still owns its own
 *       far-away regions (one player moving must not strip another's ownership).</li>
 * </ol>
 *
 * <p>Topology note: ownership belongs to PLAYERS, not to the session server. On a dedicated server
 * the server's own node holds no {@code PlayerView}, so once players announce their nodes it
 * correctly owns zero regions and {@code /nodera regions} (which answers for the server node) reads
 * empty — that is the no-host model working, not a blind panel. The players' real ownership is their
 * client lanes, which is what this scenario asserts on.
 *
 * <p>Thread-context: run on the runner's thread; stateless between runs.
 */
public final class OwnershipFollowScenario implements Scenario {

    /** Thousands of blocks = dozens of 128-block regions. */
    private static final int FAR_X = 6000;
    private static final int FAR_Z = 6000;

    /**
     * Player two's parking spot: about 39 regions away, far past any field-of-view-disc overlap (a
     * region is 512 blocks, a render-distance disc a few hundred).
     *
     * <p>The park used to be 500 000, which is a MINUTES-long chunk generation: the server thread
     * stalls, the connected clients time out and are disconnected, and the next command fails with
     * "No entity was found" — the suite blaming a feature for its own setup. Distance was never the
     * point here; non-overlap was.
     */
    private static final int PARK_X = -20_000;
    private static final int PARK_Z = -20_000;

    @Override
    public String id() {
        return "ownership-follow";
    }

    @Override
    public String title() {
        return "region ownership follows a walking player instead of freezing at its join position";
    }

    @Override
    public Set<String> tags() {
        return Set.of("live", "ownership");
    }

    @Override
    public Requirements requirements() {
        return Requirements.liveClients(5);
    }

    @Override
    public void run(ScenarioContext context) throws Exception {
        LiveStack stack = context.stack();
        RconClient rcon = stack.rcon();
        LogWatcher serverLog = context.log("server.log");
        LogWatcher joinLog = context.log("client-join.log");
        Path serverLogFile = stack.logDir().resolve("server.log");
        Path joinLogFile = stack.logDir().resolve("client-join.log");
        Path joinTwoLogFile = stack.logDir().resolve("client-join2.log");
        int[] serverMark = new int[1];
        int[] clientMark = new int[1];

        context.stage("W0", "both players are in-world with the discs parked apart", () -> {
            HostWorldSupport.dedicatedTwoPlayers(context);
            context.note("parking JoinerTwo at (" + PARK_X + ", " + PARK_Z
                    + ") so the FOV discs cannot overlap");
            rcon.require("gamemode creative JoinerTwo");
            rcon.teleport("JoinerTwo", PARK_X, 200, PARK_Z);
            // The park is itself a move: let its re-plan land before the baseline is read.
            context.settle(Duration.ofSeconds(12));
        });

        context.stage("W1", "baseline: the walking player's own client lane owns regions", () -> {
            rcon.require("gamemode creative JoinerDev");
            Optional<String> lane = HostWorldSupport.lastMatchAfter(joinLogFile, 0,
                    "client validation lane active");
            HostWorldSupport.transcript(context, "ownership.log",
                    "=== baseline client lane\n" + lane.orElse("<none>") + "\n");
            OptionalInt owned = HostWorldSupport.laneRegions(lane.orElse(null));
            context.check(owned.isPresent() && owned.getAsInt() > 0,
                    "the player's client lane owns no regions at baseline (got '"
                            + lane.orElse("none") + "')");
            HostWorldSupport.transcript(context, "ownership.log", "=== baseline position: "
                    + rcon.send("data get entity JoinerDev Pos").orElse(""));
            context.note("baseline — the player owns " + owned.getAsInt() + " region(s)");
        });

        context.stage("W2", "ownership followed the player: the session re-planned and the client "
                + "re-derived its regions at the new position", () -> {
            serverMark[0] = serverLog.lineCount();
            clientMark[0] = joinLog.lineCount();
            rcon.teleport("JoinerDev", FAR_X, 200, FAR_Z);
            // The movement check runs once a second behind a 5 s cooldown, then the lane reopens and
            // the fresh plan is broadcast asynchronously; allow generous room on a loaded machine.
            //
            // THE assertion: a re-plan fires for the new position AND the player's client re-derives
            // its ownership there. Both must be lines that did not exist before the teleport — hence
            // the mark, never a bare wait against the pre-teleport line count (which would re-match
            // the activation already sitting in the log and prove nothing).
            serverLog.awaitAfter("member node(s)", Duration.ofSeconds(150), serverMark[0]);
            joinLog.awaitAfter("client validation lane active", Duration.ofSeconds(150),
                    clientMark[0]);
            Optional<String> lane = HostWorldSupport.lastMatchAfter(joinLogFile, 0,
                    "client validation lane active");
            HostWorldSupport.transcript(context, "ownership.log",
                    "=== post-move client lane\n" + lane.orElse("<none>") + "\n");
            OptionalInt owned = HostWorldSupport.laneRegions(lane.orElse(null));
            context.check(owned.isPresent() && owned.getAsInt() > 0,
                    "the player owns no regions after moving (got '" + lane.orElse("none") + "')");
            context.note("the client re-derived " + owned.getAsInt()
                    + " region(s) at the new position");
        });

        context.stage("W3", "the teleport really moved the player across regions", () -> {
            String position = rcon.send("data get entity JoinerDev Pos").orElse("");
            HostWorldSupport.transcript(context, "ownership.log",
                    "=== post-move position: " + position);
            context.check(HostWorldSupport.positionWithin(position, FAR_X, FAR_Z, 32),
                    "the teleport did not land: " + position);
        });

        context.stage("W4", "the re-plans left no errors and the parked player kept its regions",
                () -> {
            HostWorldSupport.requireNoErrors(serverLogFile, serverMark[0],
                    "W4: error lines during the re-plans");
            context.check(!HostWorldSupport.containsAfter(serverLogFile, 0,
                            "Exception in server tick loop"),
                    "the movement re-plan crashed the server tick loop");
            // One player's re-plan must not strip the other's ownership: the parked player's own
            // client lane still has to hold a non-empty region set afterwards.
            Optional<String> parked = HostWorldSupport.lastMatchAfter(joinTwoLogFile, 0,
                    "client validation lane active");
            HostWorldSupport.transcript(context, "ownership.log",
                    "=== parked player's client lane\n" + parked.orElse("<none>") + "\n");
            OptionalInt parkedOwned = HostWorldSupport.laneRegions(parked.orElse(null));
            context.check(parkedOwned.isPresent() && parkedOwned.getAsInt() > 0,
                    "the parked player lost its regions when the other player moved (got '"
                            + parked.orElse("none") + "')");
            context.note("the parked player still owns " + parkedOwned.getAsInt() + " region(s)");
        });

        stack.collectWorkerState();
    }
}
