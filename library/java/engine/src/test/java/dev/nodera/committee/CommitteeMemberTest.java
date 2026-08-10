package dev.nodera.committee;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.testkit.engine.EngineFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The write-ahead rule a committee member votes under.
 *
 * <p>This class was covered only by {@code CommitteeSessionTest} and {@code ByzantineWorkerTest},
 * which were deleted along with {@code CommitteeSession} when the retired central-coordinator
 * design was removed. {@code CommitteeMember} was not removed with them — it is still constructed
 * by {@code WorkerValidationService} on the live validation path — so the deletion left the class
 * with no test anywhere in the tree that even names it. What follows restores the one property the
 * class's own Javadoc calls crash safety, plus the shape of the ballot that property guards.
 */
final class CommitteeMemberTest {

    private static final SignatureService SIGNATURES = new SignatureService();

    private final RegionId region = EngineFixtures.region(0, 0);

    private RegionExecutionRequest request() {
        RegionSnapshot base = EngineFixtures.fullUniformSnapshot(region, 0);
        ActionBatch batch = EngineFixtures.batch(
                region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 0, 1,
                List.of(EngineFixtures.place(region, EngineFixtures.node(1L), 1, 0L, 5, 70, 5, 1)));
        return EngineFixtures.request(base, batch);
    }

    /**
     * The property this test exists for: a member that cannot retain its candidate must not emit an
     * ACCEPT vote for it. A vote for a root that exists only in transient executor memory is a vote
     * the member cannot honour after a crash, and quorum would have counted it.
     */
    @Test
    void aCandidateThatCannotBeRetainedIsNeverVotedFor() {
        VotePersistence failing = new VotePersistence() {
            @Override
            public void prepare(RegionExecutionRequest ignored, RegionExecutionResult result) {
                throw new IllegalStateException("disk unavailable");
            }

            @Override
            public void commit(QuorumCertificate certificate) {
                throw new AssertionError("commit must not run when prepare failed");
            }
        };
        CommitteeMember member = new CommitteeMember(
                NodeIdentity.generate(), EngineFixtures.engine(), failing);

        assertThatThrownBy(() -> member.computeAndVote(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("disk unavailable");
    }

    /**
     * The honest path, asserted at the level quorum consumes it: the ballot carries the root the
     * engine computed, an ACCEPT vote signed by this member's key, and the candidate reached
     * persistence before the vote was produced.
     */
    @Test
    void anHonestBallotCarriesTheEnginesRootAndAVerifiableAcceptVote() {
        RecordingPersistence persistence = new RecordingPersistence();
        NodeIdentity identity = NodeIdentity.generate();
        CommitteeMember member = new CommitteeMember(
                identity, EngineFixtures.engine(), persistence);
        RegionExecutionRequest request = request();

        MemberBallot ballot = member.computeAndVote(request);

        assertThat(persistence.prepared)
                .as("the candidate is retained before the vote exists")
                .isEqualTo(1);
        assertThat(ballot.voter()).isEqualTo(identity.nodeId());
        assertThat(ballot.root())
                .isEqualTo(EngineFixtures.engine().execute(request).resultingRoot());
        assertThat(ballot.vote().decision()).isEqualTo(VoteDecision.ACCEPT);
        assertThat(SIGNATURES.verify(identity.publicKeyBytes(),
                ballot.vote().signedPortion(), ballot.vote().signature()))
                .as("the ballot is signed by the member that cast it")
                .isTrue();
    }

    private static final class RecordingPersistence implements VotePersistence {
        private int prepared;

        @Override
        public void prepare(RegionExecutionRequest request, RegionExecutionResult result) {
            prepared++;
        }

        @Override
        public void commit(QuorumCertificate certificate) {
            throw new AssertionError("commit must not run in these tests");
        }
    }
}
