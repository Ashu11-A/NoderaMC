package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.mc.RconClient;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.time.Duration;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The clean-slate validated pickup drive (issue #33 / L-50).
 *
 * <ol>
 *   <li>P0 the standard topology, CLEAN SLATE: the dedicated server world is wiped so the first
 *       pickup is the first action ever on a fresh store.</li>
 *   <li>P1 the dedicated server boots, auto-shares, and the entity lane is armed.</li>
 *   <li>P2 both players join and the entity lane goes live. JoinerTwo is parked far away: the pickup
 *       must be adjudicated by JoinerDev's own region committee, and no second player may be near
 *       enough to race the item.</li>
 *   <li>P3 THE DRIVE: summon a three-stone item at JoinerDev's feet. The exactly-once exit is that
 *       the stack lands in THAT player's inventory with count EXACTLY 3 (no vanish, no dupe), stays
 *       3, and never appears in the parked player's inventory.</li>
 * </ol>
 *
 * <p>The historical failure this asserts against: on a fresh store the pickup committed but the item
 * neither landed as an inventory credit nor fell back to vanilla delivery — it vanished
 * ({@code docs/minecraft/LIMITATIONS.md} L-50).
 *
 * <p>Thread-context: run on the runner's thread; stateless between runs.
 */
public final class PickupScenario implements Scenario {

    /**
     * The parked player's spot: about 39 regions away, far past any field-of-view-disc overlap (a
     * region is 512 blocks, a render-distance disc a few hundred).
     *
     * <p>The park used to be 500 000, which is a MINUTES-long chunk generation: the server thread
     * stalls, the connected clients time out and are disconnected, and the next command fails with
     * "No entity was found" — the suite blaming a feature for its own setup. Distance was never the
     * point here; non-overlap was.
     */
    private static final int PARK_X = -20_000;
    private static final int PARK_Z = -20_000;

    /** The {@code count: N} of the first stack in a {@code data get entity <p> Inventory} reply. */
    private static final Pattern COUNT = Pattern.compile("count: (\\d+)");

    @Override
    public String id() {
        return "pickup";
    }

    @Override
    public String title() {
        return "a clean-slate item pickup is delivered to exactly one player, exactly once";
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

        context.stage("P0", "infrastructure is up on a clean slate", () ->
                HostWorldSupport.probeWorkers(context));

        context.stage("P1/P2", "the dedicated server on a fresh world hosts both players, entity "
                + "lane live", () -> {
            // dedicatedTwoPlayers wipes run/world and re-stages it — the clean store this scenario
            // depends on IS that staging, not a leftover from a prior run.
            HostWorldSupport.dedicatedTwoPlayers(context);
            context.note("parking JoinerTwo at (" + PARK_X + ", " + PARK_Z
                    + ") — it must not race the pickup");
            rcon.require("gamemode creative JoinerTwo");
            rcon.teleport("JoinerTwo", PARK_X, 200, PARK_Z);
            // Let the park's ownership re-plan settle before the drive.
            context.settle(Duration.ofSeconds(12));
        });

        context.stage("P3", "the clean-slate pickup is delivered exactly once", () -> {
            String baseline = rcon.send("data get entity JoinerDev Inventory").orElse("");
            context.checkAbsent(baseline, "minecraft:stone",
                    "JoinerDev already holds stone — not a clean baseline");

            context.check(rcon.send("execute at JoinerDev run summon minecraft:item ~ ~ ~ "
                            + "{Item:{id:\"minecraft:stone\",count:3}}").isPresent(),
                    "summon failed");

            // The exit: the stack lands in the inventory with count EXACTLY 3 (no vanish).
            boolean landed = false;
            for (int attempt = 0; attempt < 30 && !landed; attempt++) {
                landed = rcon.send("data get entity JoinerDev Inventory").orElse("")
                        .contains("minecraft:stone");
                if (!landed) {
                    context.settle(Duration.ofSeconds(2));
                }
            }
            context.check(landed,
                    "the item VANISHED — no inventory delivery within 60s (the L-50 repro)");

            String inventory = rcon.send("data get entity JoinerDev Inventory").orElse("");
            context.check(firstCount(inventory) == 3, "expected exactly 3 stone, found "
                    + firstCount(inventory) + " (dupe or partial credit)");

            // Exactly-once must HOLD: no late duplicate credit, and no credit at all to the other
            // player (a dupe across players is the same bug wearing a different hat).
            context.settle(Duration.ofSeconds(10));
            String settled = rcon.send("data get entity JoinerDev Inventory").orElse("");
            int stacks = countOccurrences(settled, "minecraft:stone");
            context.check(firstCount(settled) == 3 && stacks == 1,
                    "exactly-once violated after settling (count=" + firstCount(settled)
                            + " stacks=" + stacks + ")");
            String other = rcon.send("data get entity JoinerTwo Inventory").orElse("");
            context.checkAbsent(other, "minecraft:stone",
                    "the parked player was credited the same stack — exactly-once violated across "
                            + "players");

            // Which lane delivered it is reported, not asserted: the local-primary gate falling back
            // losslessly to vanilla delivery is a correct outcome too.
            context.note(serverLog.contains("validated pickup committed")
                    ? "delivered via the VALIDATED lane (committee credit)"
                    : "delivered via the vanilla lane (local-primary gate fell back losslessly)");
        });

        stack.collectWorkerState();
    }

    /** The first stack's count, or -1 when the reply names none. */
    private static int firstCount(String inventory) {
        Matcher matcher = COUNT.matcher(inventory);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }
}
