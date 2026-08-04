package dev.nodera.testkit.scenario;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Finding one run's game JVM among every process on the machine.
 *
 * <h2>Why this spawns real processes instead of faking a command line</h2>
 *
 * <p>The defect these tests exist for is not a logic error that a stub could reproduce: it is that
 * {@code ProcessHandle.info().commandLine()} <b>truncates at 4096 bytes</b> on Linux, while a
 * ModDevGradle client JVM's command line is about 16 KB and carries the token identifying its run in
 * the <b>last</b> argument — measured in the field at byte 16579 of 16608. Nothing but a real process
 * with a real oversized argv puts that behaviour under test, and a fake would have gone on passing
 * for exactly as long as the bug did.
 *
 * <p>What it cost while unnoticed: {@code runJvmAlive} answered "dead" for every live client, so a
 * live suite reported a host-departure stage PASS while the host was still running; {@code
 * killRunJvms} killed nothing, so a crash scenario's SIGKILL was really a graceful stop — the one
 * path it exists not to exercise; and {@code awaitRunJvmsGone} returned instantly having found
 * nothing to wait for.
 *
 * <p>Thread-context: ordinary JUnit. Every spawned child is reaped in a finally block.
 */
class HostWorldSupportTest {

    /** Enough argv bytes to push the token past the JDK's 4096-byte view, as the real runs do. */
    private static final int PADDING_BYTES = 6000;

    @Test
    void findsAJvmWhoseRunTokenSitsPastTheTruncationLimit() throws IOException {
        assumeProcFs();
        String token = "clientHostRunProgramArgs-" + UUID.randomUUID();
        Process child = spawnWithTrailingToken(token, null);
        try {
            assertThat(HostWorldSupport.runJvmAlive(token))
                    .as("a token in the last argument of an oversized command line must still match "
                            + "— this is the assertion ProcessHandle.info().commandLine() fails")
                    .isTrue();
            assertThat(HostWorldSupport.findRunJvm(token)).hasValue(child.pid());
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void neverMatchesTheGradleDaemonEvenWhenItCarriesTheToken() throws IOException {
        assumeProcFs();
        String token = "clientJoinRunProgramArgs-" + UUID.randomUUID();
        // The daemon serves every build in a run, so its command line names BOTH clients' argfiles.
        // Signalling it takes down every client at once — the failure that first looked like "the
        // host died when the joiner left".
        Process daemon = spawnWithTrailingToken(token, "GradleDaemon");
        try {
            assertThat(HostWorldSupport.runJvmAlive(token))
                    .as("a process identifying itself as a Gradle daemon must never be a match, "
                            + "however many run tokens it carries")
                    .isFalse();
        } finally {
            daemon.destroyForcibly();
        }
    }

    @Test
    void anAbsentRunIsSimplyAbsent() {
        assumeProcFs();

        assertThat(HostWorldSupport.runJvmAlive("clientNobodyRunProgramArgs-" + UUID.randomUUID()))
                .isFalse();
    }

    // ---------------------------------------------------------------------------------------

    /**
     * A sleeping child whose argv is longer than the JDK will report and whose LAST argument is
     * {@code token}.
     *
     * <p>{@code sh -c <script> <args…>} keeps every trailing argument in its command line and
     * ignores them, which is the cheapest way to build an argv of a chosen shape.
     *
     * @param marker an extra argument placed BEFORE the padding, or {@code null} for none.
     */
    private static Process spawnWithTrailingToken(String token, String marker) throws IOException {
        List<String> command = new ArrayList<>(List.of("/bin/sh", "-c", "sleep 30"));
        if (marker != null) {
            command.add(marker);
        }
        String filler = "x".repeat(200);
        for (int written = 0; written < PADDING_BYTES; written += filler.length()) {
            command.add(filler);
        }
        command.add(token);
        Process process = new ProcessBuilder(command).start();
        awaitCommandLine(process);
        return process;
    }

    /** Wait until /proc has the child's command line, so a test never races its own subprocess. */
    private static void awaitCommandLine(Process process) {
        Path cmdline = Path.of("/proc", String.valueOf(process.pid()), "cmdline");
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                if (Files.isReadable(cmdline) && Files.size(cmdline) > PADDING_BYTES) {
                    return;
                }
                Thread.sleep(20);
            } catch (IOException notYet) {
                // The child is still being set up; try again.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * These tests describe a Linux behaviour and are meaningless elsewhere.
     *
     * <p>A skip here is a skip, not a pass — if this ever starts skipping on CI, the coverage is
     * gone and nothing will say so.
     */
    private static void assumeProcFs() {
        assumeTrue(Files.isDirectory(Path.of("/proc")),
                "no /proc — the command-line truncation this covers is a Linux behaviour");
    }
}
