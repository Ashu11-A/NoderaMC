package dev.nodera.committee;

import dev.nodera.consensus.ProposalKey;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.coordinator.InMemoryWorldView;
import dev.nodera.coordinator.ReliabilityLedger;
import dev.nodera.coordinator.WorldMutationApplier;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommitteeSessionTest {

    private final RegionId region = CommFixtures.region(0, 0);

    private ActionBatch batch() {
        return CommFixtures.batch(region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 0, 1, List.of(
                CommFixtures.place(region, 1, 0, 5, 70, 5, 1),
                CommFixtures.place(region, 2, 0, 40, 100, 40, 4),
                CommFixtures.place(region, 3, 0, 80, 50, 80, 3)));
    }

    @Test
    void honestCommitteeCommitsAndWorldMatchesEngineRoot() {
        RegionSnapshot base = CommFixtures.fullUniformSnapshot(region, 0);
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(base);
        ReliabilityLedger reliability = new ReliabilityLedger();
        CommitteeSession session = CommitteeSession.mvp(new WorldMutationApplier(world), reliability);

        List<CommitteeMember> members = List.of(
                new CommitteeMember(CommFixtures.identity(), CommFixtures.engine()),
                new CommitteeMember(CommFixtures.identity(), CommFixtures.engine()),
                new CommitteeMember(CommFixtures.identity(), CommFixtures.engine()));

        ActionBatch batch = batch();
        RegionExecutionRequest request = CommFixtures.request(base, batch);
        StateRoot engineRoot = CommFixtures.engine().execute(request).resultingRoot();
        ProposalKey key = new ProposalKey(region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL);

        CommitResult result = session.runBatch(key, CommFixtures.rootOf(base), members, request);

        assertThat(result.isCommitted()).isTrue();
        assertThat(result.committedRoot()).isEqualTo(engineRoot);
        assertThat(result.applyResult().committed()).isTrue();
        assertThat(result.penalized()).isEmpty();
        assertThat(result.certificate().voteCount()).isGreaterThanOrEqualTo(2);

        RegionSnapshot committed = world.reExtract(region, SnapshotVersion.INITIAL.next(), 1L);
        assertThat(StateRoot.of(CommFixtures.hashes().hash(committed))).isEqualTo(engineRoot);
    }

    @Test
    void quorumCandidatesArePreparedBeforeVotingAndCertifiedBeforeApply() {
        RegionSnapshot base = CommFixtures.fullUniformSnapshot(region, 0);
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(base);
        CommitteeSession session = CommitteeSession.mvp(
                new WorldMutationApplier(world), new ReliabilityLedger());

        List<RecordingPersistence> stores = List.of(
                new RecordingPersistence(), new RecordingPersistence(), new RecordingPersistence());
        List<CommitteeMember> members = stores.stream()
                .map(store -> new CommitteeMember(
                        CommFixtures.identity(), CommFixtures.engine(), store))
                .toList();

        RegionExecutionRequest request = CommFixtures.request(base, batch());
        CommitResult result = session.runBatch(
                new ProposalKey(region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL),
                CommFixtures.rootOf(base), members, request);

        assertThat(result.isCommitted()).isTrue();
        assertThat(stores).allSatisfy(store -> {
            assertThat(store.prepared).isEqualTo(1);
            assertThat(store.committed).isBetween(0, 1);
            assertThat(store.preparedResult.resultingRoot()).isEqualTo(result.committedRoot());
            if (store.committed == 1) {
                assertThat(store.certificate).isEqualTo(result.certificate());
            }
        });
        assertThat(stores.stream().mapToInt(store -> store.committed).sum())
                .isEqualTo(result.certificate().voteCount());
    }

    @Test
    void persistenceFailurePreventsAcceptVote() {
        RegionSnapshot base = CommFixtures.fullUniformSnapshot(region, 0);
        RegionExecutionRequest request = CommFixtures.request(base, batch());
        VotePersistence failing = new VotePersistence() {
            @Override
            public void prepare(RegionExecutionRequest ignored, RegionExecutionResult result) {
                throw new IllegalStateException("disk unavailable");
            }

            @Override
            public void commit(QuorumCertificate certificate) {
                throw new AssertionError("commit must not run");
            }
        };
        CommitteeMember member = new CommitteeMember(
                CommFixtures.identity(), CommFixtures.engine(), failing);

        assertThatThrownBy(() -> member.computeAndVote(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("disk unavailable");
    }

    private static final class RecordingPersistence implements VotePersistence {
        private int prepared;
        private int committed;
        private RegionExecutionResult preparedResult;
        private QuorumCertificate certificate;

        @Override
        public void prepare(RegionExecutionRequest request, RegionExecutionResult result) {
            prepared++;
            preparedResult = result;
        }

        @Override
        public void commit(QuorumCertificate certificate) {
            if (preparedResult == null) {
                throw new IllegalStateException("candidate was not prepared");
            }
            committed++;
            this.certificate = certificate;
        }
    }
}
