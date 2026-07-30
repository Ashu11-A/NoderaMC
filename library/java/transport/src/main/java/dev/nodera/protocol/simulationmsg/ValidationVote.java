package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * Validator→(primary + committee) vote on a {@link RegionProposal} (Task 4).
 *
 * <p>Carries the region/epoch/version the vote concerns plus the core {@link SignedVote}
 * (which holds the voter, resulting root, decision, and Ed25519 signature). The codec
 * delegates to {@link SignedVote#encode(dev.nodera.core.crypto.CanonicalWriter)} for the vote
 * body so the on-wire bytes of a vote are identical whether it is transported, hashed, or
 * verified.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param region  region the vote concerns.
 * @param epoch   region epoch the vote concerns.
 * @param version snapshot version the vote is for.
 * @param vote    the wrapped signed vote.
 */
public record ValidationVote(
        RegionId region,
        RegionEpoch epoch,
        SnapshotVersion version,
        SignedVote vote
) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any argument is null.
     */
    public ValidationVote {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(vote, "vote");
    }
}
