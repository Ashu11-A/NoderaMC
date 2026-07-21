package dev.nodera.protocol.discovery;

import dev.nodera.core.crypto.CanonicalReader;

/**
 * The lifecycle event carried by a {@link TrackerAnnounce} (Task 28; BitTorrent-style
 * {@code started}/heartbeat/{@code stopped} — {@code docs/torrent/trackers.md} §3).
 *
 * <p>Ordinals are the encoded form and are <b>frozen</b>: append new constants, never reorder.
 *
 * <p>Thread-context: immutable enum, safe for any thread.
 */
public enum AnnounceEvent {

    /** First announce of a session: the tracker registers (or replaces) the peer's record. */
    STARTED,

    /** Periodic refresh: extends the record's lifetime and updates holdings/reliability. */
    HEARTBEAT,

    /**
     * Graceful departure: the tracker drops the record immediately instead of waiting for the TTL
     * sweep, so a clean shutdown does not leave a dead peer in the world list for minutes.
     */
    STOPPED;

    /**
     * Decode a frozen ordinal.
     *
     * @param r the canonical source, positioned at the {@code u8} ordinal.
     * @return the event.
     * @throws IllegalStateException if the ordinal is not assigned.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static AnnounceEvent decodeOrdinal(CanonicalReader r) {
        int ord = r.readU8();
        AnnounceEvent[] values = values();
        if (ord < 0 || ord >= values.length) {
            throw new IllegalStateException("invalid AnnounceEvent ordinal " + ord);
        }
        return values[ord];
    }
}
