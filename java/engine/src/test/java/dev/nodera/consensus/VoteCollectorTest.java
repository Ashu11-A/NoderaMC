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
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class VoteCollectorTest {

    private static final ProposalKey KEY = new ProposalKey(
            new RegionId(DimensionKey.overworld(), 0, 0),
            new RegionEpoch(1),
            new SnapshotVersion(3));
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
    void secondMatchingAcceptCommits() {
        VoteCollector collector = new VoteCollector(
                KEY, MajorityQuorumPolicy.mvp(), PREV, 1000L);
        StateRoot agreed = root(11);

        collector.submit(accept(voter(1), agreed));
        assertThat(collector.decide()).isInstanceOf(Decision.Unresolved.class);

        collector.submit(accept(voter(2), agreed));
        Decision d = collector.decide();
        assertThat(d).isInstanceOf(Decision.Commit.class);
        var cert = ((Decision.Commit) d).certificate();
        assertThat(cert.resultingRoot()).isEqualTo(agreed);
        assertThat(cert.prevRoot()).isEqualTo(PREV);
        assertThat(cert.voteCount()).isEqualTo(2);
    }

    @Test
    void duplicateVoteFromSameVoterCountedOnce() {
        VoteCollector collector = new VoteCollector(
                KEY, MajorityQuorumPolicy.mvp(), PREV, 1000L);
        NodeId a = voter(1);
        SignedVote vote = accept(a, root(11));

        collector.submit(vote);
        collector.submit(vote);
        assertThat(collector.voterCount()).isEqualTo(1);

        assertThat(collector.decide()).isInstanceOf(Decision.Unresolved.class);

        collector.submit(accept(voter(2), root(11)));
        assertThat(collector.voterCount()).isEqualTo(2);
        assertThat(collector.decide()).isInstanceOf(Decision.Commit.class);
    }

    @Test
    void isTimedOutUsesWallClock() throws InterruptedException {
        // Capture `born` BEFORE construction: createdAtMillis >= born, so `born` is deterministically
        // inside the window regardless of scheduling delays (this raced under CPU load before).
        long born = System.currentTimeMillis();
        VoteCollector collector = new VoteCollector(KEY, MajorityQuorumPolicy.mvp(), PREV, 1L);
        Thread.sleep(5L);
        assertThat(collector.isTimedOut(born + 10_000L)).isTrue();
        assertThat(collector.isTimedOut(born)).isFalse();
    }
}
