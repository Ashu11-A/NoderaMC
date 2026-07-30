package dev.nodera.testkit.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One child process of a live run: how it was started, where its output went, and how it dies.
 *
 * <h2>Killing is the part that matters</h2>
 *
 * <p>Half of these processes are Gradle launchers whose interesting child is a Minecraft JVM. A
 * {@code destroy()} on the launcher leaves that JVM running, holding the game port and the run
 * directory, and the next scenario fails on a port check with no hint that the previous one is
 * still alive. {@link #stop} therefore walks {@link ProcessHandle#descendants()} first, and
 * {@link #kill} is available for the scenarios whose whole subject is an ungraceful death: a crash
 * test that stopped its target politely would be testing the wrong path.
 *
 * <p>Thread-context: one instance per process; {@link #stop}/{@link #kill} are idempotent.
 */
public final class ManagedProcess implements AutoCloseable {

    private final String name;
    private final Process process;
    private final Path logFile;
    private final List<String> command;

    private ManagedProcess(String name, Process process, Path logFile, List<String> command) {
        this.name = name;
        this.process = process;
        this.logFile = logFile;
        this.command = List.copyOf(command);
    }

    /**
     * Start a process with its output captured to {@code logFile}.
     *
     * @param name        how the run report refers to it ("tracker", "worker-player1", …).
     * @param workingDir  the working directory.
     * @param logFile     stdout and stderr, merged — a live run's evidence is interleaved by
     *                    design, because a crash on stderr and the last normal line on stdout are
     *                    only useful together.
     * @param environment extra environment entries.
     * @param command     the command line.
     * @return the started process.
     */
    public static ManagedProcess start(String name, Path workingDir, Path logFile,
                                       Map<String, String> environment, List<String> command) {
        try {
            Files.createDirectories(logFile.getParent());
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDir.toFile());
            builder.environment().putAll(environment);
            builder.redirectErrorStream(true);
            builder.redirectOutput(Redirect.to(logFile.toFile()));
            return new ManagedProcess(name, builder.start(), logFile, command);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start " + name + ": " + String.join(" ", command), e);
        }
    }

    /** Builder for the environment map, so call sites read as a list of decisions. */
    public static Map<String, String> env(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("env() takes key/value pairs");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }

    public String name() {
        return name;
    }

    public Path logFile() {
        return logFile;
    }

    public long pid() {
        return process.pid();
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** The command line, for the run report — a failure nobody can reproduce is half a report. */
    public List<String> command() {
        return command;
    }

    /** Wait for the process to exit on its own. */
    public int awaitExit(Duration timeout) {
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new HarnessException(name + " did not exit within " + timeout.toSeconds() + "s");
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HarnessException("interrupted while waiting for " + name, e);
        }
    }

    /** Graceful stop of the whole tree, escalating to a kill after the grace period. */
    public void stop(Duration grace) {
        if (!process.isAlive()) {
            return;
        }
        List<ProcessHandle> tree = new ArrayList<>(process.descendants().toList());
        tree.add(process.toHandle());
        tree.forEach(ProcessHandle::destroy);
        try {
            if (!process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                kill();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill();
        }
    }

    /**
     * SIGKILL the whole tree, now.
     *
     * <p>This is the method a crash scenario calls: the point of those runs is that nothing got a
     * chance to flush, close, or say goodbye, and a graceful stop would exercise the path that does
     * not fail.
     */
    public void kill() {
        List<ProcessHandle> tree = new ArrayList<>(process.descendants().toList());
        tree.add(process.toHandle());
        tree.forEach(ProcessHandle::destroyForcibly);
    }

    @Override
    public void close() {
        stop(Duration.ofSeconds(20));
    }

    @Override
    public String toString() {
        return name + "[pid=" + process.pid() + ", alive=" + process.isAlive() + "]";
    }
}
