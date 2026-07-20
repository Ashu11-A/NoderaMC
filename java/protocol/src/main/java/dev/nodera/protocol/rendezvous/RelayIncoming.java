package dev.nodera.protocol.rendezvous;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;
import java.util.UUID;

/**
 * The relay telling a reserver that a circuit is inbound (Task 29, wire tag 41; rendezvous.md §4.5).
 * The reserver validates the echoed {@link #proof()} against its own reservation, then accepts and
 * runs an end-to-end handshake before any application byte crosses.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param networkId   the network the circuit is scoped to.
 * @param genesisHash the world the circuit is scoped to.
 * @param source      the connecting peer.
 * @param target      the reserved destination peer (this reserver).
 * @param proof       the reservation proof, echoed so the reserver can validate it holds the slot.
 */
public record RelayIncoming(
        UUID networkId, Bytes genesisHash, NodeId source, NodeId target, Bytes proof)
        implements NoderaMessage {

    /**
     * @throws IllegalArgumentException if a reference argument is null or {@code genesisHash} is
     *                                  empty.
     */
    public RelayIncoming {
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(genesisHash, "genesisHash");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(proof, "proof");
        if (genesisHash.isEmpty()) {
            throw new IllegalArgumentException("genesisHash must not be empty");
        }
    }
}
