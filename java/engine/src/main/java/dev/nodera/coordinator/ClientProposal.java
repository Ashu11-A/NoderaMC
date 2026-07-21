package dev.nodera.coordinator;

import dev.nodera.consensus.ProposalKey;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;

/**
 * A primary client's Phase 2 proposal for one batch (Task 6): the delta it computed and the root it
 * claims, keyed by {@code (region, epoch, baseVersion)}. The server re-executes and commits the
 * delta only if its own root matches {@link #proposedRoot()}. The proposer signs the canonical
 * proposal (minus signature) in the wire form; the pure-Java coordinator carries the verified
 * {@code proposer} identity and trusts the mod's signature check upstream.
 *
 * @param region       the region the proposal commits into.
 * @param epoch        the epoch the primary ran under (stale epochs are dropped).
 * @param baseVersion  the snapshot version the batch started from.
 * @param proposedRoot the root the primary claims (compared against the server recompute).
 * @param delta        the block-mutation delta to commit on match.
 * @param proposer     the primary that produced this proposal.
 * @Thread-context immutable, any thread.
 */
public record ClientProposal(
        RegionId region,
        RegionEpoch epoch,
        SnapshotVersion baseVersion,
        StateRoot proposedRoot,
        RegionDelta delta,
        NodeId proposer
) {
    public ClientProposal {
        if (region == null || epoch == null || baseVersion == null
                || proposedRoot == null || delta == null || proposer == null) {
            throw new IllegalArgumentException("no field of ClientProposal may be null");
        }
    }

    /** @return the {@link ProposalKey} that names this proposal in the consensus machinery. */
    public ProposalKey key() {
        return new ProposalKey(region, epoch, baseVersion);
    }
}
