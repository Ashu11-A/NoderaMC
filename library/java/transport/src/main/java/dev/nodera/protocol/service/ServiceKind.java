package dev.nodera.protocol.service;

import dev.nodera.core.crypto.CanonicalReader;

/**
 * What kind of infrastructure service a {@link ServiceRecord} describes.
 *
 * <p>Ordinals are the encoded form and are <b>frozen</b>: append, never reorder.
 *
 * <p>Thread-context: immutable enum, safe for any thread.
 */
public enum ServiceKind {

    /** A {@code nodera-rendezvous}: registration, discovery, punch coordination, relay circuits. */
    RENDEZVOUS,

    /** A {@code nodera-tracker}: world and peer discovery. */
    TRACKER;

    /**
     * Decode a frozen ordinal.
     *
     * @param r the canonical source, positioned at the {@code u8} ordinal.
     * @return the kind.
     * @throws IllegalStateException if the ordinal is not assigned.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static ServiceKind decodeOrdinal(CanonicalReader r) {
        int ord = r.readU8();
        ServiceKind[] values = values();
        if (ord < 0 || ord >= values.length) {
            throw new IllegalStateException("invalid ServiceKind ordinal " + ord);
        }
        return values[ord];
    }
}
