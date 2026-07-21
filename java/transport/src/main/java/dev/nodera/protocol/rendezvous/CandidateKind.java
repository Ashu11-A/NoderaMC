package dev.nodera.protocol.rendezvous;

import dev.nodera.core.crypto.CanonicalReader;

/**
 * The category of a reachability {@link PeerCandidate} (Task 29; rendezvous.md §2.5).
 *
 * <p>Ordinals are the encoded form and are <b>frozen</b>: append new constants, never reorder.
 *
 * <p>Thread-context: immutable enum, safe for any thread.
 */
public enum CandidateKind {

    /** A local interface address, such as a LAN IP. */
    HOST,

    /** A directly reachable public address. */
    PUBLIC,

    /** A public address observed through the relay (STUN-like, server-reflexive). */
    SERVER_REFLEXIVE,

    /** An address explicitly mapped through UPnP / NAT-PMP / PCP. */
    MAPPED,

    /** An address reachable only through a relay circuit. */
    RELAY;

    /**
     * Decode a frozen ordinal.
     *
     * @param r the canonical source, positioned at the {@code u8} ordinal.
     * @return the kind.
     * @throws IllegalStateException if the ordinal is not assigned.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static CandidateKind decodeOrdinal(CanonicalReader r) {
        int ord = r.readU8();
        CandidateKind[] values = values();
        if (ord < 0 || ord >= values.length) {
            throw new IllegalStateException("invalid CandidateKind ordinal " + ord);
        }
        return values[ord];
    }

    /** @return whether this candidate reaches the peer without a relay circuit. */
    public boolean isDirect() {
        return this != RELAY;
    }
}
