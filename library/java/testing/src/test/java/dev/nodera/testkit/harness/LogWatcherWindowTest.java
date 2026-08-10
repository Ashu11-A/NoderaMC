package dev.nodera.testkit.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a WINDOW of a log — the assertions a "wait for a needle" cannot express.
 *
 * <p>Two live scenarios assert on the ABSENCE of a pattern after a mark: the re-key lane's plaintext
 * watch (L-59) and the determinism soak's post-drive error audit. An absence is not something a wait
 * can prove, so these reads exist — and until this landed they lived in two scenario support
 * classes, with the error-audit rule written out three times. Three copies of one regex is three
 * chances for a benign cause added to one of them to fix a third of the suites.
 *
 * <p>Thread-context: ordinary JUnit; each test writes its own synthetic log.
 */
class LogWatcherWindowTest {

    @Test
    void aMarkExcludesEverythingBeforeIt(@TempDir Path dir) throws IOException {
        LogWatcher log = watcher(write(dir, """
                bring-up: sharing world
                bring-up: JoinerDev joined the game
                --- mark ---
                soak: JoinerDev joined the game
                """));

        assertThat(log.linesAfter(3)).containsExactly("soak: JoinerDev joined the game");
        assertThat(log.containsAfter("bring-up", 3)).isFalse();
        assertThat(log.containsAfter("soak", 3)).isTrue();
        assertThat(log.matchesAfter("joined the game", 0)).hasSize(2);
        assertThat(log.matchesAfter("joined the game", 3)).hasSize(1);
    }

    @Test
    void aMarkPastTheEndIsAnEmptyWindowRatherThanAFailure(@TempDir Path dir) throws IOException {
        LogWatcher log = watcher(write(dir, "one line\n"));

        assertThat(log.linesAfter(99)).isEmpty();
        assertThat(log.containsAfter("one", 99)).isFalse();
        assertThat(log.lastMatchAfter("one", 99)).isEmpty();
    }

    @Test
    void theLastMatchIsTheLastOneInTheWindow(@TempDir Path dir) throws IOException {
        LogWatcher log = watcher(write(dir, """
                client validation lane active on 1 region(s)
                client validation lane active on 7 region(s)
                """));

        assertThat(log.lastMatchAfter("lane active", 0)).hasValueSatisfying(line ->
                assertThat(line).contains("7 region(s)"));
    }

    /** The shell's {@code grep -i} assertions: the crash scenario greps a message it did not write. */
    @Test
    void caseInsensitiveReadsFindALineWhoseCaseWasNeverGuaranteed(@TempDir Path dir)
            throws IOException {
        LogWatcher log = watcher(write(dir, "Nodera: Re-Opening It Locally\n"));

        assertThat(log.containsAfter("re-opening it locally", 0)).isFalse();
        assertThat(log.containsAfterIgnoringCase("re-opening it locally", 0)).isTrue();
    }

    @Test
    void anAuditFromAMarkIgnoresTheBringUpThatPrecededIt(@TempDir Path dir) throws IOException {
        LogWatcher log = watcher(write(dir, """
                [main/ERROR]: something broke during bring-up
                --- mark ---
                [main/ERROR]: the soak broke something
                """));

        assertThat(log.auditErrorsAfter(LogWatcher.BENIGN_ERRORS, LogWatcher.BENIGN_NETTY, 0))
                .hasSize(2);
        assertThat(log.auditErrorsAfter(LogWatcher.BENIGN_ERRORS, LogWatcher.BENIGN_NETTY, 2))
                .singleElement().asString().contains("the soak broke something");
    }

    /**
     * A netty header is benign exactly when its CAUSE is a peer this harness killed.
     *
     * <p>The audit reads the following line rather than the header, which is why the header alone is
     * not the assertion — every crash and continuity scenario produces one on purpose.
     */
    @Test
    void aNettyHeaderIsJudgedByTheLineBelowIt(@TempDir Path dir) throws IOException {
        LogWatcher benign = watcher(write(dir.resolve("a"), """
                [netty/ERROR]: Exception caught in connection
                java.io.IOException: Connection reset by peer
                """));
        LogWatcher real = watcher(write(dir.resolve("b"), """
                [netty/ERROR]: Exception caught in connection
                java.lang.NullPointerException: the region was null
                """));

        assertThat(benign.auditErrorsAfter(LogWatcher.BENIGN_ERRORS, LogWatcher.BENIGN_NETTY, 0))
                .isEmpty();
        assertThat(real.auditErrorsAfter(LogWatcher.BENIGN_ERRORS, LogWatcher.BENIGN_NETTY, 0))
                .hasSize(2);
    }

    @Test
    void anUnreadableFileIsAnEmptyWindowAndNeverAnException(@TempDir Path dir) {
        LogWatcher log = watcher(dir.resolve("never-written.log"));

        assertThat(log.lines()).isEmpty();
        assertThat(log.linesAfter(0)).isEmpty();
        assertThat(log.auditErrorsAfter(LogWatcher.BENIGN_ERRORS, LogWatcher.BENIGN_NETTY, 0))
                .isEmpty();
    }

    // ---------------------------------------------------------------------------------------

    private static LogWatcher watcher(Path file) {
        return LogWatcher.reader(file);
    }

    private static Path write(Path dir, String content) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("latest.log");
        Files.writeString(file, content);
        return file;
    }
}
