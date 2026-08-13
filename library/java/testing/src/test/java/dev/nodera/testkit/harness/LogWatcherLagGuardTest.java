package dev.nodera.testkit.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A wait that expires because the MACHINE could not keep up must say so.
 *
 * <h2>The failure this covers</h2>
 *
 * <p>On 2026-08-04 three consecutive {@code continuity} runs on an idle 14 GB box put the host's
 * integrated server 53 s / 1066 ticks behind. Vanilla said so in its own log — {@code Can't keep up!
 * Is the server overloaded? Running 53012ms or 1066 ticks behind} — the joiner's <b>vanilla</b>
 * connection timed out 35 s before the host finished planning its lane, and the scenario then failed
 * with {@code waited 180s for 'client validation lane active'}. That message names the validation
 * lane and says nothing about the machine, so a capacity failure read as a product defect for three
 * runs. No harness code anywhere grepped for that line.
 *
 * <h2>Why the negative case is the important one</h2>
 *
 * <p>A guard that fires too readily is worse than none: it converts every real lane bug into "blame
 * the machine", which is the same class of wrong answer pointing the other way. So a log with no
 * overload line, and a log whose overload is a brief chunk-generation hiccup, must both still
 * produce the ordinary failure.
 *
 * <p>Thread-context: ordinary JUnit; each test writes its own synthetic log.
 */
class LogWatcherLagGuardTest {

    private static final String NEEDLE = "client validation lane active";

    @Test
    void anOverloadedHostIsNamedInsteadOfTheLane(@TempDir Path dir) throws IOException {
        Path hostLog = write(dir, "host.log", """
                [Server thread/INFO]: Preparing spawn area
                [Server thread/WARN]: Can't keep up! Is the server overloaded? \
                Running 53012ms or 1066 ticks behind
                """);
        Path joinLog = write(dir, "join.log", "[Render thread/INFO]: Loading world\n");

        assertThatThrownBy(() -> watcher(joinLog)
                .awaitWithLagGuard(NEEDLE, Duration.ofMillis(1), hostLog))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("the host server fell 1066 ticks behind")
                .hasMessageContaining("the machine, not the lane")
                .hasMessageContaining(NEEDLE);
    }

    @Test
    void aLogWithNoOverloadStillFailsOnTheNeedle(@TempDir Path dir) throws IOException {
        Path hostLog = write(dir, "host.log", """
                [Server thread/INFO]: Preparing spawn area
                [Server thread/INFO]: Done (12.4s)!
                """);
        Path joinLog = write(dir, "join.log", "[Render thread/INFO]: Loading world\n");

        assertThatThrownBy(() -> watcher(joinLog)
                .awaitWithLagGuard(NEEDLE, Duration.ofMillis(1), hostLog))
                .isInstanceOf(HarnessException.class)
                .as("with no overload in the host's log the lane is still the honest suspect — a "
                        + "guard that blamed the machine here would hide every real lane bug")
                .hasMessageContaining("it never appeared")
                .hasMessageNotContaining("ticks behind");
    }

    @Test
    void aBriefHiccupIsBelowTheThresholdAndExplainsNothing(@TempDir Path dir) throws IOException {
        Path hostLog = write(dir, "host.log",
                "[Server thread/WARN]: Can't keep up! Is the server overloaded? "
                        + "Running 2100ms or 42 ticks behind\n");
        Path joinLog = write(dir, "join.log", "");

        assertThatThrownBy(() -> watcher(joinLog)
                .awaitWithLagGuard(NEEDLE, Duration.ofMillis(1), hostLog))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("it never appeared")
                .hasMessageNotContaining("ticks behind");
    }

    @Test
    void theNeedleArrivingMeansNoFailureAtAll(@TempDir Path dir) throws IOException {
        Path hostLog = write(dir, "host.log",
                "Can't keep up! Is the server overloaded? Running 53012ms or 1066 ticks behind\n");
        Path joinLog = write(dir, "join.log", "[Render thread/INFO]: " + NEEDLE + " on 4 region(s)\n");

        watcher(joinLog).awaitWithLagGuard(NEEDLE, Duration.ofSeconds(1), hostLog);
    }

    @Test
    void theWorstOverloadIsTheOneReported(@TempDir Path dir) throws IOException {
        Path hostLog = write(dir, "host.log", """
                Can't keep up! Is the server overloaded? Running 2100ms or 42 ticks behind
                Can't keep up! Is the server overloaded? Running 53012ms or 1066 ticks behind
                Can't keep up! Is the server overloaded? Running 6000ms or 120 ticks behind
                """);

        assertThat(watcher(hostLog).ticksBehind()).hasValue(1066);
    }

    @Test
    void aHealthyLogReportsNoLagAtAll(@TempDir Path dir) throws IOException {
        assertThat(watcher(write(dir, "host.log", "Done (12.4s)!\n")).ticksBehind()).isEmpty();
    }

    /** A host log that was never written is not evidence of anything. */
    @Test
    void anAbsentHostLogFallsBackToTheOrdinaryMessage(@TempDir Path dir) throws IOException {
        Path joinLog = write(dir, "join.log", "");

        assertThatThrownBy(() -> watcher(joinLog)
                .awaitWithLagGuard(NEEDLE, Duration.ofMillis(1), dir.resolve("nothing-here.log")))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("it never appeared");
    }

    // ---------------------------------------------------------------------------------------

    private static LogWatcher watcher(Path file) {
        return new LogWatcher(file, Topology.standard());
    }

    private static Path write(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
