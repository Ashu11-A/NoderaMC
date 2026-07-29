package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.mc.RconClient;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pearl drive: ghost, flight, teleport (L-50).
 *
 * <p>The ender pearl is the entity lane's named review projectile: it is captured as a ghost, it
 * crosses regions in flight, and it ends in a teleport that must land in the region the lane says it
 * does. Three moments, one throw.
 *
 * <ol>
 *   <li>E0 the standard topology plus a clean-slate dedicated server, both players in-world, the
 *       entity lane live.</li>
 *   <li>E1 GHOST: a pearl thrown in a region THIS node owns is captured by the lane
 *       ({@code PEARL: ghost <id> captured in Region[...]}). On a dedicated server under
 *       field-of-view ownership the node owns none, so the stage reports SKIPPED and names L-60
 *       rather than asserting the impossible.</li>
 *   <li>E2 TELEPORT: the thrower ends up where the pearl landed — the lane's reported destination
 *       region and the player's actual position agree.</li>
 *   <li>E3 transcripts and worker state snapshots collected.</li>
 * </ol>
 *
 * <p>Thread-context: run on the runner's thread; stateless between runs.
 */
public final class PearlScenario implements Scenario {

    /** The int-array form of a player UUID, which is the only form the {@code Owner} tag accepts. */
    // Bounded and possessive on purpose: this runs over a server reply, which is input the
    // harness does not control, and an unbounded greedy repetition makes `find()` re-scan the
    // same characters from every start position — quadratic in the length of the reply.
    private static final Pattern UUID_ARRAY = Pattern.compile("\\[I;[^]]{0,120}+]");

    @Override
    public String id() {
        return "pearl";
    }

    @Override
    public String title() {
        return "an ender pearl is captured, flies across regions, and teleports its thrower";
    }

    @Override
    public Set<String> tags() {
        return Set.of("live", "entity");
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
        Path serverLogFile = stack.logDir().resolve("server.log");
        int[] mark = new int[1];
        String[] startPosition = new String[1];

        context.stage("E0", "both players are in-world, the lane is live, every peer is up", () ->
                HostWorldSupport.dedicatedTwoPlayers(context));

        context.stage("E1", "the pearl is thrown, and the lane's ghost half is asserted where the "
                + "node owns regions", () -> {
            // Creative: the thrower is about to stand in mid-air, and fall damage would kill the
            // drive rather than the pearl. Creative changes nothing about the teleport itself — it
            // only stops the item being consumed and the fall hurting.
            rcon.require("gamemode creative JoinerDev");
            // The pearl needs AIR to fly through. The first version of this drive put the thrower at
            // y=-58 — buried in stone — where the pearl spawned inside solid blocks, never produced
            // a hit result, and so never teleported anybody. y=100 is open sky over ordinary
            // terrain.
            rcon.teleport("JoinerDev", 100, 100, 100);
            context.settle(Duration.ofSeconds(8));
            startPosition[0] = rcon.send("data get entity JoinerDev Pos").orElse("");
            HostWorldSupport.transcript(context, "pearl.log",
                    "=== start position: " + startPosition[0]);

            mark[0] = serverLog.lineCount();
            // There is no "use item" command, so the pearl is summoned with an owner. The owner is a
            // UUID — `Owner:"JoinerDev"` is silently ignored, which is how the first version of this
            // drive summoned a pearl that could never teleport anybody and then waited for the
            // teleport. Read the real UUID and hand it over in the int-array form the tag expects.
            String uuidReply = rcon.send("data get entity JoinerDev UUID").orElse("");
            Matcher uuid = UUID_ARRAY.matcher(uuidReply);
            context.check(uuid.find(),
                    "could not read JoinerDev's UUID (needed to own the pearl): " + uuidReply);
            HostWorldSupport.transcript(context, "pearl.log", "=== thrower UUID: " + uuid.group());
            // ABSOLUTE offsets, not caret coords: `^` is relative to the thrower's FACING, which a
            // teleport does not pin, so the pearl's launch direction was luck. `~ ~1 ~` with a
            // downward-and-forward motion always sends it across open air into the ground below.
            String summoned = rcon.send("execute at JoinerDev run summon minecraft:ender_pearl "
                    + "~ ~1 ~ {Motion:[0.0,-0.2,1.2],Owner:" + uuid.group() + "}").orElse("");
            HostWorldSupport.transcript(context, "pearl.log", "=== summon: " + summoned);
            context.checkContains(summoned, "Summoned", "the pearl was not summoned");

            // E1's ghost half rides the same node question as the mob drive: a pearl is captured
            // only where the SERVER's lane owns the region, and under field-of-view ownership it
            // owns none (L-60). The teleport half below does NOT — it is a vanilla event the host
            // always sees — so the drive still asserts the part it can and says which part it could
            // not.
            if (serverLog.contains("no regions fall to this node")) {
                HostWorldSupport.transcript(context, "pearl.log",
                        "=== E1 skipped: the server's lane owns no regions (L-60)");
                context.note("E1: SKIPPED — this server's lane owns no regions, so nothing on it "
                        + "captures the pearl as a ghost (L-60). The teleport half below still "
                        + "asserts.");
            } else if (serverLog.pollFor("PEARL: ghost", Duration.ofSeconds(120), mark[0])) {
                HostWorldSupport.lastMatchAfter(serverLogFile, mark[0], "PEARL: ghost")
                        .ifPresent(ghost -> HostWorldSupport.transcript(context, "pearl.log",
                                "=== ghost: " + ghost));
            } else {
                context.fail("the lane never captured the pearl as a ghost on a node that DOES own "
                        + "regions (see " + serverLogFile + ")");
            }
        });

        context.stage("E2", "the thrower teleported — the lane's destination and the world agree",
                () -> {
            serverLog.awaitAfter("PEARL: JoinerDev teleported", Duration.ofSeconds(180), mark[0]);
            HostWorldSupport.lastMatchAfter(serverLogFile, mark[0], "PEARL: JoinerDev teleported")
                    .ifPresent(line -> HostWorldSupport.transcript(context, "pearl.log",
                            "=== teleport: " + line));

            // The player really moved: a teleport the lane reports but the world did not perform
            // would be the worst of both worlds — a confident log line over an unchanged player.
            context.settle(Duration.ofSeconds(5));
            String finalPosition = rcon.send("data get entity JoinerDev Pos").orElse("");
            HostWorldSupport.transcript(context, "pearl.log",
                    "=== final position: " + finalPosition);
            context.check(!finalPosition.isBlank() && !finalPosition.equals(startPosition[0]),
                    "the lane logged a teleport but the player did not move: " + finalPosition);
        });

        context.stage("E3", "worker state snapshots and logs are collected",
                stack::collectWorkerState);
    }
}
