package dev.nodera.protocol.rendezvous;

import dev.nodera.core.crypto.CanonicalReader;

/**
 * What a {@link SignedPeerRecord} asks the rendezvous point to do (Task 29; rendezvous.md §4.1).
 *
 * <p>The event is part of the signed record so an {@link #UNREGISTER} cannot be forged by flipping a
 * captured {@link #REGISTER}. Ordinals are the encoded form and are <b>frozen</b>: append, never
 * reorder.
 *
 * <p>Thread-context: immutable enum, safe for any thread.
 */
public enum RegistrationEvent {

    /** First registration of a session: create or replace the record. */
    REGISTER,

    /** Periodic refresh: extend the record's lease. */
    REFRESH,

    /** Graceful departure: drop the record now instead of waiting for the TTL sweep. */
    UNREGISTER;

    /**
     * Decode a frozen ordinal.
     *
     * @param r the canonical source, positioned at the {@code u8} ordinal.
     * @return the event.
     * @throws IllegalStateException if the ordinal is not assigned.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static RegistrationEvent decodeOrdinal(CanonicalReader r) {
        int ord = r.readU8();
        RegistrationEvent[] values = values();
        if (ord < 0 || ord >= values.length) {
            throw new IllegalStateException("invalid RegistrationEvent ordinal " + ord);
        }
        return values[ord];
    }
}
