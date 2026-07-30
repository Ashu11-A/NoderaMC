package dev.nodera.consensus;

import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class MajorityQuorumPolicyTest {

    private static final RegionId REGION =
            new RegionId(DimensionKey.overworld(), 0, 0);
    private static final RegionEpoch EPOCH = new RegionEpoch(1);
    private static final SnapshotVersion VERSION = new SnapshotVersion(7);
    private static final ProposalKey KEY = new ProposalKey(REGION, EPOCH, VERSION);
    private static final StateRoot PREV = root(0);

    private static StateRoot root(int seed) {
        byte[] b = new byte[32];
        Arrays.fill(b, (byte) seed);
        return new StateRoot(new Bytes(b));
    }

    private static SignedVote accept(NodeId voter, StateRoot root) {
        return new SignedVote(voter, root, VoteDecision.ACCEPT, Bytes.empty());
    }

    private static SignedVote accept(NodeId voter, StateRoot root, StateRoot transitionRoot) {
        return new SignedVote(
                voter, root, transitionRoot, VoteDecision.ACCEPT, Bytes.empty());
    }

    private static SignedVote reject(NodeId voter) {
        return new SignedVote(voter, root(99), VoteDecision.REJECT_STATE_ROOT, Bytes.empty());
    }

    private static SignedVote anchored(NodeId voter, ProposalKey key, StateRoot batchRoot) {
        return new SignedVote(
                voter, key.region(), key.epoch(), key.version(), batchRoot,
                root(8), root(9), VoteDecision.ACCEPT, Bytes.empty());
    }

    private static NodeId voter(long msb) {
        return new NodeId(new UUID(msb, 0L));
    }

    @Test
    void mvp_twoMatchingAcceptsOfThreeCommit() {
        MajorityQuorumPolicy policy = MajorityQuorumPolicy.mvp();
        assertThat(policy.required(3)).isEqualTo(2);

        NodeId a = voter(1), b = voter(2), c = voter(3);
        StateRoot agreed = root(42);

        Decision d = policy.evaluate(KEY, PREV, List.of(
                accept(a, agreed), accept(b, agreed), accept(c, agreed)));

        assertThat(d).isInstanceOf(Decision.Commit.class);
        var cert = ((Decision.Commit) d).certificate();
        assertThat(cert.resultingRoot()).isEqualTo(agreed);
        assertThat(cert.prevRoot()).isEqualTo(PREV);
        assertThat(cert.voteCount()).isEqualTo(3);
        assertThat(cert.votes()).allSatisfy(v ->
                assertThat(v.resultingRoot()).isEqualTo(agreed));
    }

    @Test
    void mvp_certificateVotesAreSortedByVoterUuid() {
        MajorityQuorumPolicy policy = MajorityQuorumPolicy.mvp();
        NodeId a = voter(40), b = voter(10), c = voter(20);
        StateRoot agreed = root(7);

        Decision d = policy.evaluate(KEY, PREV, List.of(
                accept(a, agreed), accept(b, agreed), accept(c, agreed)));

        assertThat(d).isInstanceOf(Decision.Commit.class);
        List<SignedVote> votes = ((Decision.Commit) d).certificate().votes();
        assertThat(votes).extracting(SignedVote::voter)
                .isSortedAccordingTo(Comparator.comparing(NodeId::value));
        assertThat(votes).extracting(v -> v.voter().value())
                .containsExactly(b.value(), c.value(), a.value());
    }

    @Test
    void mvp_oneAcceptIsUnresolved() {
        MajorityQuorumPolicy policy = MajorityQuorumPolicy.mvp();
        Decision d = policy.evaluate(KEY, PREV, List.of(accept(voter(1), root(1))));
        assertThat(d).isInstanceOf(Decision.Unresolved.class);
        var u = (Decision.Unresolved) d;
        assertThat(u.acceptVotes()).isEqualTo(1);
        assertThat(u.required()).isEqualTo(2);
    }

    @Test
    void mvp_loneAcceptCanNeverCommit() {
        MajorityQuorumPolicy policy = MajorityQuorumPolicy.mvp();
        Decision d = policy.evaluate(KEY, PREV, List.of(accept(voter(1), root(1))));
        assertThat(d).isNotInstanceOf(Decision.Commit.class);
    }

    @Test
    void mvp_twoRejectsAndOneAcceptReject() {
        MajorityQuorumPolicy policy = MajorityQuorumPolicy.mvp();
        Decision d = policy.evaluate(KEY, PREV, List.of(
                accept(voter(1), root(1)),
                reject(voter(2)),
                reject(voter(3))));
        assertThat(d).isInstanceOf(Decision.Reject.class);
        var r = (Decision.Reject) d;
        assertThat(r.reason()).isEqualTo(Decision.Reject.RejectReason.NOT_ENOUGH_ACCEPT);
    }

    @Test
    void mvp_conflictingAcceptRootsUnresolvedWhileQuorumReachable() {
        // 2-of-3: A->r1, B->r2. acceptCount=2>=required, but split across roots so no group is
        // at threshold. Outstanding voter C could still give r1 (or r2) a 2-of-3 quorum, so this
        // must stay UNRESOLVED — not Reject. A premature Reject would abandon a committable
        // proposal (liveness regression).
        MajorityQuorumPolicy policy = MajorityQuorumPolicy.mvp();
        StateRoot r1 = root(1);
        StateRoot r2 = root(2);
        Decision d = policy.evaluate(KEY, PREV, List.of(
                accept(voter(1), r1), accept(voter(2), r2)));
        assertThat(d).isInstanceOf(Decision.Unresolved.class);
    }

    @Test
    void sameStateRootWithDifferentEffectsDoesNotFormQuorum() {
        MajorityQuorumPolicy policy = MajorityQuorumPolicy.mvp();
        StateRoot state = root(1);
        StateRoot honestTransition = root(2);
        StateRoot forgedTransition = root(3);
        NodeId a = voter(1), b = voter(2), c = voter(3);

        Decision split = policy.evaluate(KEY, PREV, List.of(
                accept(a, state, honestTransition),
                accept(b, state, forgedTransition)));
        assertThat(split).isInstanceOf(Decision.Unresolved.class);

        Decision committed = policy.evaluate(KEY, PREV, List.of(
                accept(a, state, honestTransition),
                accept(b, state, forgedTransition),
                accept(c, state, honestTransition)));
        assertThat(committed).isInstanceOf(Decision.Commit.class);
        assertThat(((Decision.Commit) committed).certificate().votes())
                .extracting(SignedVote::transitionRoot)
                .containsOnly(honestTransition);
    }

    @Test
    void anchoredVotesMustAgreeOnProposalKeyAndBatch() {
        MajorityQuorumPolicy policy = MajorityQuorumPolicy.mvp();
        SignedVote first = anchored(voter(1), KEY, root(10));
        SignedVote otherBatch = anchored(voter(2), KEY, root(11));
        SignedVote wrongEpoch = anchored(
                voter(3), new ProposalKey(REGION, new RegionEpoch(2), VERSION), root(10));
        assertThat(policy.evaluate(KEY, PREV, List.of(first, otherBatch)))
                .isInstanceOf(Decision.Unresolved.class);
        assertThat(policy.evaluate(KEY, PREV, List.of(first, wrongEpoch)))
                .isInstanceOf(Decision.Unresolved.class);
        assertThat(policy.evaluate(KEY, PREV, List.of(
                first, otherBatch, anchored(voter(3), KEY, root(10)))))
                .isInstanceOf(Decision.Commit.class);
    }

    @Test
    void mvp_splitAcceptThenLateVoteCommits() {
        // Same split as above, then C votes ACCEPT(r1): r1 now has 2-of-3 -> Commit.
        // Guards the liveness fix end-to-end (the late vote must not arrive on a closed collector).
        MajorityQuorumPolicy policy = MajorityQuorumPolicy.mvp();
        StateRoot r1 = root(1);
        StateRoot r2 = root(2);
        NodeId a = voter(1), b = voter(2), c = voter(3);

        Decision split = policy.evaluate(KEY, PREV, List.of(accept(a, r1), accept(b, r2)));
        assertThat(split).isInstanceOf(Decision.Unresolved.class);

        Decision resolved = policy.evaluate(KEY, PREV,
                List.of(accept(a, r1), accept(b, r2), accept(c, r1)));
        assertThat(resolved).isInstanceOf(Decision.Commit.class);
        assertThat(((Decision.Commit) resolved).certificate().resultingRoot()).isEqualTo(r1);
    }

    @Test
    void mvp_splitAcceptAllVotedThenReject() {
        // 2-of-3: A->r1, B->r2, C->REJECT. acceptCount=2>=required, outstanding=0, maxGroup=1<2:
        // no root can ever reach threshold now -> terminal Reject.
        MajorityQuorumPolicy policy = MajorityQuorumPolicy.mvp();
        StateRoot r1 = root(1);
        StateRoot r2 = root(2);
        Decision d = policy.evaluate(KEY, PREV, List.of(
                accept(voter(1), r1), accept(voter(2), r2), reject(voter(3))));
        assertThat(d).isInstanceOf(Decision.Reject.class);
        assertThat(((Decision.Reject) d).reason())
                .isEqualTo(Decision.Reject.RejectReason.NOT_ENOUGH_ACCEPT);
    }

    @Test
    void peer_splitAcrossRootsUnresolvedThenCommit() {
        // 3-of-4: A,B->r1, C->r2 (acceptCount=3>=required, maxGroup=2, outstanding=1 -> Unresolved);
        // then D->r1 gives r1 3-of-4 -> Commit.
        MajorityQuorumPolicy policy = MajorityQuorumPolicy.peer();
        StateRoot r1 = root(1);
        StateRoot r2 = root(2);
        NodeId a = voter(1), b = voter(2), c = voter(3), d = voter(4);

        Decision split = policy.evaluate(KEY, PREV,
                List.of(accept(a, r1), accept(b, r1), accept(c, r2)));
        assertThat(split).isInstanceOf(Decision.Unresolved.class);

        Decision resolved = policy.evaluate(KEY, PREV,
                List.of(accept(a, r1), accept(b, r1), accept(c, r2), accept(d, r1)));
        assertThat(resolved).isInstanceOf(Decision.Commit.class);
    }

    @Test
    void peer_twoAcceptsUnresolvedThreeCommit() {
        MajorityQuorumPolicy policy = MajorityQuorumPolicy.peer();
        assertThat(policy.required(4)).isEqualTo(3);
        StateRoot agreed = root(5);

        Decision before = policy.evaluate(KEY, PREV, List.of(
                accept(voter(1), agreed), accept(voter(2), agreed)));
        assertThat(before).isInstanceOf(Decision.Unresolved.class);

        Decision after = policy.evaluate(KEY, PREV, List.of(
                accept(voter(1), agreed), accept(voter(2), agreed), accept(voter(3), agreed)));
        assertThat(after).isInstanceOf(Decision.Commit.class);
        assertThat(((Decision.Commit) after).certificate().voteCount()).isEqualTo(3);
    }

    @Test
    void sizedTo_matchesTheActualCommitteeMajority() {
        assertThat(MajorityQuorumPolicy.requiredForMajority(1)).isEqualTo(1);
        assertThat(MajorityQuorumPolicy.requiredForMajority(2)).isEqualTo(2);
        assertThat(MajorityQuorumPolicy.requiredForMajority(3)).isEqualTo(2);
        assertThat(MajorityQuorumPolicy.requiredForMajority(4)).isEqualTo(3);
        assertThat(MajorityQuorumPolicy.requiredForMajority(5)).isEqualTo(3);
        assertThatThrownBy(() -> MajorityQuorumPolicy.requiredForMajority(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sizedTo_soloCommitteeCommitsOnItsOwnAccept() {
        MajorityQuorumPolicy policy = MajorityQuorumPolicy.sizedTo(1);
        StateRoot agreed = root(5);

        Decision decision = policy.evaluate(KEY, PREV, List.of(accept(voter(1), agreed)));

        assertThat(decision).isInstanceOf(Decision.Commit.class);
        assertThat(((Decision.Commit) decision).certificate().voteCount()).isEqualTo(1);
    }
}
