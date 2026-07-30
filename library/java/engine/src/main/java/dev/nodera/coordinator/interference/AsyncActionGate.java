package dev.nodera.coordinator.interference;

import dev.nodera.core.action.ActionEnvelope;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;

/**
 * The LEGAL asynchronous mutation API (Task 16 / L-25): any thread may submit a signed
 * {@link ActionEnvelope}; the server thread drains the gate once per tick and feeds the actions
 * into the validated lane like any player action — same validation, same committee, same root.
 * Off-thread DIRECT writes stay illegal ({@link MutationGuard.AsyncWriteException}); this gate is
 * what the error message tells the caller to use instead.
 *
 * <p>Ordering is FIFO per gate and the drain runs on the single server thread, so the submitted
 * actions enter the batch in one deterministic order — asynchrony ends at the gate, determinism
 * begins at the drain.
 *
 * @Thread-context {@link #submit} from any thread; {@link #drainInto} from the server thread only.
 */
public final class AsyncActionGate {

    /** Bounded backlog: a runaway submitter degrades itself, never the server tick. */
    public static final int CAPACITY = 4096;

    private final ArrayBlockingQueue<ActionEnvelope> pending = new ArrayBlockingQueue<>(CAPACITY);

    /**
     * Submit one signed action from any thread.
     *
     * @return {@code true} when accepted; {@code false} when the gate is full (the caller backs
     *         off — the queue bound protects the tick, not the submitter).
     */
    public boolean submit(ActionEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        return pending.offer(envelope);
    }

    /**
     * Drain every pending action, in submission order, into {@code consumer} (the action lane).
     * Called once per tick on the server thread.
     *
     * @return how many actions were drained.
     */
    public int drainInto(Consumer<ActionEnvelope> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        List<ActionEnvelope> drained = new ArrayList<>();
        pending.drainTo(drained);
        for (ActionEnvelope envelope : drained) {
            consumer.accept(envelope);
        }
        return drained.size();
    }

    /** @return the current backlog size (telemetry). */
    public int backlog() {
        return pending.size();
    }
}
