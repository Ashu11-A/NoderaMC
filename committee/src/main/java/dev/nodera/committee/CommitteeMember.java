package dev.nodera.committee;

import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.RegionEngine;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;

/**
 * One committee member (primary or validator) in Phase 3 (Task 7). Each member re-executes the
 * batch with its own {@link RegionEngine} and casts a <b>signed ACCEPT vote carrying its own
 * computed root</b>. Honest members that share the engine and inputs produce byte-identical roots;
 * a byzantine member is modelled simply by handing it a corrupting engine — its root then lands in a
 * different quorum group and can never reach the threshold alone.
 *
 * @Thread-context confined per {@link #computeAndVote} call; the engine is a pure function.
 */
public final class CommitteeMember {

    private final NodeIdentity identity;
    private final RegionEngine engine;

    public CommitteeMember(NodeIdentity identity, RegionEngine engine) {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        this.identity = identity;
        this.engine = engine;
    }

    /** @return this member's node id. */
    public NodeId nodeId() {
        return identity.nodeId();
    }

    /**
     * Re-execute {@code request} and produce this member's signed ballot: an {@code ACCEPT} vote on
     * the root it computed, plus the delta it would commit.
     *
     * @param request the shared execution inputs (context + snapshot + batch).
     * @return this member's ballot.
     */
    public MemberBallot computeAndVote(RegionExecutionRequest request) {
        RegionExecutionResult result = engine.execute(request);
        SignedVote vote = sign(result.resultingRoot(), VoteDecision.ACCEPT);
        return new MemberBallot(nodeId(), result.resultingRoot(), result.delta(), vote);
    }

    /**
     * Cast an explicit decision (e.g. a REJECT) on a given root — used when a member disagrees with
     * a proposal rather than proposing its own.
     */
    public SignedVote sign(StateRoot root, VoteDecision decision) {
        SignedVote unsigned = new SignedVote(nodeId(), root, decision, Bytes.empty());
        Bytes signature = identity.sign(unsigned.signedPortion());
        return new SignedVote(nodeId(), root, decision, signature);
    }
}
