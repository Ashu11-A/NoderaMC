package dev.nodera.protocol.rendezvous;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;
import java.util.UUID;

/**
 * A peer reserving an inbound relay slot (Task 29, wire tag 38; rendezvous.md §4.2). A peer that
 * expects inbound relayed connects reserves before advertising a relay candidate — no reservation,
 * no {@code CONNECT} (circuit-relay-v2 style; rendezvous.md §8.4). Answered with a
 * {@link RelayReservation}.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param networkId   the network the reservation is scoped to.
 * @param genesisHash the world the reservation is scoped to.
 * @param peer        the reserving peer (the future destination of inbound circuits).
 */
public record RelayReserve(UUID networkId, Bytes genesisHash, NodeId peer) implements NoderaMessage {

    /**
     * @throws IllegalArgumentException if a reference argument is null or {@code genesisHash} is
     *                                  empty.
     */
    public RelayReserve {
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(genesisHash, "genesisHash");
        Objects.requireNonNull(peer, "peer");
        if (genesisHash.isEmpty()) {
            throw new IllegalArgumentException("genesisHash must not be empty");
        }
    }
}
