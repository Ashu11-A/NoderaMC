package dev.nodera.testkit.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The two ways a spawned child can hang the thread that spawned it.
 *
 * <p>Both defects are the same shape: a blocking read on a pipe with no bound on it. {@link
 * SpawnedService#start} consulted its ready deadline only <i>between</i> lines, so a child that
 * printed nothing and did not exit parked the test thread inside {@code readLine()} forever, and the
 * twenty-second promise in the javadoc could never be kept. {@link SpawnedService#runOnce} left the
 * child's stderr on a pipe nobody read, so a child that wrote more than the operating system's pipe
 * buffer to fd 2 blocked writing, never exited, and never closed stdout — which meant {@code
 * readAllBytes()} never returned and the thirty-second {@code waitFor} below it was never reached.
 *
 * <p>A CI job wedged with no output is worse than a red one: nothing names the cause, and the job is
 * killed by a scheduler minutes or hours later with a message about the scheduler. Each test here
 * therefore bounds the call from the outside with {@link
 * org.junit.jupiter.api.Assertions#assertTimeoutPreemptively} and asserts that the helper came back
 * on its own well inside that bound. Against the unfixed helper both hang and are cut down by the
 * preemptive bound; against the fixed one both return.
 *
 * <p>The children are shell scripts rather than service binaries, because neither defect has
 * anything to do with what the child is: {@code start} and {@code runOnce} take a {@link Path} to
 * any executable. A machine with no POSIX shell skips — that is circumstantial, a property of the
 * machine, unlike a skip that no configuration can ever turn off.
 */
final class SpawnedServiceLifecycleTest {

    /** Comfortably over the Linux pipe buffer of 64 KiB, so a undrained stderr certainly blocks. */
    private static final int STDERR_FLOOD_BYTES = 1024 * 1024;

    /** Short enough to keep the suite quick; the production default stays at twenty seconds. */
    private static final Duration TEST_READY_TIMEOUT = Duration.ofSeconds(2);

    @TempDir
    Path work;

    @Test
    @DisplayName("a child that prints nothing and does not exit fails the ready wait, not the job")
    void aSilentChildTimesOutInsteadOfHangingTheCaller() throws IOException {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "a POSIX shell to script a child with");

        Path pidFile = work.resolve("silent.pid");
        Path child = script("nodera-silent-child", """
                printf '%s\\n' "$$" > "PIDFILE"
                exec sleep 30
                """.replace("PIDFILE", pidFile.toString()));

        IOException thrown = assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
                assertThrows(IOException.class,
                        () -> SpawnedService.start(child, "", TEST_READY_TIMEOUT)));

        assertThat(thrown)
                .as("the message is the whole value of failing rather than hanging: it names the "
                        + "child, the line it owed and the window it had")
                .hasMessageContaining(child.getFileName().toString())
                .hasMessageContaining("listening on")
                .hasMessageContaining(TEST_READY_TIMEOUT.toSeconds() + "s");

        assertThat(childOf(pidFile))
                .as("the timeout path has to destroy the child; a helper that leaks a process is "
                        + "the specific problem this class was written to end")
                .satisfies(pid -> assertThat(waitForExit(pid)).isTrue());

        assertThat(leftoverConfigsFor(child))
                .as("the temporary TOML is deleted on the timeout path, as on every other")
                .isEmpty();
    }

    @Test
    @DisplayName("a child that floods stderr still lets runOnce return its stdout")
    void aStderrFloodDoesNotWedgeRunOnce() throws IOException {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "a POSIX shell to script a child with");

        Path noise = work.resolve("noise.txt");
        Files.writeString(noise, "E".repeat(STDERR_FLOOD_BYTES), StandardCharsets.UTF_8);
        Path child = script("nodera-chatty-child", """
                cat 'NOISE' >&2
                printf 'schema-line\\n'
                """.replace("NOISE", noise.toString()));

        Optional<String> out = assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> SpawnedService.runOnce(child));

        assertThat(out)
                .as("the child exits 0 after writing a megabyte to fd 2, so the caller gets its "
                        + "stdout; before the fix nobody drained fd 2 and it never got that far")
                .contains("schema-line\n");
        assertThat(out.orElseThrow())
                .as("runOnce is for --print-schema, where the caller wants stdout and only stdout")
                .doesNotContain("EEEE");
    }

    private Path script(String name, String body) throws IOException {
        Path file = work.resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    /** The child's own pid, which survives the {@code exec} that replaces the shell with sleep. */
    private static long childOf(Path pidFile) throws IOException {
        return Long.parseLong(Files.readString(pidFile).trim());
    }

    private static boolean waitForExit(long pid) {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (handle.isPresent() && handle.orElseThrow().isAlive()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** The configuration {@code start} writes lands in the JVM's temp directory, named for it. */
    private static List<Path> leftoverConfigsFor(Path child) throws IOException {
        Path temp = Path.of(System.getProperty("java.io.tmpdir"));
        String prefix = child.getFileName().toString();
        try (Stream<Path> entries = Files.list(temp)) {
            return entries
                    .filter(entry -> entry.getFileName().toString().startsWith(prefix)
                            && entry.getFileName().toString().endsWith(".toml"))
                    .toList();
        }
    }
}
