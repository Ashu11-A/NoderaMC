package dev.nodera.committee;

import dev.nodera.consensus.Decision;
import dev.nodera.consensus.EquivocationDetector;
import dev.nodera.consensus.MajorityQuorumPolicy;
import dev.nodera.consensus.ProposalKey;
import dev.nodera.consensus.QuorumPolicy;
import dev.nodera.consensus.VoteCollector;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.StateRoot;
import dev.nodera.coordinator.ReliabilityLedger;
import dev.nodera.coordinator.WorldMutationApplier;
import dev.nodera.simulation.RegionExecutionRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runs one committee vote round (Task 7 — the MVP gate). Every {@link CommitteeMember} re-executes
 * the batch and casts a signed ACCEPT vote on its own root; the {@link QuorumPolicy} groups votes by
 * root and commits when one root reaches the threshold. On commit, the delta of an agreeing member
 * is applied through the {@link WorldMutationApplier}, agreeing members are rewarded and
 * disagreeing (outvoted / lying) members are penalised in the {@link ReliabilityLedger};
 * equivocators are slashed.
 *
 * <p>Safety, rehearsed here early (Plan §6 Phase 3):
 * <ul>
 *   <li>a lone lying validator lands in its own root group and can never reach the 2-of-3
 *       threshold alone;</li>
 *   <li>a lying primary is outvoted by the two honest validators (their shared root commits) and
 *       penalised for disagreeing with the committed root.</li>
 * </ul>
 *
 * @Thread-context confined to the session thread; not thread-safe.
 */
public final class CommitteeSession {

    private final QuorumPolicy policy;
    private final WorldMutationApplier applier;
    private final ReliabilityLedger reliability;
    private final EquivocationDetector equivocation;
    private final long voteTimeoutMillis;

    public CommitteeSession(QuorumPolicy policy, WorldMutationApplier applier,
                            ReliabilityLedger reliability, EquivocationDetector equivocation,
                            long voteTimeoutMillis) {
        this.policy = policy;
        this.applier = applier;
        this.reliability = reliability;
        this.equivocation = equivocation;
        this.voteTimeoutMillis = voteTimeoutMillis;
    }

    /** MVP session: 2-of-3 majority, a fresh equivocation detector, a 2 s vote window. */
    public static CommitteeSession mvp(WorldMutationApplier applier, ReliabilityLedger reliability) {
        return new CommitteeSession(MajorityQuorumPolicy.mvp(), applier, reliability,
                new EquivocationDetector(), 2000L);
    }

    /**
     * Run one vote round.
     *
     * @param key      the proposal key {@code (region, epoch, baseVersion)}.
     * @param prevRoot the last committed root of the region (certificate {@code prevRoot}).
     * @param members  the committee (primary + validators).
     * @param request  the shared execution inputs every member re-executes.
     * @return the {@link CommitResult}.
     */
    public CommitResult runBatch(ProposalKey key, StateRoot prevRoot, List<CommitteeMember> members,
                                 RegionExecutionRequest request) {
        VoteCollector collector = new VoteCollector(key, policy, prevRoot, voteTimeoutMillis);
        List<MemberBallot> ballots = new ArrayList<>(members.size());
        Set<NodeId> equivocators = new TreeSet<>(Comparator.comparing(NodeId::value));

        for (CommitteeMember m : members) {
            MemberBallot ballot = m.computeAndVote(request);
            ballots.add(ballot);
            equivocation.observe(key, ballot.vote());
            if (equivocation.hasEquivoked(ballot.voter())) {
                equivocators.add(ballot.voter());
            }
            collector.submit(ballot.vote());
        }

        Decision decision = collector.decide();
        return switch (decision) {
            case Decision.Commit c -> onCommit(c.certificate(), ballots, equivocators);
            case Decision.Reject r -> CommitResult.rejected(r.reason().name(), equivocators);
            case Decision.Unresolved u -> CommitResult.pending();
        };
    }

    private CommitResult onCommit(QuorumCertificate cert, List<MemberBallot> ballots,
                                  Set<NodeId> equivocators) {
        StateRoot committed = cert.resultingRoot();
        Set<NodeId> penalized = new TreeSet<>(Comparator.comparing(NodeId::value));
        RegionDelta commitDelta = null;

        for (MemberBallot b : ballots) {
            boolean agree = b.root().equals(committed);
            reliability.record(b.voter(), agree);
            if (agree && commitDelta == null) {
                commitDelta = b.delta();
            }
            if (!agree) {
                penalized.add(b.voter());
            }
        }
        for (NodeId eq : equivocators) {
            reliability.slash(eq);
            penalized.add(eq);
        }
        // commitDelta is non-null: a Commit certificate implies at least one agreeing member.
        WorldMutationApplier.ApplyResult applied = applier.apply(commitDelta);
        return CommitResult.committed(cert, applied, penalized, equivocators);
    }
}
