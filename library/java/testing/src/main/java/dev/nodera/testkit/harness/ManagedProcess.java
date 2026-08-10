package dev.nodera.testkit.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One child process of a live run: how it was started, where its output went, and how it dies.
 *
 * <h2>Killing is the part that matters — and this class cannot finish the job alone</h2>
 *
 * <p>Half of these processes are Gradle launchers, and a Minecraft JVM is <b>not</b> a descendant of
 * the launcher that asked for it. Gradle hands the build to a long-lived DAEMON, and the daemon
 * forks the game — so the game is the daemon's child, and this class's {@link ProcessHandle#descendants()}
 * walk in {@link #stop}/{@link #kill} never reaches it. Measured directly on a live run: stopping a
 * client's wrapper left its game JVM running and serving for minutes afterwards, while a stage that
 * had just announced the player's departure asserted — and reported a pass — against a world that
 * was still up.
 *
 * <p>This doc used to claim the opposite, that {@code destroy()} on the launcher leaves the JVM
 * running and the descendants walk therefore handles it. Only the first half was true. Stopping a
 * client for real needs BOTH halves: this class for the wrapper, and
 * {@code HostWorldSupport.stopClient} for the game JVM, which addresses it by its per-run token and
 * waits for it to be gone. Reach for that, not for this, whenever the subject is a game client.
 *
 * <p>{@link #kill} remains available for processes this harness really did start directly — the
 * dedicated server, the services — where the tree walk does reach everything that matters. The
 * mechanism for the other kind, {@link #findByToken}, lives here too: addressing a process this
 * class did not fork is still a fact about processes, and it was implemented in a scenario support
 * class for long enough that two places described one behaviour.
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

    /**
     * The exit status of a process that has already exited, or {@code 0} while it is still running.
     *
     * <p>For a readiness wait the interesting question is "did the launcher fail", and a non-zero
     * status answers it before any timeout can. A live process is reported as {@code 0} because the
     * caller has already asked {@link #isAlive}; this is not a way to poll for completion.
     */
    public int exitValueOrUnknown() {
        return process.isAlive() ? 0 : process.exitValue();
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

    // ---------------------------------------------------------------------------------------
    // Processes this harness did NOT start directly: addressing a JVM by a token on its command line
    // ---------------------------------------------------------------------------------------

    /** How a Gradle daemon identifies itself; see {@link #commandLineOf}. */
    private static final Pattern DAEMON =
            Pattern.compile("GradleDaemon|org\\.gradle\\.launcher\\.daemon");

    /**
     * The PID of a process whose command line carries {@code token}.
     *
     * <p>This is the other half of the class javadoc above: the game JVM is a child of the Gradle
     * DAEMON, so no handle this class owns can reach it and it has to be addressed by something it
     * carries itself. ModDevGradle passes each run's program arguments via an {@code @argfile}, so
     * the JVM command line carries {@code <run>RunProgramArgs} — NOT the quick-play flags. That
     * per-run token is the only reliable way to tell the joiner JVM from the host JVM: matching a
     * bare {@code RunProgramArgs} also matches the dedicated SERVER, and a scenario that killed it
     * by accident then dials a game port nothing is listening on — a "the joiner never joined"
     * failure with an innocent client.
     *
     * @param token e.g. {@code clientJoinRunProgramArgs}.
     * @return the first matching PID, or empty when nothing carries it.
     */
    public static OptionalLong findByToken(String token) {
        try (Stream<ProcessHandle> processes = ProcessHandle.allProcesses()) {
            return processes
                    .filter(handle -> commandLineOf(handle).contains(token))
                    .mapToLong(ProcessHandle::pid)
                    .findFirst();
        }
    }

    /** @return {@code true} while some process carries {@code token}. */
    public static boolean tokenAlive(String token) {
        return findByToken(token).isPresent();
    }

    /** Apply {@code action} to every process carrying {@code token}. */
    public static void forEachByToken(String token, Consumer<ProcessHandle> action) {
        try (Stream<ProcessHandle> processes = ProcessHandle.allProcesses()) {
            processes.filter(handle -> commandLineOf(handle).contains(token)).forEach(action);
        }
    }

    /**
     * The FULL command line of a process.
     *
     * <p>Not {@code ProcessHandle.info().commandLine()}, which truncates at 4096 bytes on Linux. A
     * ModDevGradle client JVM's command line is about 16 KB and ends with its {@code @argfile} —
     * measured at byte 16579 of 16608 — so the one token that identifies which run a JVM belongs to
     * is exactly the part the JDK throws away. Every caller therefore saw NO client JVM at all, and
     * each failed silently in its own direction: "is it alive" always answered "dead" (which
     * reported a healthy host as having died with its joiner), a kill killed nothing (so a crash
     * scenario's SIGKILL was in fact a graceful wrapper stop, which is the one path it exists NOT to
     * exercise), and "wait until it is gone" returned at once having found nothing to wait for.
     *
     * <p>Reads {@code /proc} directly where it exists and falls back to the truncated view where it
     * does not, so a non-Linux run degrades to the old behaviour rather than to an exception.
     */
    private static String commandLineOf(ProcessHandle handle) {
        Path cmdline = Path.of("/proc", String.valueOf(handle.pid()), "cmdline");
        String full;
        if (Files.isReadable(cmdline)) {
            try {
                full = new String(Files.readAllBytes(cmdline), StandardCharsets.UTF_8)
                        .replace('\0', ' ');
            } catch (IOException exited) {
                // The process ended between listing and reading; not the one being looked for.
                return "";
            }
        } else {
            full = handle.info().commandLine().orElse("");
        }
        // The Gradle DAEMON is never a match, whatever tokens it carries. It serves every build in
        // the run, so its command line names BOTH clients' argfiles — and once the full command line
        // became visible, "stop the joiner" started matching the process that owns the host as well.
        // Signalling it takes down every client at once, which is precisely the failure this whole
        // line of work exists to stop mistaking for a product defect.
        return DAEMON.matcher(full).find() ? "" : full;
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
