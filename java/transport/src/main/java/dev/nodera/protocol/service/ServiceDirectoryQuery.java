package dev.nodera.protocol.service;

import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;
import java.util.UUID;

/**
 * A peer asking a tracker which services of a kind it knows (wire tag
 * {@value dev.nodera.protocol.codec.MessageCodec#TAG_SERVICE_DIRECTORY_QUERY}).
 *
 * <p>This is the query that removes the hand-written rendezvous list. Before it, a peer's set of
 * rendezvous points was whatever string was in its configuration, so adding a rendezvous to the
 * network reached nobody and losing one was an outage for everybody configured to use it.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param kind      which kind of service to list.
 * @param networkId the network the caller serves.
 * @param limit     maximum rows to return; 0 lets the tracker choose its page size.
 */
public record ServiceDirectoryQuery(ServiceKind kind, UUID networkId, int limit)
        implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if a reference argument is null or {@code limit} is negative.
     */
    public ServiceDirectoryQuery {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(networkId, "networkId");
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be non-negative");
        }
    }
}
