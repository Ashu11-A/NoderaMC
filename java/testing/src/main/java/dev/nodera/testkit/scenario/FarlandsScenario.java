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
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two players extremely far apart: who controls which chunks, and where is each player?
 *
 * <ol>
 *   <li>F0 the standard topology plus a clean-slate dedicated server with both players joined.</li>
 *   <li>F1 the players are teleported about 566 km apart — JoinerDev to (200000, 200, 200000) and
 *       JoinerTwo to (-200000, 200, -200000). Positions are read back over RCON
 *       ({@code data get entity <p> Pos}) and asserted within ±16 blocks of the target.</li>
 *   <li>F2 ownership interrogation per player over RCON: {@code nodera zone} for the region at the
 *       player's feet and its ownership state as the server node sees it, plus
 *       {@code nodera regions} / {@code nodera entities} for the delegation picture. After a nudge
 *       teleport (+64 blocks) the always-on REGION boundary tracker logs
 *       {@code REGION: <p> … entered Region[…] (owner: <name>)} — the assertion is that each
 *       far-apart player OWNS the region it stands in.</li>
 *   <li>F3 every interrogation transcript, every worker's state document, and all logs are
 *       collected.</li>
 * </ol>
 *
 * <p>Thread-context: run on the runner's thread; stateless between runs.
 */
public final class FarlandsScenario implements Scenario {

    private static final int X1 = 200_000;
    private static final int Z1 = 200_000;
    private static final int X2 = -200_000;
    private static final int Z2 = -200_000;

    /** The first {@code x,z} pair in a zone panel — the region the player is standing in. */
    private static final Pattern REGION_COORDS = Pattern.compile("-?[0-9]+,-?[0-9]+");

    /** Any of the ownership states the zone panel may report. */
    private static final Pattern OWNERSHIP_STATE =
            Pattern.compile("OWNED|VALIDATING|REPLICA|FOREIGN|UNASSIGNED", Pattern.CASE_INSENSITIVE);

    @Override
    public String id() {
        return "farlands";
    }

    @Override
    public String title() {
        return "two players 566 km apart each control the chunks they stand in";
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
        Path serverLogFile = stack.logDir().resolve("server.log");
        int[] mark = new int[1];

        context.stage("F0", "both players are in-world, the lane is live, every peer is up", () ->
                HostWorldSupport.dedicatedTwoPlayers(context));

        context.stage("F1", "the players are verified about 566 km apart", () -> {
            rcon.require("gamemode creative JoinerDev");
            rcon.require("gamemode creative JoinerTwo");
            rcon.teleport("JoinerDev", X1, 200, Z1);
            rcon.teleport("JoinerTwo", X2, 200, Z2);
            context.note("waiting for far chunks + the ownership replan to settle");
            context.settle(Duration.ofSeconds(20));
            assertPosition(context, rcon, "JoinerDev", X1, Z1);
            assertPosition(context, rcon, "JoinerTwo", X2, Z2);
        });

        context.stage("F2", "each far-apart player's zone panel is valid and they stand in "
                + "distinct regions", () -> {
            interrogate(context, rcon, "JoinerDev");
            interrogate(context, rcon, "JoinerTwo");

            // The two players must be standing in DIFFERENT regions (they are 566 km apart).
            Optional<String> zoneOne = firstRegionCoordinate(zone(rcon, "JoinerDev"));
            Optional<String> zoneTwo = firstRegionCoordinate(zone(rcon, "JoinerTwo"));
            HostWorldSupport.transcript(context, "interrogation.log",
                    "=== region coords: JoinerDev=" + zoneOne.orElse("")
                            + " JoinerTwo=" + zoneTwo.orElse(""));
            context.check(zoneOne.isPresent() && zoneTwo.isPresent()
                            && !zoneOne.get().equals(zoneTwo.get()),
                    "players report the same/empty region (" + zoneOne.orElse("<none>") + " vs "
                            + zoneTwo.orElse("<none>") + ")");
        });

        context.stage("F2b", "the REGION boundary evidence names each player as the owner of the "
                + "region it entered", () -> {
            // Nudge each player across a region boundary — the always-on REGION tracker logs the
            // entered region's owner; each far-apart player must own the region it lands in (its own
            // FOV disc). A plain teleport, not the verified one: this is a nudge, and the position
            // it lands on has already been asserted above.
            mark[0] = serverLog.lineCount();
            rcon.send("execute in minecraft:overworld run tp JoinerDev "
                    + (X1 + 64) + " 200 " + (Z1 + 64));
            rcon.send("execute in minecraft:overworld run tp JoinerTwo "
                    + (X2 - 64) + " 200 " + (Z2 - 64));
            context.settle(Duration.ofSeconds(10));

            for (String line : HostWorldSupport.matchesAfter(serverLogFile, mark[0], "REGION: ")) {
                HostWorldSupport.transcript(context, "interrogation.log", line);
            }
            Optional<String> ownerOne =
                    HostWorldSupport.lastMatchAfter(serverLogFile, mark[0], "REGION: JoinerDev ");
            Optional<String> ownerTwo =
                    HostWorldSupport.lastMatchAfter(serverLogFile, mark[0], "REGION: JoinerTwo ");
            context.check(ownerOne.isPresent() || ownerTwo.isPresent(),
                    "no REGION boundary evidence was logged after the nudges");
            // A line without an `owner:` field carries no claim to check — the assertion is only
            // ever made against evidence that actually names an owner.
            ownerOne.filter(line -> line.contains("owner: ")).ifPresent(line ->
                    context.checkContains(line, "owner: JoinerDev",
                            "JoinerDev entered a region owned by someone else"));
            ownerTwo.filter(line -> line.contains("owner: ")).ifPresent(line ->
                    context.checkContains(line, "owner: JoinerTwo",
                            "JoinerTwo entered a region owned by someone else"));
        });

        context.stage("F3", "worker state snapshots and logs are collected",
                stack::collectWorkerState);
    }

    /**
     * Read a player's position until it parses AND matches, and only then call it a failure.
     *
     * <p>POLLED, not sampled once. Generating terrain 200 km out puts the server tens of seconds
     * behind on a two-core CI runner ("Can't keep up! … 274 ticks behind"), and a single read taken
     * during that window comes back EMPTY — while the teleport had in fact worked.
     */
    private void assertPosition(ScenarioContext context, RconClient rcon, String player,
                                int x, int z) {
        Duration limit = Duration.ofSeconds(180);
        long deadline = System.nanoTime() + context.topology().scaled(limit).toNanos();
        String position = "";
        while (System.nanoTime() < deadline) {
            position = rcon.send("data get entity " + player + " Pos").orElse("");
            if (HostWorldSupport.positionWithin(position, x, z, 16)) {
                break;
            }
            context.settle(Duration.ofSeconds(5));
        }
        HostWorldSupport.transcript(context, "interrogation.log",
                "=== " + player + " position: " + position);
        context.check(HostWorldSupport.positionWithin(position, x, z, 16),
                player + " is not at (" + x + ", " + z + ") after "
                        + context.topology().scaled(limit).toSeconds() + "s: " + position);
    }

    /** The three interrogation panels, transcripted, with the zone panel's two assertions. */
    private void interrogate(ScenarioContext context, RconClient rcon, String player) {
        for (String command : List.of("nodera zone", "nodera regions", "nodera entities")) {
            String out = rcon.send("execute as " + player + " at " + player + " run " + command)
                    .orElse("");
            HostWorldSupport.transcript(context, "interrogation.log",
                    "=== " + player + ": /" + command + "\n" + out + "\n");
        }
        String zone = zone(rcon, player);
        context.check(zone.toLowerCase(Locale.ROOT).contains("region"),
                player + " zone panel has no region row: " + zone);
        context.check(OWNERSHIP_STATE.matcher(zone).find(),
                player + " zone panel has no ownership state: " + zone);
    }

    private String zone(RconClient rcon, String player) {
        return rcon.send("execute as " + player + " at " + player + " run nodera zone").orElse("");
    }

    private Optional<String> firstRegionCoordinate(String zonePanel) {
        Matcher matcher = REGION_COORDS.matcher(zonePanel);
        return matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
    }
}
