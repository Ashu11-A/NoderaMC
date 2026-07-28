package dev.nodera.protocol.service;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * A service's signed self-announcement to a tracker (wire tag
 * {@value dev.nodera.protocol.codec.MessageCodec#TAG_SERVICE_ANNOUNCE}).
 *
 * <p>The shape a peer's {@code TrackerAnnounce} already has, one level up: a rendezvous tells
 * trackers where it is, what version it runs, how loaded it is, and — the point of the whole lane —
 * when it is about to go away. A tracker verifies the signature and stores the record; it cannot mint
 * a record it did not receive.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param record    the canonical service record.
 * @param signature Ed25519 over {@link ServiceRecord#signedBytes()}.
 */
public record ServiceAnnounce(ServiceRecord record, Bytes signature) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if either argument is null.
     */
    public ServiceAnnounce {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(signature, "signature");
    }

    /**
     * This announcement as a verifiable directory row, with no score attached yet.
     *
     * @return the entry a tracker would list before any peer has measured the service.
     * @Thread-context any thread.
     */
    public ServiceDirectoryEntry asEntry() {
        return new ServiceDirectoryEntry(record, signature, ServiceScore.UNKNOWN);
    }
}
