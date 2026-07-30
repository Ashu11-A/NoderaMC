package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * One founding peer's genesis endorsement (Task 16 / L-20): the founder's Ed25519 signature over
 * the {@code GenesisRecertification} signed portion (root ‖ complete founding set). The collector
 * verifies the signature against the DECLARED founder key — a grant from an outsider, a duplicate
 * founder, or with a bad signature never counts toward the quorum.
 *
 * @param genesisRoot the genesis root being endorsed; not null.
 * @param founder     the endorsing founder's node id; not null.
 * @param signature   the founder's signature over the signed portion; not null.
 */
public record GenesisApprovalGrant(
        StateRoot genesisRoot, NodeId founder, Bytes signature) implements NoderaMessage {

    public GenesisApprovalGrant {
        Objects.requireNonNull(genesisRoot, "genesisRoot");
        Objects.requireNonNull(founder, "founder");
        Objects.requireNonNull(signature, "signature");
    }
}
