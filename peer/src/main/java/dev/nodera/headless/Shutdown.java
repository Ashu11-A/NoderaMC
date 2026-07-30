package dev.nodera.headless;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Stopping a background lane, and meaning it.
 *
 * <p>{@code shutdownNow()} only <i>asks</i>: it interrupts the worker thread and returns at once,
 * so a task that is halfway through writing an archive, a piece or a telemetry window keeps writing
 * for a while afterwards. Every lane here writes into a directory that its owner tears down right
 * after closing it — the world's store on unload, a test's temporary directory on the way out — and
 * a close that returns early hands that directory back while a thread is still creating files in
 * it. That race is invisible when it wins and looks like a file-system fault when it loses.
 */
final class Shutdown {

    /** Bounded on purpose: a lane that will not stop must not hold a worker shutdown open. */
    static final long DEFAULT_WAIT_MILLIS = 2_000L;

    private Shutdown() {
    }

    /**
     * Interrupt {@code executor} and wait, briefly, for whatever it was doing to actually stop.
     *
     * @return {@code true} if the executor came to rest within the wait.
     */
    static boolean stopAndWait(ExecutorService executor) {
        return stopAndWait(executor, DEFAULT_WAIT_MILLIS);
    }

    /** @see #stopAndWait(ExecutorService) */
    static boolean stopAndWait(ExecutorService executor, long waitMillis) {
        if (executor == null) {
            return true;
        }
        executor.shutdownNow();
        try {
            return executor.awaitTermination(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
