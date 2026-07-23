package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.region.RegionId;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/** One source- or target-committee member's signed acceptance of a transfer descriptor. */
public record EntityTransferAccept(
        long transferId, RegionId side, SignedVote vote) implements NoderaMessage {

    public EntityTransferAccept {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(vote, "vote");
        if (!side.equals(vote.region())) {
            throw new IllegalArgumentException("accept side must match vote region");
        }
    }
}
