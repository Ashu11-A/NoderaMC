package dev.nodera.core.concurrent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The property that matters is not "exceptions are caught" — it is that the <b>schedule survives</b>.
 * A `scheduleWithFixedDelay` task is cancelled permanently the first time it throws, and the
 * cancellation is reported only into a `Future` nobody reads, so the failure mode is a task that
 * stops forever on a process that goes on looking healthy. So the first test uses a real scheduler
 * and asserts the tick <i>after</i> the throw, which a plain try/catch test would not have caught.
 */
final class RecurringTest {

    @Test
    @DisplayName("a throwing tick does not cancel the schedule")
    void theScheduleSurvivesAThrow() throws Exception {
        AtomicInteger ticks = new AtomicInteger();
        CountDownLatch ranAgain = new CountDownLatch(3);
        List<RuntimeException> reported = new ArrayList<>();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            scheduler.scheduleWithFixedDelay(
                    Recurring.survivable(
                            () -> {
                                ticks.incrementAndGet();
                                throw new IllegalStateException("every single tick fails");
                            },
                            // The latch is released HERE and not in the body. It used to count down
                            // before the throw, which made the third release happen a moment before
                            // the third failure was recorded — so `await` could return with only two
                            // entries in `reported` and the size assertion below failed roughly one
                            // run in a hundred, on CI and never locally. Releasing from the reporter
                            // makes the latch mean what the assertions need it to mean: three
                            // failures have been *reported*, not three ticks have started.
                            failure -> {
                                synchronized (reported) {
                                    reported.add(failure);
                                }
                                ranAgain.countDown();
                            }),
                    0, 5, TimeUnit.MILLISECONDS);

            assertThat(ranAgain.await(5, TimeUnit.SECONDS))
                    .as("without the wrapper the scheduler cancels after the first throw and this "
                            + "latch never reaches zero")
                    .isTrue();
        } finally {
            scheduler.shutdownNow();
        }

        assertThat(ticks.get()).isGreaterThanOrEqualTo(3);
        synchronized (reported) {
            assertThat(reported)
                    .as("every failure is reported, so a task that is failing every tick is visible "
                            + "rather than merely surviving")
                    .hasSizeGreaterThanOrEqualTo(3)
                    .allSatisfy(e -> assertThat(e).hasMessage("every single tick fails"));
        }
    }

    @Test
    @DisplayName("a tick that succeeds reports nothing")
    void successIsSilent() {
        AtomicInteger reported = new AtomicInteger();
        AtomicInteger ran = new AtomicInteger();

        Recurring.survivable(ran::incrementAndGet, failure -> reported.incrementAndGet()).run();

        assertThat(ran.get()).isEqualTo(1);
        assertThat(reported.get()).isZero();
    }

    @Test
    @DisplayName("an Error is not swallowed — a dead JVM must not keep looking alive")
    void errorsPropagate() {
        assertThatThrownBy(() -> Recurring.survivable(
                () -> {
                    throw new OutOfMemoryError("heap");
                },
                failure -> {
                    throw new AssertionError("an Error must not be reported as a survivable failure");
                }).run())
                .isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    @DisplayName("the arguments are required, so a null sink cannot silently discard failures")
    void bothArgumentsAreRequired() {
        assertThatThrownBy(() -> Recurring.survivable(null, e -> { }))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Recurring.survivable(() -> { }, null))
                .isInstanceOf(NullPointerException.class);
    }
}
