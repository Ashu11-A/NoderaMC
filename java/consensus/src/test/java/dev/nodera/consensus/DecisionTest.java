package dev.nodera.consensus;

import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class DecisionTest {

    private static final ProposalKey KEY = new ProposalKey(
            new RegionId(DimensionKey.overworld(), 0, 0),
            new RegionEpoch(1),
            new SnapshotVersion(1));
    private static final StateRoot PREV = root(0);

    private static StateRoot root(int seed) {
        byte[] b = new byte[32];
        Arrays.fill(b, (byte) seed);
        return new StateRoot(new Bytes(b));
    }

    private static SignedVote accept(NodeId voter, StateRoot root) {
        return new SignedVote(voter, root, VoteDecision.ACCEPT, Bytes.empty());
    }

    private static NodeId voter(long msb) {
        return new NodeId(new UUID(msb, 0L));
    }

    @Test
    void commitCarriesNonNullCertificate() {
        Decision d = MajorityQuorumPolicy.mvp().evaluate(KEY, PREV, List.of(
                accept(voter(1), root(7)), accept(voter(2), root(7))));

        String matched = switch (d) {
            case Decision.Commit c -> "commit:" + c.certificate().voteCount();
            case Decision.Reject r -> "reject:" + r.reason();
            case Decision.Unresolved u -> "unresolved:" + u.acceptVotes();
        };
        assertThat(matched).startsWith("commit:");
        assertThat(d).isInstanceOf(Decision.Commit.class);
        assertThat(((Decision.Commit) d).certificate()).isNotNull();
        assertThat(((Decision.Commit) d).certificate().resultingRoot()).isEqualTo(root(7));
    }

    @Test
    void rejectCarriesReason() {
        Decision direct = new Decision.Reject(
                KEY, Decision.Reject.RejectReason.STALE_EPOCH);
        assertThat(direct).isInstanceOf(Decision.Reject.class);
        assertThat(((Decision.Reject) direct).reason())
                .isEqualTo(Decision.Reject.RejectReason.STALE_EPOCH);
        assertThat(((Decision.Reject) direct).key()).isEqualTo(KEY);
    }

    @Test
    void rejectReasonFromPolicyIsNotEnoughAccept() {
        Decision d = MajorityQuorumPolicy.mvp().evaluate(KEY, PREV, List.of(
                accept(voter(1), root(1)),
                new SignedVote(voter(2), root(0), VoteDecision.REJECT_STATE_ROOT, Bytes.empty()),
                new SignedVote(voter(3), root(0), VoteDecision.REJECT_STATE_ROOT, Bytes.empty())));
        assertThat(d).isInstanceOf(Decision.Reject.class);
        assertThat(((Decision.Reject) d).reason())
                .isEqualTo(Decision.Reject.RejectReason.NOT_ENOUGH_ACCEPT);
    }

    @Test
    void unresolvedCarriesCounts() {
        Decision d = MajorityQuorumPolicy.mvp().evaluate(
                KEY, PREV, List.of(accept(voter(1), root(1))));
        assertThat(d).isInstanceOf(Decision.Unresolved.class);
        var u = (Decision.Unresolved) d;
        assertThat(u.key()).isEqualTo(KEY);
        assertThat(u.acceptVotes()).isEqualTo(1);
        assertThat(u.required()).isEqualTo(2);
    }

    @Test
    void directCommitRecordValidatesCertificate() {
        QuorumCertificate cert = new QuorumCertificate(
                KEY.region(), KEY.epoch(), KEY.version(), PREV, root(9),
                List.of(accept(voter(1), root(9)), accept(voter(2), root(9))));
        Decision.Commit commit = new Decision.Commit(cert);
        assertThat(commit.certificate().voteCount()).isEqualTo(2);
    }
}
