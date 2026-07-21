package dev.nodera.protocol.rendezvous;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;
import java.util.UUID;

/**
 * A namespace discovery query (Task 29, wire tag 36; rendezvous.md §4.3). Answered with a
 * {@link RendezvousPeers} page. Paged + rate-limited: no full-namespace enumeration beyond the page
 * limit (rendezvous.md §8.5).
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param networkId   the network to search.
 * @param genesisHash the world (swarm id) to search.
 * @param cursor      opaque page cursor ({@code 0} starts from the beginning).
 * @param limit       maximum records to return ({@code 0} lets the service choose its page size).
 */
public record RendezvousDiscover(UUID networkId, Bytes genesisHash, int cursor, int limit)
        implements NoderaMessage {

    /**
     * @throws IllegalArgumentException if a reference argument is null, {@code genesisHash} is
     *                                  empty, or a count is negative.
     */
    public RendezvousDiscover {
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(genesisHash, "genesisHash");
        if (genesisHash.isEmpty()) {
            throw new IllegalArgumentException("genesisHash must not be empty");
        }
        if (cursor < 0 || limit < 0) {
            throw new IllegalArgumentException("cursor and limit must be non-negative");
        }
    }
}
