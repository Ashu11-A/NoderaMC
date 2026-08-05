package dev.nodera.testkit.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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
                layout.module("peer")
                        .resolve("build/install/nodera-headless/bin/nodera-headless"),
                layout.module("paper-plugin").resolve("build/libs/nodera-paper.jar"),
                // The companion app is its own cargo workspace (layout.properties: nodera-app is
                // excluded from the root one), so its target directory is under the crate, not under
                // dir.cargoTarget. Composing the file name here is the same division of labour as
                // the jars above: the manifest says where the crate is, this says what it builds.
                layout.crate("nodera-app").resolve("target/release/nodera-app"),
                run,
                run.resolve(".e2e-suite.lock"),
                run.resolve("results"));
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
