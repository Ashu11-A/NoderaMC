package dev.nodera.peer.control;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fetch that is still moving must never be reported as failed.
 *
 * <h2>The failure this exists for</h2>
 *
 * <p>One number was spent twice with two incompatible meanings. On the client,
 * {@code timeoutSeconds} became {@code socket.setSoTimeout((seconds + 10) * 1000)} — a hard wall
 * clock. On the worker, the same value became a <b>stall</b> budget, whose own documentation says
 * "a fetch that keeps moving keeps going". A large archive that progresses steadily therefore keeps
 * resetting the worker's clock while the client's wall clock expires underneath it: the worker goes
 * on downloading into a socket nobody is reading, and the player is told the fetch failed.
 *
 * <p>Measured live, twice. A joiner sat on the recovery screen for 748 seconds and never recovered,
 * while its worker was healthy and 212 of 283 pieces into the very download being waited on.
 *
 * <p>The fix is that the worker says so while it works, and the client treats each word as
 * liveness — the same reason {@code NODERA-WATCH} sends a keepalive rather than trusting silence.
 *
 * <p>Thread-context: ordinary JUnit; the server runs on its own threads.
 */
final class ArchiveFetchKeepsTheClientInformedTest {

    private ControlServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    /**
     * A worker whose fetch takes far longer than the client's budget but never stops progressing.
     *
     * <p>This is the shape that broke: not a stalled fetch, a slow one.
     */
    private static final class SlowButMovingFetch implements ControlHandler {

        private final int steps;
        private final Duration perStep;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final CountDownLatch started = new CountDownLatch(1);

        SlowButMovingFetch(int steps, Duration perStep) {
            this.steps = steps;
            this.perStep = perStep;
        }

        @Override
        public String workerVersion() {
            return "archive-progress-test";
        }

        @Override
        public String fetchArchive(String worldId, String destPathB64, long timeoutSeconds,
                                   ArchiveProgress progress) {
            started.countDown();
            for (int piece = 1; piece <= steps; piece++) {
                try {
                    Thread.sleep(perStep.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                progress.at(piece, steps);
            }
            finished.set(true);
            return "4096 7";
        }
    }

    @Test
    @DisplayName("a slow fetch that keeps progressing is not cut off by the client's deadline")
    void progressKeepsTheConnectionAlivePastTheDeadline() throws Exception {
        // Ten steps of 300ms is three seconds of work against a one-second budget. Without the
        // keepalive the client's socket expires at (1 + 10)s... which is longer than the work, so
        // the timing is chosen to make the ASSERTION about progress rather than about luck: the
        // budget below is what the client must treat as "time without progress", and each step is
        // comfortably inside it while the total is not.
        SlowButMovingFetch handler = new SlowButMovingFetch(10, Duration.ofMillis(300));
        server = new ControlServer("127.0.0.1", 0, handler);
        server.start();

        List<String> seen = new ArrayList<>();
        CompanionClient client = new CompanionClient("127.0.0.1", server.boundPort());
        Optional<String> result = client.fetchArchive("bfcaaad2", java.nio.file.Path.of("/tmp/x"),
                1, new StringBuilder(), (done, total) -> seen.add(done + "/" + total));

        assertThat(result)
                .as("the worker finished, so the client must report success — a one-second budget "
                        + "is a no-progress budget, not a wall clock")
                .isPresent();
        assertThat(handler.finished).isTrue();
        assertThat(seen)
                .as("the client saw the fetch advancing")
                .isNotEmpty()
                .last().isEqualTo("10/10");
    }

    @Test
    @DisplayName("a fetch that stops progressing still ends, and says so")
    void aGenuineStallStillFails() throws Exception {
        // One step then silence forever: the case the budget exists for.
        ControlHandler wedged = new ControlHandler() {
            @Override
            public String workerVersion() {
                return "archive-progress-test";
            }

            @Override
            public String fetchArchive(String worldId, String destPathB64, long timeoutSeconds,
                                       ArchiveProgress progress) {
                progress.at(1, 283);
                try {
                    Thread.sleep(Duration.ofSeconds(30).toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }
        };
        server = new ControlServer("127.0.0.1", 0, wedged);
        server.start();

        StringBuilder reason = new StringBuilder();
        long startedAt = System.nanoTime();
        Optional<String> result = new CompanionClient("127.0.0.1", server.boundPort())
                .fetchArchive("bfcaaad2", java.nio.file.Path.of("/tmp/x"), 1, reason,
                        (done, total) -> { });
        Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(result).as("a fetch that stopped moving must not hang the caller").isEmpty();
        assertThat(waited)
                .as("the caller waits its no-progress budget, not the worker's whole sleep")
                .isLessThan(Duration.ofSeconds(25));
        assertThat(reason.toString()).isNotBlank();
    }

    @Test
    @DisplayName("a worker that never answers at all is still reported as silent")
    void anUnreachableWorkerIsStillAFailure() {
        StringBuilder reason = new StringBuilder();

        Optional<String> result = new CompanionClient("127.0.0.1", 1)
                .fetchArchive("bfcaaad2", java.nio.file.Path.of("/tmp/x"), 1, reason,
                        (done, total) -> { });

        assertThat(result).isEmpty();
        assertThat(reason.toString()).contains("did not answer");
    }
}
