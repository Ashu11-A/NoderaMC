package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.mc.RconClient;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The MOD-COMPATIBILITY CORPUS drive (server task 1's preflight half today; L-65's certification
 * half later).
 *
 * <pre>
 *   C0  preflight: plugin jar + Paper jar, or a SKIP with the reason
 *   C1  a Paper server carrying nodera-endpoint AND every corpus plugin found in run/plugins. What
 *       is present and what is ABSENT are both named: a compatibility scenario that quietly tests
 *       three plugins instead of seven is worse than one that says which four were missing
 *   C2  co-existence: every corpus plugin enables, Nodera enables, and neither leaves an exception
 *       in the log
 *   C3  THE BULK PATH: a WorldEdit //set of a known volume reaches Nodera's foreign-write bridge,
 *       block for block, and is not suppressed — the blocks are what the operator asked for
 *   C4  CoreProtect still logged every one of those changes, proven by rolling them back
 * </pre>
 *
 * <h2>C3 exists because of one specific way this suite could lie</h2>
 *
 * <p><b>WorldEdit's {@code //set} fires no Bukkit block event at all.</b> A foreign-write bridge
 * built on {@code BlockPlaceEvent} and friends sees a player placing one block by hand and sees
 * <i>nothing</i> for the operation L-65's exit clause names. A stage that drove a {@code //set} and
 * asserted "no error in the log" would pass against a bridge that observed zero blocks. So C3
 * asserts a NUMBER: every block of a known volume, counted by the plugin, in the world the operator
 * edited.
 *
 * <p><b>C3 and C4 fail when their subject is missing. They never note it.</b> The corpus used to be
 * allowed to be empty, which made "the compatibility suite is green" and "the compatibility suite
 * tested a server with no plugins on it" the same sentence. {@code scripts/stage-plugin-corpus.sh}
 * stages the pinned members, and a missing one is now a failure that names the script.
 *
 * <h2>What C3 does NOT assert, and why the row stays open</h2>
 *
 * <p>Not that the write is <b>certified</b>. Certification needs a delegated region — a committee,
 * a version chain, and a node key to sign an {@code EXTERNAL_MUTATION} certificate with — and
 * nothing on the plugin path delegates one yet (server tasks 2 and 3). What C3 proves is the half
 * that was previously unprovable in any form: the write REACHES the interference guard rather than
 * bypassing Nodera entirely. L-65's exit clause is unmet and the register says so.
 *
 * <p>Thread-context: stateless; the runner calls {@link #run} on its own thread.
 */
public final class PluginsScenario implements Scenario {

    /** The world the staged endpoint claims; see {@link EndpointScenario} for why it must name one. */
    private static final String WORLD_ID = "0000000000000e2e";

    private static final Pattern ENDPOINT_EXCEPTION =
            Pattern.compile("\\[NoderaEndpoint\\].*Exception|Could not pass event.*NoderaEndpoint");

    /**
     * The plugin's own per-session report, which is C3's whole assertion.
     *
     * <p>Kept as one pattern beside the constant that produces it
     * ({@code WorldEditBulkWrites.SESSION_PREFIX}); a reworded log message has silently turned three
     * live suites into timeouts before.
     */
    private static final Pattern WORLDEDIT_SESSION = Pattern.compile(
            "WorldEdit edit session in \\S+: (\\d+) foreign block write\\(s\\) observed");

    /** The operator who runs the {@code //set}. A fresh name each run never lands on a dead body. */
    private static final String BUILDER = "nodera_builder";

    /**
     * The volume the {@code //set} covers, in a flat world's empty air well above the surface.
     *
     * <p>{@code 16 × 5 × 16 = 1280} blocks — big enough that WorldEdit takes its bulk path (a
     * handful of blocks would not distinguish it from the slow one) and small enough to finish
     * inside a CI stage.
     */
    private static final int SET_X0 = 0;
    private static final int SET_Y0 = -50;
    private static final int SET_Z0 = 0;
    private static final int SET_X1 = 15;
    private static final int SET_Y1 = -46;
    private static final int SET_Z1 = 15;
    private static final int SET_BLOCKS =
            (SET_X1 - SET_X0 + 1) * (SET_Y1 - SET_Y0 + 1) * (SET_Z1 - SET_Z0 + 1);

    @Override
    public String id() {
        return "plugins";
    }

    @Override
    public String title() {
        return "nodera-endpoint co-exists with the staged plugin corpus and nothing throws";
    }

    @Override
    public Set<String> tags() {
        return Set.of("server", "plugins");
    }

    @Override
    public Requirements requirements() {
        return Requirements.paperEndpoint();
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        Path corpusDirectory = corpusDirectory(ctx);
        try (ServerEndpointSupport server = new ServerEndpointSupport(ctx)) {
            // ---------------------------------------------------------------------------
            // C0 — preflight
            // ---------------------------------------------------------------------------
            ctx.stage("C0", "a plugin jar and a Paper jar are both present",
                    () -> server.preflight("paper"));

            // ---------------------------------------------------------------------------
            // C1 — stage the corpus, naming what is and is not there
            // ---------------------------------------------------------------------------
            ctx.stage("C1", "Paper boots with nodera-endpoint and every corpus plugin found",
                    () -> {
                ctx.note("C1: staging Paper with nodera-endpoint + the corpus from "
                        + corpusDirectory);
                server.stageBukkitServer(WORLD_ID);

                // The corpus is whatever an operator staged. Nothing is downloaded: a compatibility
                // scenario that fetches its own subjects tests whatever the internet happened to
                // serve that morning.
                for (Path jar : corpusJars(corpusDirectory)) {
                    server.installCorpusPlugin(jar);
                }

                if (server.corpusPresent().isEmpty()) {
                    ctx.note("C1: the corpus is EMPTY — stage jars in " + corpusDirectory
                            + " to test co-existence with them.");
                    ctx.note("C1: continuing with nodera-endpoint alone, which still proves the "
                            + "jar preflight.");
                } else {
                    ctx.note("C1: corpus present: " + String.join(" ", server.corpusPresent()));
                }
                if (!server.corpusMissing().isEmpty()) {
                    ctx.note("C1: corpus MISSING: " + String.join(" ", server.corpusMissing()));
                }

                server.startBukkitServer("server.log");
                ctx.log("server.log").await("Done (", Duration.ofSeconds(480));
            });

            // ---------------------------------------------------------------------------
            // C2 — co-existence
            // ---------------------------------------------------------------------------
            ctx.stage("C2", "every staged plugin loaded and nothing threw", () -> {
                LogWatcher log = ctx.log("server.log");
                ctx.check(log.contains("[NoderaEndpoint] Enabling"),
                        "C2: nodera-endpoint did not enable alongside the corpus");
                ctx.check(log.lastCapture(ENDPOINT_EXCEPTION).isEmpty(),
                        "C2: nodera-endpoint threw with the corpus loaded");
                for (String name : server.corpusPresent()) {
                    String stem = name.endsWith(".jar")
                            ? name.substring(0, name.length() - ".jar".length()) : name;
                    ctx.check(log.lastCapture(Pattern.compile(Pattern.quote(stem),
                                    Pattern.CASE_INSENSITIVE)).isPresent(),
                            "C2: " + name + " is staged but never appears in the server log — "
                                    + "did it load?");
                }

            });

            // ---------------------------------------------------------------------------
            // C3 — the bulk path: //set reaches Nodera, block for block, and is not suppressed
            // ---------------------------------------------------------------------------
            ctx.stage("C3", "a WorldEdit //set of " + SET_BLOCKS
                    + " blocks reaches Nodera's foreign-write bridge", () -> {
                ctx.check(staged(server, "worldedit"),
                        "C3: WorldEdit is not in the corpus, so the one plugin whose writes bypass "
                                + "Bukkit entirely was never driven. Stage it with "
                                + "`scripts/stage-plugin-corpus.sh worldedit`. This is a failure, "
                                + "not a note: L-65's exit clause names this plugin.");

                RconClient rcon = ctx.stack().rcon();
                // The builder must survive long enough to type three commands. A player killed on
                // the way is held at the death screen and every command it sends is dropped, which
                // presents as "WorldEdit did nothing" — observed on a real 1.21.1 Paper.
                rcon.require("difficulty peaceful");
                rcon.require("gamerule doMobSpawning false");
                rcon.require("gamerule doImmediateRespawn true");
                rcon.require("op " + BUILDER);

                LogWatcher log = ctx.log("server.log");
                int mark = log.lineCount();
                try (ServerVanillaBot builder = new ServerVanillaBot("127.0.0.1",
                        ctx.topology().gamePort(), BUILDER,
                        ServerEndpointSupport.MINECRAFT_PROTOCOL,
                        ctx.topology().scaled(Duration.ofSeconds(60)),
                        ctx.stack().logDir().resolve("worldedit-builder.log"))) {
                    // A WorldEdit command keeps its leading slash: chat `//set` is command `/set`.
                    builder.run(List.of("wait:joined", "sleep:5",
                            "cmd:/pos1 " + SET_X0 + "," + SET_Y0 + "," + SET_Z0, "sleep:2",
                            "cmd:/pos2 " + SET_X1 + "," + SET_Y1 + "," + SET_Z1, "sleep:2",
                            "cmd:/set stone", "sleep:15", "quit"));
                }

                long observed = observedByNodera(log.file(), mark);
                ctx.check(observed >= SET_BLOCKS,
                        "C3: Nodera observed " + observed + " of the " + SET_BLOCKS
                                + " blocks the //set wrote. ZERO is the signature of a bridge built "
                                + "on Bukkit events alone — //set fires none. Anything between is a "
                                + "partially wrapped extent chain.");
                ctx.note("C3: " + observed + " foreign block writes reached the interference guard "
                        + "through WorldEdit's extent pipeline.");

                // ...and the write LANDED. A bridge that certified by suppressing would also
                // report a number.
                rcon.require("execute if block " + SET_X0 + " " + SET_Y0 + " " + SET_Z0
                        + " minecraft:stone run say NODERA-C3-SET-LANDED");
                ctx.check(log.pollFor("NODERA-C3-SET-LANDED", Duration.ofSeconds(30), mark),
                        "C3: the //set was observed but the world is not stone — the write was "
                                + "suppressed or reverted, which is exactly what PC-3 forbids");

                ctx.note("C3: NOT asserted — that the write was CERTIFIED into a version chain. "
                        + "Nothing on the plugin path delegates a region yet (server tasks 2/3), so "
                        + "there is no chain to certify into. L-65 stays open on that clause.");
            });

            // ---------------------------------------------------------------------------
            // C4 — CoreProtect logged every one of those changes
            // ---------------------------------------------------------------------------
            ctx.stage("C4", "CoreProtect logged the whole //set, and can roll it back", () -> {
                ctx.check(staged(server, "coreprotect"),
                        "C4: CoreProtect is not in the corpus, so the MONITOR-priority logger "
                                + "L-65's exit clause names was never run beside Nodera's. Stage it "
                                + "with `scripts/stage-plugin-corpus.sh coreprotect`.");

                LogWatcher log = ctx.log("server.log");
                ctx.check(log.contains("WorldEdit logging successfully initialized"),
                        "C4: CoreProtect did not hook WorldEdit, so it is not observing the same "
                                + "extent chain Nodera is — the PC-2 collision this stage exists to "
                                + "test never happened");

                // The proof that it logged EVERY change is that its own record can undo them all.
                // A lookup cannot be asserted over RCON: CoreProtect answers asynchronously, after
                // the exchange has closed — the same platform fact §1.2.1 records for spark.
                int mark = log.lineCount();
                RconClient rcon = ctx.stack().rcon();
                rcon.require("co rollback u:" + BUILDER + " t:1h r:#global");
                boolean restored = false;
                for (int attempt = 0; attempt < 20 && !restored; attempt++) {
                    rcon.require("execute if block " + SET_X0 + " " + SET_Y0 + " " + SET_Z0
                            + " minecraft:air run say NODERA-C4-ROLLED-BACK");
                    restored = log.pollFor("NODERA-C4-ROLLED-BACK", Duration.ofSeconds(3), mark);
                }
                ctx.check(restored,
                        "C4: CoreProtect could not roll the //set back to air, so it did not log "
                                + "every change Nodera observed. Nodera's MONITOR listeners must "
                                + "not have disturbed it — that is what PC-2 promises.");

                ctx.check(log.lastCapture(ENDPOINT_EXCEPTION).isEmpty(),
                        "C4: nodera-endpoint threw while the corpus was writing");
            });

            ctx.stage("C5", "the server stops cleanly with the corpus loaded", () -> {
                LogWatcher log = ctx.log("server.log");
                ctx.stack().rcon().send("stop");
                if (!log.pollFor("Nodera endpoint stopped", Duration.ofSeconds(180), 0)) {
                    ctx.note("C5: no clean disable line (the server may have exited first)");
                }
            });

            ctx.stack().collectArtefacts();
        }
    }

    /** Is a corpus member staged? Matched on the jar's file name, case-insensitively. */
    private static boolean staged(ServerEndpointSupport server, String member) {
        return server.corpusPresent().stream()
                .anyMatch(jar -> jar.toLowerCase(Locale.ROOT).contains(member));
    }

    /**
     * The largest per-session count the plugin reported after {@code mark}.
     *
     * <p>The largest, not the last: one {@code //set} produces more than one WorldEdit edit session
     * — the operation's own, and an empty one for the selection — and only the one that carried the
     * blocks answers the question C3 is asking.
     */
    private static long observedByNodera(Path serverLog, int mark) throws java.io.IOException {
        List<String> lines = Files.readAllLines(serverLog);
        long best = 0;
        for (int i = Math.max(0, mark); i < lines.size(); i++) {
            var match = WORLDEDIT_SESSION.matcher(lines.get(i));
            if (match.find()) {
                best = Math.max(best, Long.parseLong(match.group(1)));
            }
        }
        return best;
    }

    /** {@code $NODERA_PLUGIN_CORPUS_DIR}, else {@code run/plugins}. */
    private static Path corpusDirectory(ScenarioContext ctx) {
        String configured = System.getenv("NODERA_PLUGIN_CORPUS_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        return ctx.stack().paths().runDir().resolve("plugins");
    }

    private static List<Path> corpusJars(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (Exception unreadable) {
            return List.of();
        }
    }
}
