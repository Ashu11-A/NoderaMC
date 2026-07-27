package dev.nodera.protocol.service;

import dev.nodera.protocol.NoderaMessage;

import java.util.List;
import java.util.Objects;

/**
 * A tracker's answer to a {@link ServiceAnnounce} (wire tag
 * {@value dev.nodera.protocol.codec.MessageCodec#TAG_SERVICE_ANNOUNCE_ACK}).
 *
 * <p>{@link #directory()} is what makes a seamless handover possible: a draining rendezvous learns
 * its own replacements <b>in the same round trip</b> it uses to say that it is draining, so it can
 * name them to the peers it is about to disconnect instead of leaving them to rediscover blind. The
 * alternative — announce, then query, then drain — adds a round trip exactly when the service is
 * shutting down and least able to make one.
 *
 * <p>Like a peer's announce ack, {@link #nextAnnounceAfterSeconds()} lets the service pace its own
 * load rather than hoping announcers behave.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param accepted                  whether the record was admitted.
 * @param nextAnnounceAfterSeconds  how long to wait before announcing again.
 * @param reason                    empty when accepted; otherwise a short stable rejection code.
 * @param directory                 sibling services of the same kind, best first.
 */
public record ServiceAnnounceAck(
        boolean accepted,
        int nextAnnounceAfterSeconds,
        String reason,
        List<ServiceDirectoryEntry> directory) implements NoderaMessage {

    /**
     * Compact constructor: validates and defensive-copies the directory.
     *
     * @throws IllegalArgumentException if a reference argument is null or the interval is negative.
     */
    public ServiceAnnounceAck {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(directory, "directory");
        if (nextAnnounceAfterSeconds < 0) {
            throw new IllegalArgumentException("nextAnnounceAfterSeconds must be non-negative");
        }
        directory = List.copyOf(directory);
    }
}
