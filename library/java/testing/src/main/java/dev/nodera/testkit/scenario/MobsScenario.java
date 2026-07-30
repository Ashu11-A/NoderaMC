package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.mc.RconClient;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The ghost-mob lane: capture where it is allowed, revoke where it is not (L-50).
 *
 * <ol>
 *   <li>G0 the standard topology plus a clean-slate dedicated server with both players in-world and
 *       the entity lane live. {@code mobCaptureDimensions} is the standard staging: the OVERWORLD
 *       opts in, nothing else does.</li>
 *   <li>G1 CAPTURE: summon mobs at a player's feet in a delegated overworld region. The exit is that
 *       a region reports it now holds ghost mobs and is NOT revoked — a captured ghost is the lane
 *       holding a mob, not giving up on one.</li>
 *   <li>G2 Into the NETHER, which opted nothing in, for the two halves that only separate there.
 *       G2a: the ZOMBIE is captured anyway, because the engine owns that species' behaviour — the
 *       per-species default (L-24). G2b: a CREEPER is not, so the node that SEES it refuses the
 *       region, names the reason, and announces the refusal to the mesh whether or not it holds a
 *       seat (L-60). The world keeps playing.</li>
 *   <li>G3 transcripts and worker state snapshots collected.</li>
 * </ol>
 *
 * <p>Why the nether rather than a config flip and a restart: capture is decided per DIMENSION, so
 * the un-opted-in dimension is a revocation trigger that costs one teleport instead of two client
 * boots.
 *
 * <p>Thread-context: run on the runner's thread; stateless between runs.
 */
public final class MobsScenario implements Scenario {

    /** The engine-owned species: its behaviour originates in {@code MobAiRules}/{@code MobCombatRules}. */
    private static final String MOB = "minecraft:zombie";

    /**
     * A species the engine does NOT own.
     *
     * <p>The zombie is captured anywhere (L-24's default), so using it for the revocation stage would
     * prove nothing — there would be nothing non-delegable about it.
     */
    private static final String MOB_UNKNOWN = "minecraft:creeper";

    private static final int MOB_COUNT = 3;

    /** A first-capture line that names a hostile rather than an ambient animal. */
    private static final Pattern HOSTILE_GHOST =
            Pattern.compile("GHOST:.*minecraft:(zombie|skeleton|creeper|spider)");

    @Override
    public String id() {
        return "mobs";
    }

    @Override
    public String title() {
        return "the ghost lane captures where a dimension opted in and refuses where it did not";
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
        int[] netherMark = new int[1];

        context.stage("G0", "both players are in-world, the lane is live, every peer is up", () ->
                HostWorldSupport.dedicatedTwoPlayers(context));

        context.stage("G1", "ghost capture: the lane controls the mobs and keeps its region", () -> {
            rcon.require("gamemode creative JoinerDev");
            int mark = serverLog.lineCount();
            for (int i = 0; i < MOB_COUNT; i++) {
                summonAt(context, rcon, "JoinerDev", MOB);
            }

            // The evidence is the lane's own line, not a counter. `nodera entities` reports the node
            // the COMMAND ran on — the server — and under field-of-view ownership the regions belong
            // to the PLAYERS' nodes, so the server's total is legitimately 0 while capture is
            // working perfectly. That is what made the first two runs of this suite disagree with
            // each other (163, then 0).
            //
            // The line is emitted once per region, the first time that region holds a ghost — and a
            // live world is full of cows and chickens, so regions announce as soon as the lane goes
            // live, seconds before any summon lands. So the assertion is over the WHOLE log, not the
            // window after the summons: what it proves is that capture runs in this dimension at
            // all. What the summons then have to prove is the opposite of revocation — that adding
            // mobs where capture is enabled does not cost the lane a region.
            serverLog.await("GHOST:", Duration.ofSeconds(240));
            List<String> ghosts = HostWorldSupport.matchesAfter(serverLogFile, 0, "GHOST:");
            ghosts.subList(Math.max(0, ghosts.size() - 5), ghosts.size())
                    .forEach(line -> HostWorldSupport.transcript(context, "mobs.log", line));
            // Hostiles specifically: a lane that only ever captured passive animals would satisfy
            // the line above and still be broken for the mobs that matter. A note, not an assertion
            // — regions announce on their first ghost, which is often an ambient animal.
            if (ghosts.stream().noneMatch(line -> HOSTILE_GHOST.matcher(line).find())) {
                context.note("G1: note — no hostile named in a first-capture line (regions announce "
                        + "on their first ghost, which is often an ambient animal)");
            }

            // Capture and revocation are opposites: a revoke in the window after the summons would
            // mean the lane dropped the very region it was supposed to be holding.
            context.check(!HostWorldSupport.containsAfter(serverLogFile, mark,
                            "entity lane revoked"),
                    "the lane REVOKED a region in a dimension where capture is enabled");
        });

        context.stage("G2a", "the engine-owned species is captured with no dimension opt-in (L-24)",
                () -> {
            // This used to skip. Revocation was gated on `runtime.delegated(region)` — the node's
            // OWN lane — and under field-of-view ownership a dedicated server owns nothing ("no
            // regions fall to this node"), so the one node that could see the mob was the one node
            // forbidden from acting on it. L-60's fix evaluates the dimension's opt-in BEFORE the
            // ownership gate and announces a `RegionRefusal` (tag 61), so the observer refuses and
            // the owners drop the region. The assertion is therefore the same on every topology,
            // which is what makes it worth running.
            netherMark[0] = serverLog.lineCount();
            rcon.teleport("JoinerDev", 0, 100, 0, "minecraft:the_nether");
            context.settle(Duration.ofSeconds(15));  // let the nether chunks + the re-plan settle

            // G2a FIRST, and the order is load-bearing: a refusal is permanent for the region, so
            // proving the species default after refusing the region would prove nothing.
            int mark = serverLog.lineCount();
            summonAt(context, rcon, "JoinerDev", MOB);
            context.settle(Duration.ofSeconds(10));
            // The claim is about the ZOMBIE, not about silence. The nether is full of striders,
            // magma cubes and piglins — every one a species the engine does not own — so revocations
            // here are expected and correct; what must never happen is one that names the
            // engine-owned species. Asserting "no revocation at all" would make this stage fail for
            // the lane working properly on somebody else.
            boolean revokedTheZombie = HostWorldSupport
                    .matchesAfter(serverLogFile, mark, "entity lane revoked").stream()
                    .anyMatch(line -> line.contains(MOB));
            context.check(!revokedTheZombie, "a " + MOB + " was treated as non-delegable in a "
                    + "dimension that opted nothing in — the per-species default (L-24) is not "
                    + "being honoured");
            HostWorldSupport.transcript(context, "mobs.log",
                    "=== G2a: " + MOB + " captured in the nether on the species default alone");
        });

        context.stage("G2b", "the region is released, the reason is named, the refusal reaches the "
                + "mesh, and the session plays on", () -> {
            summonAt(context, rcon, "JoinerDev", MOB_UNKNOWN);

            // The mark is the NETHER ENTRY, not the summon, and the creeper is not required to be
            // the entity named in the refusal. Both were wrong before, and a live run showed why:
            // the nether is full of striders, magma cubes and piglins, every one of them a species
            // the engine does not own, so the region is refused seconds after the player arrives —
            // correctly, and by the same rule this stage is about. A refusal is permanent per
            // region, so by the time the creeper lands there is nothing left to announce. Asserting
            // on the creeper specifically was asserting that it won a race against the nether's own
            // mobs.
            if (!serverLog.pollFor("entity lane revoked", Duration.ofSeconds(180), netherMark[0])) {
                // Self-diagnosing: the summon is proven above, so a missing revoke is genuinely the
                // lane's silence. Say WHICH silence it is — the three candidates read very
                // differently.
                HostWorldSupport.transcript(context, "mobs.log",
                        "=== G2b: no revoke within the window. What the log did show:");
                HostWorldSupport.transcript(context, "mobs.log",
                        "--- lane activity since the nether entry ---");
                List<String> since = HostWorldSupport.readLines(serverLogFile);
                List<String> laneActivity = since
                        .subList(Math.min(netherMark[0], since.size()), since.size()).stream()
                        .filter(line -> line.contains("GHOST:") || line.contains("entity lane")
                                || line.contains("REGION:"))
                        .toList();
                tail(laneActivity, 20)
                        .forEach(line -> HostWorldSupport.transcript(context, "mobs.log", line));
                HostWorldSupport.transcript(context, "mobs.log",
                        "--- did the nether region ever appear? ---");
                tail(HostWorldSupport.matchesAfter(serverLogFile, 0, "the_nether"), 10)
                        .forEach(line -> HostWorldSupport.transcript(context, "mobs.log", line));
                context.fail(MOB_UNKNOWN + " was summoned but no region was refused — see the lane "
                        + "transcript in " + stack.resultsDir().resolve("mobs.log")
                        + " (the summon itself is asserted, so this is the lane's silence, not a "
                        + "missing mob)");
            }

            String revoked = HostWorldSupport
                    .lastMatchAfter(serverLogFile, netherMark[0], "entity lane revoked").orElse("");
            HostWorldSupport.transcript(context, "mobs.log", "=== revocation: " + revoked);
            context.checkContains(revoked, "non-delegable entity",
                    "the region revoked without stating the reason");
            context.checkContains(revoked, "minecraft:",
                    "the revocation names no species at all");
            // Revoking is a controlled retreat, not a failure: the server keeps running and the
            // player keeps playing. A revoke that takes the session down would be worse than never
            // delegating at all.
            String alive = rcon.send("list").orElse("");
            context.checkContains(alive, "JoinerDev", "the player is gone after the revocation");
            HostWorldSupport.requireNoErrors(serverLogFile, netherMark[0],
                    "G2: the revocation left errors in the log");
            // L-60: the whole point is that the refusal LEAVES this node. A revoke that stopped at
            // the observer would leave every owning lane still validating the region it just
            // refused.
            context.checkContains(revoked, "refusal announced to the mesh",
                    "the region was revoked locally but no refusal was announced");
        });

        context.stage("G3", "worker state snapshots and logs are collected",
                stack::collectWorkerState);
    }

    /**
     * Summon a mob and PROVE it happened.
     *
     * <p>The reply used to go to {@code /dev/null}, so "the lane did not react" and "there was
     * nothing to react to" were the same observation. That is exactly how G2 failed for nine minutes
     * with no zombie in the world at all: the whole stage rested on a command nobody checked.
     * Minecraft answers "Summoned new Zombie", or an error naming the reason.
     */
    private void summonAt(ScenarioContext context, RconClient rcon, String who, String species) {
        String reply = rcon.send("execute at " + who + " run summon " + species + " ~ ~ ~")
                .orElse("");
        HostWorldSupport.transcript(context, "mobs.log",
                "=== summon " + species + " at " + who + ": "
                        + (reply.isBlank() ? "<no reply>" : reply));
        context.checkContains(reply, "Summoned new",
                "the summon of " + species + " at " + who + " did not happen");
    }

    private static List<String> tail(List<String> lines, int count) {
        return lines.subList(Math.max(0, lines.size() - count), lines.size());
    }
}
