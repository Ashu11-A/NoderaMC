package dev.nodera.committee;

import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.crypto.HashService;
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

    private static final HashService HASHES = new HashService();

    private final NodeIdentity identity;
    private final RegionEngine engine;
    private final VotePersistence persistence;

    /** Create a member using the compatibility no-op persistence seam. */
    public CommitteeMember(NodeIdentity identity, RegionEngine engine) {
        this(identity, engine, VotePersistence.none());
    }

    /**
     * Create a member whose accepted candidates cross {@code persistence} before voting.
     *
     * @param identity    the member identity.
     * @param engine      the deterministic region engine.
     * @param persistence the candidate/certificate durability seam.
     */
    public CommitteeMember(
            NodeIdentity identity, RegionEngine engine, VotePersistence persistence) {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        if (persistence == null) {
            throw new IllegalArgumentException("persistence must not be null");
        }
        this.identity = identity;
        this.engine = engine;
        this.persistence = persistence;
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
        // Crash safety: an ACCEPT vote is emitted only after this member has retained the candidate.
        // If persistence fails, the exception aborts the round rather than creating a root vote that
        // exists nowhere outside transient executor memory.
        persistence.prepare(request, result);
        StateRoot transitionRoot = StateRoot.of(HASHES.hash(result.delta()));
        SignedVote vote = sign(request, result.resultingRoot(), transitionRoot, VoteDecision.ACCEPT);
        return new MemberBallot(nodeId(), result.resultingRoot(), result.delta(), vote);
    }

    /** Bind this member's prepared candidate to the certificate before canonical state advances. */
    public void markCommitted(QuorumCertificate certificate) {
        persistence.commit(certificate);
    }

    /**
     * Cast an explicit decision (e.g. a REJECT) on a given root — used when a member disagrees with
     * a proposal rather than proposing its own.
     */
    public SignedVote sign(StateRoot root, VoteDecision decision) {
        return sign(root, root, decision);
    }

    /** Sign both post-state truth and the complete transition, including one-way effects. */
    public SignedVote sign(StateRoot root, StateRoot transitionRoot, VoteDecision decision) {
        SignedVote unsigned = new SignedVote(
                nodeId(), root, transitionRoot, decision, Bytes.empty());
        Bytes signature = identity.sign(unsigned.signedPortion());
        return new SignedVote(nodeId(), root, transitionRoot, decision, signature);
    }

    private SignedVote sign(
            RegionExecutionRequest request, StateRoot root, StateRoot transitionRoot,
            VoteDecision decision) {
        SignedVote unsigned = new SignedVote(
                nodeId(), request.batch().region(), request.batch().epoch(),
                request.batch().baseVersion(), StateRoot.of(HASHES.hash(request.batch())),
                root, transitionRoot, decision, Bytes.empty());
        Bytes signature = identity.sign(unsigned.signedPortion());
        return new SignedVote(
                nodeId(), request.batch().region(), request.batch().epoch(),
                request.batch().baseVersion(), StateRoot.of(HASHES.hash(request.batch())),
                root, transitionRoot, decision, signature);
    }
}
