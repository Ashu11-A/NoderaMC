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

    private static SignedVote reject(NodeId voter) {
        return new SignedVote(voter, root(99), VoteDecision.REJECT_STATE_ROOT, Bytes.empty());
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
    void mvp_conflictingAcceptRootsDoNotCommit() {
        MajorityQuorumPolicy policy = MajorityQuorumPolicy.mvp();
        StateRoot r1 = root(1);
        StateRoot r2 = root(2);
        Decision d = policy.evaluate(KEY, PREV, List.of(
                accept(voter(1), r1), accept(voter(2), r2)));
        assertThat(d).isNotInstanceOf(Decision.Commit.class);
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
}
