package dev.nodera.testkit.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Every path the live harness needs, derived once from the repository root.
 *
 * <p>The shell harness this replaces derived each of these at the top of every suite with a
 * {@code ${VAR:-default}} chain, which meant a suite could disagree with the launcher about where
 * the worker distribution lived and only find out as a "file not found" thirty seconds into a run.
 * One record, resolved once, hands every scenario the same answer.
 *
 * <p>Thread-context: immutable; safe to share across suites and threads.
 */
public record TestPaths(
        Path root,
        Path rustRelease,
        Path modDir,
        Path workerDist,
        Path paperPluginJar,
        Path companionApp,
        Path runDir,
        Path lockFile,
        Path resultsRoot) {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * Resolve the layout from the repository root.
     *
     * <p>Directories come from {@link LayoutManifest}; the file names under them are composed here,
     * because {@code build/libs/nodera-paper.jar} is a property of the build system and the
     * manifest only describes the tree.
     *
     * <p>The layout SHAPE always comes from the repository's own manifest; only the root is the
     * caller's. That is what lets a preflight test pass an empty temporary directory and get a
     * correctly-shaped layout in which nothing is built.
     *
     * @param root the directory to resolve the layout against.
     * @return the resolved paths.
     */
    public static TestPaths of(Path root) {
        return of(LayoutManifest.load().rebasedOn(root));
    }

    /**
     * Resolve the layout from an already-loaded manifest.
     *
     * @param layout the layout table.
     * @return the resolved paths.
     */
    public static TestPaths of(LayoutManifest layout) {
        Path run = layout.dir("run");
        return new TestPaths(
                layout.root(),
                layout.dir("cargoTarget").resolve("release"),
                layout.module("neoforge-mod"),
                fromEnvironment("NODERA_E2E_WORKER_BIN").orElseGet(() -> layout.module("peer")
                        .resolve("build/install/nodera-headless/bin/nodera-headless")),
                layout.module("paper-plugin").resolve("build/libs/nodera-paper.jar"),
                // The companion app is its own cargo workspace (layout.properties: nodera-app is
                // excluded from the root one), so its target directory is under the crate, not under
                // dir.cargoTarget. Composing the file name here is the same division of labour as
                // the jars above: the manifest says where the crate is, this says what it builds.
                fromEnvironment("NODERA_E2E_APP_BIN").orElseGet(() ->
                        layout.crate("nodera-app").resolve("target/release/nodera-app")),
                run,
                run.resolve(".e2e-suite.lock"),
                run.resolve("results"));
    }

    /**
     * A binary the run was told to use instead of the one this checkout builds.
     *
     * <h2>Why the harness can be pointed outside its own tree</h2>
     *
     * <p>The companion's acceptance claim is about an <b>installed</b> product: "install the app,
     * host a world, close Minecraft, and have your friend still be able to join". A job that builds
     * the worker with {@code :peer:installDist} and runs it out of {@code build/install} tests the
     * code and not the product — the install path is where bundled runtimes, resource paths, file
     * permissions and the launcher script's own assumptions actually break, and every one of those
     * is invisible to a run that never installed anything.
     *
     * <p>So a job may install the {@code .deb} that {@code scripts/release.sh --component app}
     * produced and hand these two variables the paths the <b>package manager</b> reported, and the
     * live stack then launches those. Nothing is defaulted to an install location: this harness must
     * not guess where a package put its files, and a scenario that needs to know whether it got an
     * installed binary asks whether the path it was given is inside the repository root.
     */
    private static Optional<Path> fromEnvironment(String variable) {
        String value = System.getenv(variable);
        return value == null || value.isBlank()
                ? Optional.empty() : Optional.of(Path.of(value.trim()));
    }

    /**
     * Find the repository root by walking up from the working directory.
     *
     * @return the resolved layout.
     * @throws IllegalStateException if the repository root is not found on the way up.
     */
    public static TestPaths discover() {
        return of(LayoutManifest.load());
    }

    /** The tracker service binary. */
    public Path trackerBinary() {
        return rustRelease.resolve("nodera-tracker");
    }

    /** The rendezvous service binary. */
    public Path rendezvousBinary() {
        return rustRelease.resolve("nodera-rendezvous");
    }

    /** A NeoForge dev run directory ({@code run}, {@code run-host}, {@code run-join}, …). */
    public Path gameDir(String name) {
        return modDir.resolve(name);
    }

    /** {@code <gameDir>/logs/latest.log} — what the mod and Minecraft actually write to. */
    public Path gameLog(String gameDir) {
        return gameDir(gameDir).resolve("logs/latest.log");
    }

    /** A fresh, timestamped output directory for one suite run. */
    public Path newRunDirectory(String suiteId) {
        Path directory = resultsRoot.resolve(suiteId).resolve(LocalDateTime.now().format(STAMP));
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the results directory " + directory, e);
        }
        return directory;
    }
}
