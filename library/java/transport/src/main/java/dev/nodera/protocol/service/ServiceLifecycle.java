package dev.nodera.protocol.service;

import dev.nodera.core.crypto.CanonicalReader;

/**
 * Where a service is in its own lifecycle.
 *
 * <p>{@link #DRAINING} is the load-bearing value: it is how a service that is about to restart —
 * for an update, or because an operator asked — tells the network <b>before</b> it stops answering,
 * so peers migrate on their own schedule instead of discovering the outage by failing. Every other
 * value exists to make that one unambiguous.
 *
 * <p>Ordinals are the encoded form and are <b>frozen</b>: append, never reorder.
 *
 * <p>Thread-context: immutable enum, safe for any thread.
 */
public enum ServiceLifecycle {

    /** Bound and warming up; it may refuse work. */
    STARTING,

    /** Serving normally. */
    SERVING,

    /** Finishing existing work, refusing new work, about to stop at its drain deadline. */
    DRAINING,

    /** Stopped on purpose. A record in this state is a removal request, not an advertisement. */
    STOPPED;

    /**
     * Whether a peer should route <i>new</i> work to a service in this state.
     *
     * @return true only for {@link #SERVING}.
     * @Thread-context any thread.
     */
    public boolean acceptsNewWork() {
        return this == SERVING;
    }

    /**
     * Decode a frozen ordinal.
     *
     * @param r the canonical source, positioned at the {@code u8} ordinal.
     * @return the lifecycle state.
     * @throws IllegalStateException if the ordinal is not assigned.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static ServiceLifecycle decodeOrdinal(CanonicalReader r) {
        int ord = r.readU8();
        ServiceLifecycle[] values = values();
        if (ord < 0 || ord >= values.length) {
            throw new IllegalStateException("invalid ServiceLifecycle ordinal " + ord);
        }
        return values[ord];
    }
}
