package dev.nodera.committee;

import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.StateRoot;

/**
 * One committee member's contribution for a batch (Task 7): the root it computed, the delta it would
 * commit, and its signed vote. The {@link #delta()} is only committed if this member's {@link #root()}
 * is the one that reaches quorum.
 *
 * @param voter the member.
 * @param root  the state root the member computed.
 * @param delta the delta the member computed (transport for {@code root}).
 * @param vote  the member's signed ACCEPT vote on {@code root}.
 * @Thread-context immutable, any thread.
 */
public record MemberBallot(NodeId voter, StateRoot root, RegionDelta delta, SignedVote vote) {
    public MemberBallot {
        if (voter == null || root == null || delta == null || vote == null) {
            throw new IllegalArgumentException("no field of MemberBallot may be null");
        }
    }
}
