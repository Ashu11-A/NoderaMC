package dev.nodera.committee;

import dev.nodera.consensus.ProposalKey;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.coordinator.InMemoryWorldView;
import dev.nodera.coordinator.LeaseManager;
import dev.nodera.coordinator.ReliabilityLedger;
import dev.nodera.coordinator.WorldMutationApplier;
import dev.nodera.simulation.RegionExecutionRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headless Phase 3 MVP-gate scenario (Task 7, the first playable milestone): a flat world with
 * a 3-member committee. A batch is placed where A is primary and B/C are validators; all three
 * re-execute to identical roots and the server commits on the 2-of-3 quorum; then A disconnects, a
 * validator is promoted to primary under a bumped epoch, and play continues (a second batch commits
 * over the surviving 2-member committee). Runs entirely over the
 * {@link dev.nodera.simulation.engine.FlatWorldRegionEngine} and the {@code InMemoryWorldView} seam.
 */
class CommitteeMvpIT {

    private final RegionId region = CommFixtures.region(0, 0);

    @Test
    void quorumCommitThenPrimaryFailoverContinuesPlay() {
        // --- committee + world setup ---
        NodeIdentity idA = CommFixtures.identity();
        NodeIdentity idB = CommFixtures.identity();
        NodeIdentity idC = CommFixtures.identity();
        Map<NodeId, CommitteeMember> members = new HashMap<>();
        members.put(idA.nodeId(), new CommitteeMember(idA, CommFixtures.engine()));
        members.put(idB.nodeId(), new CommitteeMember(idB, CommFixtures.engine()));
        members.put(idC.nodeId(), new CommitteeMember(idC, CommFixtures.engine()));

        RegionSnapshot base = CommFixtures.fullUniformSnapshot(region, 0);
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(base);
        WorldMutationApplier applier = new WorldMutationApplier(world);
        ReliabilityLedger reliability = new ReliabilityLedger();
        CommitteeSession session = CommitteeSession.mvp(applier, reliability);
        LeaseManager leases = new LeaseManager(200);

        RegionLease lease = leases.issue(region, idA.nodeId(),
                List.of(idB.nodeId(), idC.nodeId()), 0); // epoch 0, A primary

        // --- batch 1: A primary, B/C validators, all agree, quorum commits ---
        var batch1 = CommFixtures.batch(region, lease.epoch(), SnapshotVersion.INITIAL, 0, 1, List.of(
                CommFixtures.place(region, 1, 0, 5, 70, 5, 1),
                CommFixtures.place(region, 2, 0, 40, 100, 40, 4)));
        RegionExecutionRequest request1 = CommFixtures.request(base, batch1);
        StateRoot engineRoot1 = CommFixtures.engine().execute(request1).resultingRoot();
        ProposalKey key1 = new ProposalKey(region, lease.epoch(), SnapshotVersion.INITIAL);

        CommitResult r1 = session.runBatch(key1, CommFixtures.rootOf(base),
                committee(members, lease), request1);
        assertThat(r1.isCommitted()).isTrue();
        assertThat(r1.committedRoot()).isEqualTo(engineRoot1);

        RegionSnapshot afterBatch1 = world.reExtract(region, SnapshotVersion.INITIAL.next(), 1L);
        assertThat(StateRoot.of(CommFixtures.hashes().hash(afterBatch1))).isEqualTo(engineRoot1);

        // --- A disconnects: promote a validator under a bumped epoch ---
        RegionLease failedOver = CommitteeFailover.promoteOnPrimaryLoss(lease, leases, 100);
        assertThat(failedOver).isNotNull();
        assertThat(failedOver.epoch().value()).isEqualTo(1);
        assertThat(failedOver.primary()).isNotEqualTo(idA.nodeId());

        // --- batch 2: surviving 2-member committee keeps play going under the new epoch ---
        var batch2 = CommFixtures.batch(region, failedOver.epoch(), SnapshotVersion.INITIAL.next(), 2, 3,
                List.of(CommFixtures.place(region, 100, 3, 12, 60, 12, 2)));
        RegionExecutionRequest request2 = CommFixtures.request(afterBatch1, batch2);
        StateRoot engineRoot2 = CommFixtures.engine().execute(request2).resultingRoot();
        ProposalKey key2 = new ProposalKey(region, failedOver.epoch(), SnapshotVersion.INITIAL.next());

        CommitResult r2 = session.runBatch(key2, CommFixtures.rootOf(afterBatch1),
                committee(members, failedOver), request2);
        assertThat(r2.isCommitted()).isTrue();
        assertThat(r2.committedRoot()).isEqualTo(engineRoot2);

        RegionSnapshot afterBatch2 = world.reExtract(region, SnapshotVersion.INITIAL.next().next(), 3L);
        assertThat(StateRoot.of(CommFixtures.hashes().hash(afterBatch2))).isEqualTo(engineRoot2);
    }

    private static List<CommitteeMember> committee(Map<NodeId, CommitteeMember> members, RegionLease lease) {
        List<CommitteeMember> out = new ArrayList<>();
        out.add(members.get(lease.primary()));
        for (NodeId v : lease.validators()) {
            out.add(members.get(v));
        }
        return out;
    }
}
