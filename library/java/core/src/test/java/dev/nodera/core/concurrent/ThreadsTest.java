package dev.nodera.core.concurrent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Threads must actually run, on whichever runtime this is.
 *
 * <p>These assertions look almost too simple to write down, and that is the point: the bug they
 * guard against was code that <i>created</i> threads successfully on Android and then threw when
 * starting them, so every test that only checked "the object was constructed" passed while the peer
 * could not open a socket. What matters is that the body executes.
 */
final class ThreadsTest {

    @Test
    @DisplayName("a started thread runs its body, whatever the runtime provides")
    void aStartedThreadRuns() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);

        Thread thread = Threads.start("nodera-test", ran::countDown);

        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        thread.join(java.time.Duration.ofSeconds(5));
        assertThat(thread.isAlive()).isFalse();
    }

    @Test
    @DisplayName("an unstarted thread does not run until it is started")
    void anUnstartedThreadWaits() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);

        Thread thread = Threads.unstarted("nodera-test-unstarted", ran::countDown);

        assertThat(ran.getCount()).isEqualTo(1);
        thread.start();
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("the task executor runs submitted work")
    void theExecutorRunsWork() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();

        try (ExecutorService executor = Threads.newTaskExecutor()) {
            executor.submit(() -> seen.set("ran")).get(5, TimeUnit.SECONDS);
        }

        assertThat(seen.get()).isEqualTo("ran");
    }

    @Test
    @DisplayName("service threads are daemons on every runtime")
    void serviceThreadsNeverHoldTheProcessOpen() {
        // Virtual threads are always daemons. The platform fallback has to match, or a phone build
        // would keep the process alive on threads a desktop build lets go.
        Thread thread = Threads.unstarted("nodera-test-daemon", () -> {});

        assertThat(thread.isDaemon()).isTrue();
    }

    @Test
    @DisplayName("the probe answers, and answers the same way every time")
    void theProbeIsStable() {
        boolean first = Threads.virtualThreadsAvailable();

        assertThat(Threads.virtualThreadsAvailable()).isEqualTo(first);
    }
}
