package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.LogWatcher;
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
 * </pre>
 *
 * <p>What this does NOT assert yet: that a WorldEdit {@code //set} inside a delegated region is
 * CERTIFIED rather than suppressed. That is L-65, and it needs the validated lane the endpoint does
 * not host yet (server tasks 2-3). The stage lands with the capability, not ahead of it — an
 * assertion that cannot fail is not evidence.
 *
 * <p>Thread-context: stateless; the runner calls {@link #run} on its own thread.
 */
public final class PluginsScenario implements Scenario {

    /** The world the staged endpoint claims; see {@link EndpointScenario} for why it must name one. */
    private static final String WORLD_ID = "0000000000000e2e";

    private static final Pattern ENDPOINT_EXCEPTION =
            Pattern.compile("\\[NoderaEndpoint\\].*Exception|Could not pass event.*NoderaEndpoint");

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

                ctx.stack().rcon().send("stop");
                if (!log.pollFor("Nodera endpoint stopped", Duration.ofSeconds(180), 0)) {
                    ctx.note("C2: no clean disable line (the server may have exited first)");
                }
            });

            ctx.stack().collectArtefacts();
        }
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
