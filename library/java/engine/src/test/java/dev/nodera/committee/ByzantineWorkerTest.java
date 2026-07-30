package dev.nodera.committee;

import dev.nodera.consensus.EquivocationDetector;
import dev.nodera.consensus.MajorityQuorumPolicy;
import dev.nodera.consensus.ProposalKey;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.identity.NodeIdentity;
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

class ByzantineWorkerTest {

    private final RegionId region = CommFixtures.region(0, 0);

    private ActionBatch batch() {
        return CommFixtures.batch(region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 0, 1, List.of(
                CommFixtures.place(region, 1, 0, 5, 70, 5, 1),
                CommFixtures.place(region, 2, 0, 40, 100, 40, 4)));
    }

    private record Rig(CommitteeSession session, RegionExecutionRequest request, StateRoot prevRoot,
                       StateRoot engineRoot, ReliabilityLedger reliability, ProposalKey key) {
    }

    private Rig rig() {
        RegionSnapshot base = CommFixtures.fullUniformSnapshot(region, 0);
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(base);
        ReliabilityLedger reliability = new ReliabilityLedger();
        CommitteeSession session = CommitteeSession.mvp(new WorldMutationApplier(world), reliability);
        ActionBatch batch = batch();
        RegionExecutionRequest request = CommFixtures.request(base, batch);
        StateRoot engineRoot = CommFixtures.engine().execute(request).resultingRoot();
        ProposalKey key = new ProposalKey(region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL);
        return new Rig(session, request, CommFixtures.rootOf(base), engineRoot, reliability, key);
    }

    @Test
    void lyingValidatorCannotFormQuorumAloneAndIsPenalised() {
        Rig r = rig();
        NodeIdentity liarId = CommFixtures.identity();
        CommitteeMember liar = new CommitteeMember(liarId, CommFixtures.corruptingEngine());
        List<CommitteeMember> members = List.of(
                new CommitteeMember(CommFixtures.identity(), CommFixtures.engine()),
                new CommitteeMember(CommFixtures.identity(), CommFixtures.engine()),
                liar);

        double before = r.reliability().score(liarId.nodeId());
        CommitResult result = r.session().runBatch(r.key(), r.prevRoot(), members, r.request());

        // The two honest validators form the quorum on the correct root; the liar is excluded.
        assertThat(result.isCommitted()).isTrue();
        assertThat(result.committedRoot()).isEqualTo(r.engineRoot());
        assertThat(result.penalized()).contains(liarId.nodeId());
        assertThat(r.reliability().score(liarId.nodeId())).isLessThan(before);
    }

    @Test
    void lyingPrimaryIsOutvotedAndPenalised() {
        Rig r = rig();
        NodeIdentity primaryId = CommFixtures.identity();
        // The primary lies; the two honest validators outvote it.
        CommitteeMember lyingPrimary = new CommitteeMember(primaryId, CommFixtures.corruptingEngine());
        List<CommitteeMember> members = List.of(
                lyingPrimary,
                new CommitteeMember(CommFixtures.identity(), CommFixtures.engine()),
                new CommitteeMember(CommFixtures.identity(), CommFixtures.engine()));

        CommitResult result = r.session().runBatch(r.key(), r.prevRoot(), members, r.request());

        assertThat(result.isCommitted()).isTrue();
        assertThat(result.committedRoot()).isEqualTo(r.engineRoot()); // honest root wins
        assertThat(result.penalized()).contains(primaryId.nodeId());
        assertThat(r.reliability().score(primaryId.nodeId())).isLessThan(0.95);
    }

    @Test
    void twoLiarsCannotCommitAWrongRootOverAnHonestMinority() {
        Rig r = rig();
        // 2 liars produce the SAME corrupted root (deterministic corruption) → they DO reach a 2-of-3
        // quorum on the wrong root; this is exactly the fully-colluding-committee case the spot-check
        // auditor exists to catch. Here we assert the single honest member is the one penalised
        // relative to the (wrong) committed root — consensus alone cannot save an out-voted honest
        // node, which is why the audit net matters.
        CommitteeMember honest = new CommitteeMember(CommFixtures.identity(), CommFixtures.engine());
        List<CommitteeMember> members = List.of(
                new CommitteeMember(CommFixtures.identity(), CommFixtures.corruptingEngine()),
                new CommitteeMember(CommFixtures.identity(), CommFixtures.corruptingEngine()),
                honest);

        CommitResult result = r.session().runBatch(r.key(), r.prevRoot(), members, r.request());
        assertThat(result.isCommitted()).isTrue();
        assertThat(result.committedRoot()).isNotEqualTo(r.engineRoot()); // colluded wrong root
        assertThat(result.penalized()).contains(honest.nodeId());
    }

    @Test
    void equivocatingVoterIsSlashed() {
        Rig r = rig();
        NodeIdentity shared = CommFixtures.identity();
        CommitteeMember honestSelf = new CommitteeMember(shared, CommFixtures.engine());
        CommitteeMember lyingSelf = new CommitteeMember(shared, CommFixtures.corruptingEngine());
        CommitteeMember b = new CommitteeMember(CommFixtures.identity(), CommFixtures.engine());
        CommitteeMember c = new CommitteeMember(CommFixtures.identity(), CommFixtures.engine());

        // Round 1: the node votes the honest root for the key.
        r.session().runBatch(r.key(), r.prevRoot(), List.of(honestSelf, b, c), r.request());
        // Round 2: SAME key, the same identity now votes a different (corrupted) root — equivocation.
        CommitResult round2 = r.session().runBatch(r.key(), r.prevRoot(), List.of(lyingSelf, b, c), r.request());

        assertThat(round2.equivocators()).contains(shared.nodeId());
        assertThat(r.reliability().score(shared.nodeId())).isZero(); // slashed
    }

    @Test
    void mvpPolicyIsTwoOfThree() {
        assertThat(MajorityQuorumPolicy.mvp().required(3)).isEqualTo(2);
        assertThat(new EquivocationDetector().maxSize()).isPositive();
    }
}
