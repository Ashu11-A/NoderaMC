package dev.nodera.committee;

import dev.nodera.consensus.ProposalKey;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.coordinator.InMemoryWorldView;
import dev.nodera.coordinator.ReliabilityLedger;
import dev.nodera.coordinator.WorldMutationApplier;
import dev.nodera.simulation.RegionExecutionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
