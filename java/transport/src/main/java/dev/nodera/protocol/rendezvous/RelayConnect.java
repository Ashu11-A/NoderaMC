package dev.nodera.protocol.rendezvous;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;
import java.util.UUID;

/**
 * A peer asking the relay to bridge it to a target's reserved slot (Task 29, wire tag 40;
 * rendezvous.md §4.5). The relay looks up the target's live reservation, delivers a
 * {@link RelayIncoming} on the target's control stream, and — on accept — splices the two streams
 * and meters them. Frames on the bridged circuit are opaque, end-to-end-encrypted bytes.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param networkId   the network the circuit is scoped to.
 * @param genesisHash the world the circuit is scoped to.
 * @param source      the connecting peer.
 * @param target      the reserved destination peer.
 */
public record RelayConnect(UUID networkId, Bytes genesisHash, NodeId source, NodeId target)
        implements NoderaMessage {

    /**
     * @throws IllegalArgumentException if a reference argument is null or {@code genesisHash} is
     *                                  empty.
     */
    public RelayConnect {
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(genesisHash, "genesisHash");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (genesisHash.isEmpty()) {
            throw new IllegalArgumentException("genesisHash must not be empty");
        }
    }
}
