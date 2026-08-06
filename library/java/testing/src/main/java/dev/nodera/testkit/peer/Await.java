package dev.nodera.testkit.peer;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Polling for the asynchronous half of a mesh test.
 *
 * <p>Every transport in this repository delivers off the sender's thread, so an assertion made
 * immediately after a send is an assertion about the scheduler. This is the one place that waiting
 * is written; before it existed the same deadline loop appeared in a dozen integration tests, each
 * with its own timeout and its own idea of what to say when it expired.
 *
 * <p>There are deliberately <b>two</b> shapes. {@link #until} fails with its own message and is for
 * a wait whose expiry IS the failure. {@link #quietly} returns instead, and is for a test that
 * wants to make its own assertion afterwards — a shared helper that raised its own error there
 * would replace a test's carefully-worded failure with the harness's generic one.
 *
 * <p>Thread-context: any thread.
 */
public final class Await {

    private static final long POLL_MILLIS = 20L;

    private Await() {}

    /**
     * Wait for {@code condition}, failing if it never holds.
     *
     * @param what          what is being waited for, named in the failure.
     * @param timeoutMillis how long to wait.
     * @param condition     polled every 20 ms.
     * @throws AssertionError if the deadline passes with the condition still false.
     */
    public static void until(String what, long timeoutMillis, BooleanSupplier condition) {
        until(what, timeoutMillis, condition, () -> "");
    }

    /**
     * As {@link #until}, with a state dump evaluated only ON FAILURE — the difference between a CI
     * flake you can diagnose and one you can only re-run.
     *
     * @param detail supplies extra context for the failure message.
     */
    public static void until(String what, long timeoutMillis, BooleanSupplier condition,
                             Supplier<String> detail) {
        if (quietly(timeoutMillis, condition)) {
            return;
        }
        throw new AssertionError("timed out after " + timeoutMillis + " ms waiting for: " + what
                + " — " + detail.get());
    }

    /**
     * Wait for {@code condition} and report whether it held, without failing.
     *
     * @return {@code true} if the condition became true before the deadline.
     */
    public static boolean quietly(long timeoutMillis, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleep(POLL_MILLIS);
        }
        return condition.getAsBoolean();
    }

    /** Sleep, turning an interrupt into a failure rather than swallowing it. */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", e);
        }
    }
}
