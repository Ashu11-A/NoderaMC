package dev.nodera.testkit.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * One Rust service binary, spawned from Java on an ephemeral port, with its bound address read back
 * from the line it prints.
 *
 * <p>This existed four times before it existed once: {@code TrackerServiceIT}, {@code
 * RendezvousRelayIT}, {@code WorldContinuityIT} and the live {@link LiveStack} each retyped the same
 * fifty lines — locate the binary under {@code dir.cargoTarget}, write a TOML, {@code
 * ProcessBuilder}, read stdout until an address appears, drain the rest on a daemon thread, destroy
 * with a grace period, delete the config. Four copies of a process lifecycle is four places for a
 * child process to be left running.
 *
 * <p><b>The ready line is matched by its whole prefix, not by a substring.</b> Every service prints
 * {@code "<name>: listening on <addr>"}, and {@code nodera-tracker} prints a <i>second</i> line,
 * {@code "nodera-tracker: udp listening on <addr>"}, for its UDP socket. All three copies searched
 * for {@code indexOf("listening on ")}, which matches both — so which address the test connected to
 * was decided by which line the child happened to flush first. Anchoring on {@code "<name>: "}
 * removes the race: the UDP line cannot satisfy the TCP wait.
 *
 * <p><b>Absence is a skip, unless the run says the binaries were built.</b> {@code cargo} is a
 * separate toolchain and a Java-only checkout must stay green, so {@link #binary(String)} returns
 * empty and the caller assumes. But a skip that is structural rather than circumstantial is a suite
 * that never runs — and until this class, twelve tests had never run in the main gate, because the
 * {@code java} job never built what they waited for. Setting {@value #REQUIRE_PROPERTY} (system
 * property or environment variable {@value #REQUIRE_ENV}) turns the absence into a failure, so a CI
 * job that promises to build the binaries cannot silently stop doing so.
 *
 * <p><b>The flag reaches this JVM only because the build forwards it.</b> A launcher {@code -D}
 * sets a property on the Gradle <i>daemon</i>, and a {@code Test} task inherits the daemon's
 * environment rather than the invoking shell's, so neither source arrives in a forked test worker
 * on its own. {@code library/java/build-logic/src/main/kotlin/NoderaServiceBinaries.kt} reads both
 * and re-publishes the value as a system property on every {@code Test} task. Without that, this
 * whole mechanism is unreachable — which it was, until the round-2 review found it.
 *
 * <p>Thread-context: constructed and closed on the test thread; one daemon thread per instance
 * drains the child's output so a full pipe can never block it.
 */
public final class SpawnedService implements AutoCloseable {

    /** System property that turns "binary missing" from a skip into a failure. */
    public static final String REQUIRE_PROPERTY = "nodera.test.requireServiceBinaries";

    /** Environment variable read when the system property is absent. */
    public static final String REQUIRE_ENV = "NODERA_REQUIRE_SERVICE_BINARIES";

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration STOP_GRACE = Duration.ofSeconds(10);

    private final String name;
    private final Process process;
    private final Path configFile;
    private final String endpoint;

    private SpawnedService(String name, Process process, Path configFile, String endpoint) {
        this.name = name;
        this.process = process;
        this.configFile = configFile;
        this.endpoint = endpoint;
    }

    /**
     * Locate a service binary under {@code dir.cargoTarget}, preferring a release build.
     *
     * <p>Empty means "this checkout has no cargo toolchain", and the caller assumes on it. But when
     * {@link #binariesRequired()} says the run promised to build them, empty means the promise was
     * broken, and this throws rather than letting the caller skip — a test that can only ever skip
     * is a test nobody is running.
     *
     * @param name the binary's file name, e.g. {@code "nodera-tracker"}.
     * @return the executable, or empty when neither profile has been built.
     * @throws IllegalStateException if the binary is absent and this run required it.
     */
    public static Optional<Path> binary(String name) {
        Path root = LayoutManifest.load().dir("cargoTarget");
        for (String profile : new String[] {"release", "debug"}) {
            Path candidate = root.resolve(profile).resolve(name);
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        if (binariesRequired()) {
            throw new IllegalStateException(
                    name + " was not built, and " + REQUIRE_PROPERTY + " says this run builds it. "
                    + "Looked under " + root.resolve("release") + " and " + root.resolve("debug")
                    + ". " + buildHint(name));
        }
        return Optional.empty();
    }

    /**
     * Whether this run has promised that the service binaries were built.
     *
     * <p>When true, a test that would have skipped must fail instead. That is the difference between
     * "this machine has no cargo toolchain" and "the job that said it would build these did not".
     *
     * @return true when {@value #REQUIRE_PROPERTY} or {@value #REQUIRE_ENV} is set to a true value.
     */
    public static boolean binariesRequired() {
        String property = System.getProperty(REQUIRE_PROPERTY);
        String value = property != null ? property : System.getenv(REQUIRE_ENV);
        return value != null && !value.isBlank()
                && !"0".equals(value) && !"false".equalsIgnoreCase(value);
    }

    /**
     * The message a skipping test prints, naming the binary and how to build it.
     *
     * @param name the binary's file name.
     * @return the description for the assumption or assertion.
     */
    public static String buildHint(String name) {
        return name + " binary (run: cargo build --release --bin " + name + ")";
    }

    /**
     * Spawn a service with the given TOML configuration and wait for its bound address.
     *
     * <p>The configuration goes to a temporary file that {@link #close()} deletes. The address
     * returned is whatever the process printed after {@code "<name>: listening on "}, which is the
     * only party that knows it when the config binds port 0.
     *
     * @param binary the executable, from {@link #binary(String)}.
     * @param configToml the whole configuration file contents.
     * @return a running service, to be closed by the caller.
     * @throws IOException if the process cannot start or never reports an address.
     */
    public static SpawnedService start(Path binary, String configToml) throws IOException {
        String name = binary.getFileName().toString();
        Path config = Files.createTempFile(name, ".toml");
        Files.writeString(config, configToml, StandardCharsets.UTF_8);

        ProcessBuilder builder = new ProcessBuilder(
                binary.toString(), "--config", config.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String marker = name + ": listening on ";
        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        try {
            String line;
            while (System.nanoTime() < deadline && (line = reader.readLine()) != null) {
                if (!line.startsWith(marker)) {
                    continue;
                }
                drainInBackground(name, reader);
                return new SpawnedService(
                        name, process, config, line.substring(marker.length()).trim());
            }
        } catch (IOException readFailed) {
            destroy(process);
            Files.deleteIfExists(config);
            throw readFailed;
        }
        destroy(process);
        Files.deleteIfExists(config);
        throw new IOException(name + " did not print \"" + marker + "<addr>\" within "
                + READY_TIMEOUT.toSeconds() + "s");
    }

    /**
     * The address the service bound, exactly as it printed it.
     *
     * @return a {@code host:port} string.
     */
    public String endpoint() {
        return endpoint;
    }

    /**
     * The running process, for a test that needs to kill it a particular way.
     *
     * @return the child process.
     */
    public Process process() {
        return process;
    }

    /** Stop the service, waiting a grace period before forcing, and delete its config file. */
    @Override
    public void close() {
        destroy(process);
        try {
            Files.deleteIfExists(configFile);
        } catch (IOException ignored) {
            // A leftover file in the OS temp directory is not worth failing a teardown over.
        }
    }

    @Override
    public String toString() {
        return name + "@" + endpoint;
    }

    private static void destroy(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(STOP_GRACE.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void drainInBackground(String name, BufferedReader reader) {
        Thread drain = new Thread(() -> {
            try {
                while (reader.readLine() != null) {
                    // The service's own logs are not this test's evidence; they are read only so a
                    // full pipe can never block the child.
                }
            } catch (IOException ignored) {
                // The stream closes when the process exits, which is how a drain ends.
            }
        }, name + "-log-drain");
        drain.setDaemon(true);
        drain.start();
    }

    /**
     * Read a service's whole stdout from a one-shot invocation, e.g. {@code --print-schema}.
     *
     * @param binary the executable.
     * @param args arguments after the binary.
     * @return the output, or empty when the process failed or timed out.
     */
    public static Optional<String> runOnce(Path binary, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = binary.toString();
        System.arraycopy(args, 0, command, 1, args.length);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            String out = new String(
                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            return process.exitValue() == 0 ? Optional.of(out) : Optional.empty();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
