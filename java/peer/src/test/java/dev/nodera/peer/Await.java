package dev.nodera.peer;

import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.fail;

/** Tiny polling helper for the asynchronous, multi-threaded runtime tests. */
final class Await {

    private Await() {}

    static void until(String what, long timeoutMillis, BooleanSupplier condition) {
        until(what, timeoutMillis, condition, () -> "");
    }

    /** Variant with a state-dump supplier evaluated ON FAILURE (CI flake forensics). */
    static void until(String what, long timeoutMillis, BooleanSupplier condition,
                      java.util.function.Supplier<String> detail) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(20);
        }
        if (!condition.getAsBoolean()) {
            fail("timed out after " + timeoutMillis + " ms waiting for: " + what
                    + " — " + detail.get());
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
