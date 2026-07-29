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
        Path runDir,
        Path lockFile,
        Path resultsRoot) {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * Resolve the layout from the repository root.
     *
     * @param root the repository root (the directory holding {@code settings.gradle.kts}).
     * @return the resolved paths.
     */
    public static TestPaths of(Path root) {
        Path absolute = root.toAbsolutePath().normalize();
        return new TestPaths(
                absolute,
                absolute.resolve("rust/target/release"),
                absolute.resolve("java/neoforge-mod"),
                absolute.resolve("java/worker/build/install/nodera-headless/bin/nodera-headless"),
                absolute.resolve("java/paper-plugin/build/libs/nodera-endpoint.jar"),
                absolute.resolve("run"),
                absolute.resolve("run/.e2e-suite.lock"),
                absolute.resolve("run/results"));
    }

    /**
     * Find the repository root by walking up from the working directory.
     *
     * @return the resolved layout.
     * @throws IllegalStateException if no {@code settings.gradle.kts} is found on the way up.
     */
    public static TestPaths discover() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.exists(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException(
                    "cannot find the repository root from " + Path.of("").toAbsolutePath());
        }
        return of(candidate);
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
