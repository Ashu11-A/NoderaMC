package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.NoderaMessage;

import java.util.List;
import java.util.Objects;

/**
 * Genesis re-certification request (Task 16 / L-20): the sharing host asks one founding peer to
 * endorse the world's genesis root. The request carries the root AND the complete declared
 * founding set — the founder signs over both (the {@code GenesisRecertification} signed portion),
 * so no host can shrink the set to lower the quorum bar after the fact. The founder answers with
 * {@link GenesisApprovalGrant} only when the root matches the genesis it actually joined on.
 *
 * @param genesisRoot the certified genesis root to endorse; not null.
 * @param founders    the complete declared founding set, fixed order; not empty.
 */
public record GenesisApprovalRequest(
        StateRoot genesisRoot, List<FounderEntry> founders) implements NoderaMessage {

    /** One declared founding peer: its node id + its Ed25519 public key. */
    public record FounderEntry(NodeId nodeId, Bytes publicKey) {
        public FounderEntry {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(publicKey, "publicKey");
        }
    }

    public GenesisApprovalRequest {
        Objects.requireNonNull(genesisRoot, "genesisRoot");
        Objects.requireNonNull(founders, "founders");
        if (founders.isEmpty()) {
            throw new IllegalArgumentException("founders must not be empty");
        }
        founders = List.copyOf(founders);
    }
}
