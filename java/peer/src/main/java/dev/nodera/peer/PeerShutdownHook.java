package dev.nodera.peer;

import dev.nodera.core.identity.NodeId;
import dev.nodera.distribution.EmergencyFlush;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * One idempotent Task-24 shutdown path: flush physical replicas, then send goodbye/stop runtime.
 *
 * <p>The same instance is called from NeoForge's graceful stop event and a JVM shutdown hook. A
 * compare-and-set future makes those paths share one execution, so overlapping callbacks cannot
 * transfer pieces twice or stop the transport before the winning flush finishes.
 *
 * <p>{@code EmergencyFlush}'s single absolute deadline bounds unreachable replica waits. Once that
 * budget expires, shutdown continues through {@code afterFlush}; the hook is defence-in-depth and
 * never overrides Task-21 redundancy by refusing process exit.
 *
 * <p>Thread-context: thread-safe. At most one caller performs shutdown work; other callers await the
 * same result.
 */
public final class PeerShutdownHook implements AutoCloseable {

    /**
     * @param flush result from the single emergency flush execution.
     * @param afterFlushCompleted whether goodbye/runtime stop returned normally.
     */
    public record ShutdownResult(
            EmergencyFlush.FlushResult flush,
            boolean afterFlushCompleted) {
        public ShutdownResult {
            Objects.requireNonNull(flush, "flush");
        }
    }

    private final NodeId self;
    private final EmergencyFlush emergencyFlush;
    private final Supplier<List<EmergencyFlush.LocalHolding>> holdings;
    private final Duration flushBudget;
    private final Runnable afterFlush;
    private final AtomicReference<CompletableFuture<ShutdownResult>> shutdown =
            new AtomicReference<>();
    private final AtomicBoolean registered = new AtomicBoolean();
    private final Thread hookThread;

    /**
     * @param self local peer id excluded from surviving replica counts and replacement targets.
     * @param emergencyFlush deadline-bound physical flush service.
     * @param holdings supplies a current immutable snapshot of local holdings at shutdown time.
     * @param flushBudget whole emergency-flush budget.
     * @param afterFlush best-effort goodbye plus runtime/transport stop, executed after flush even
     *                   when the flush times out or fails.
     */
    public PeerShutdownHook(
            NodeId self,
            EmergencyFlush emergencyFlush,
            Supplier<List<EmergencyFlush.LocalHolding>> holdings,
            Duration flushBudget,
            Runnable afterFlush) {
        this.self = Objects.requireNonNull(self, "self");
        this.emergencyFlush = Objects.requireNonNull(emergencyFlush, "emergencyFlush");
        this.holdings = Objects.requireNonNull(holdings, "holdings");
        this.flushBudget = Objects.requireNonNull(flushBudget, "flushBudget");
        if (flushBudget.isNegative()) {
            throw new IllegalArgumentException("flushBudget must not be negative: " + flushBudget);
        }
        this.afterFlush = Objects.requireNonNull(afterFlush, "afterFlush");
        this.hookThread = new Thread(this::runFromJvm, "nodera-emergency-flush-" + self);
    }

    /**
     * Register the JVM hook exactly once.
     *
     * @return {@code true} when this call registered it; false when already registered.
     * @throws IllegalStateException if JVM shutdown has already begun.
     */
    public boolean register() {
        if (!registered.compareAndSet(false, true)) {
            return false;
        }
        try {
            Runtime.getRuntime().addShutdownHook(hookThread);
            return true;
        } catch (RuntimeException e) {
            registered.set(false);
            throw e;
        }
    }

    /**
     * Run graceful shutdown through the same once-only path as the JVM hook.
     *
     * @return the shared shutdown result.
     */
    public ShutdownResult gracefulShutdown() {
        ShutdownResult result = runOnce();
        unregisterHook();
        return result;
    }

    /** @return whether the once-only shutdown sequence has started. */
    public boolean hasStarted() {
        return shutdown.get() != null;
    }

    /**
     * Graceful close has the same ordering contract as {@link #gracefulShutdown()}.
     */
    @Override
    public void close() {
        gracefulShutdown();
    }

    private void runFromJvm() {
        try {
            runOnce();
        } catch (RuntimeException ignored) {
            // JVM shutdown must continue. Runtime hooks cannot report a useful failure to callers.
        }
    }

    private ShutdownResult runOnce() {
        CompletableFuture<ShutdownResult> mine = new CompletableFuture<>();
        CompletableFuture<ShutdownResult> existing = shutdown.compareAndExchange(null, mine);
        if (existing != null) {
            return join(existing);
        }

        EmergencyFlush.FlushResult flushResult;
        boolean stopped = false;
        try {
            List<EmergencyFlush.LocalHolding> snapshot = List.copyOf(
                    Objects.requireNonNull(holdings.get(), "holdings supplier returned null"));
            flushResult = emergencyFlush.flush(self, snapshot, flushBudget);
        } catch (RuntimeException flushFailure) {
            // Even an unexpected planner/transport failure must not skip goodbye/runtime stop.
            try {
                afterFlush.run();
                stopped = true;
            } catch (RuntimeException stopFailure) {
                flushFailure.addSuppressed(stopFailure);
            }
            mine.completeExceptionally(flushFailure);
            throw flushFailure;
        }

        try {
            afterFlush.run();
            stopped = true;
            ShutdownResult result = new ShutdownResult(flushResult, true);
            mine.complete(result);
            return result;
        } catch (RuntimeException stopFailure) {
            ShutdownResult result = new ShutdownResult(flushResult, stopped);
            mine.completeExceptionally(stopFailure);
            throw stopFailure;
        }
    }

    private void unregisterHook() {
        if (!registered.compareAndSet(true, false)) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hookThread);
        } catch (IllegalStateException ignored) {
            // JVM shutdown is already running this hook.
        }
    }

    private static ShutdownResult join(CompletableFuture<ShutdownResult> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }
}
