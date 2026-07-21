package dev.nodera.committee;

import dev.nodera.consensus.ProposalKey;
import dev.nodera.core.event.BlockChangedEvent;
import dev.nodera.core.event.CommittedEventEnvelope;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.BlockMutation;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.coordinator.InMemoryWorldView;
import dev.nodera.coordinator.LagHandoffPolicy;
import dev.nodera.coordinator.LeaseManager;
import dev.nodera.coordinator.ReliabilityLedger;
import dev.nodera.coordinator.WorldMutationApplier;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.storage.ContentId;
import dev.nodera.storage.event.EventReplayer;
import dev.nodera.storage.event.InMemoryCertificateStore;
import dev.nodera.storage.event.InMemoryRegionEventStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Headless proof that sustained region lag hands off only the lagging region and play continues. */
class LagHandoffIT {

    @Test
    void sustainedLagDemotesOnlyItsPrimaryAndPromotedValidatorContinuesAtNextEpoch() {
        RegionId laggingRegion = CommFixtures.region(0, 0);
        RegionId healthyRegion = CommFixtures.region(1, 0);
        NodeIdentity laggingPrimary = CommFixtures.identity();
        NodeIdentity validatorB = CommFixtures.identity();
        NodeIdentity validatorC = CommFixtures.identity();
        NodeIdentity healthyPrimary = CommFixtures.identity();
        NodeIdentity healthyValidatorB = CommFixtures.identity();
        NodeIdentity healthyValidatorC = CommFixtures.identity();

        LeaseManager leases = new LeaseManager(200);
        RegionLease laggingLease = leases.issue(laggingRegion, laggingPrimary.nodeId(),
                List.of(validatorB.nodeId(), validatorC.nodeId()), 0);
        RegionLease healthyLease = leases.issue(healthyRegion, healthyPrimary.nodeId(),
                List.of(healthyValidatorB.nodeId(), healthyValidatorC.nodeId()), 0);
        ReliabilityLedger reliability = new ReliabilityLedger(0.02, 0.95, 1.0);
        long threshold = 4L * LagHandoffPolicy.TICK_BASIS_POINTS;
        LagHandoffPolicy policy = new LagHandoffPolicy(threshold, 3, 40);

        for (long tick = 1; tick <= 2; tick++) {
            assertThat(policy.observe(laggingLease, threshold + 1, tick)).isEmpty();
            assertThat(policy.observe(healthyLease, threshold, tick)).isEmpty();
        }
        LagHandoffPolicy.Decision decision =
                policy.observe(laggingLease, threshold + 1, 3).orElseThrow();
        assertThat(policy.observe(healthyLease, threshold, 3)).isEmpty();
        assertThat(reliability.score(laggingPrimary.nodeId())).isEqualTo(1.0);

        RegionLease promoted = CommitteeFailover.promoteOnLag(decision, leases, reliability, 20);

        assertThat(promoted).isNotNull();
        assertThat(promoted.region()).isEqualTo(laggingRegion);
        assertThat(promoted.epoch()).isEqualTo(decision.epoch().bump());
        assertThat(promoted.primary()).isIn(validatorB.nodeId(), validatorC.nodeId());
        assertThat(promoted.contains(laggingPrimary.nodeId())).isFalse();
        assertThat(reliability.eligibleForAssignment(laggingPrimary.nodeId())).isFalse();
        assertThat(reliability.score(healthyPrimary.nodeId())).isEqualTo(1.0);
        assertThat(reliability.score(validatorB.nodeId())).isEqualTo(1.0);
        assertThat(reliability.score(validatorC.nodeId())).isEqualTo(1.0);
        assertThat(leases.leaseOf(healthyRegion)).isEqualTo(healthyLease);
        assertThat(leases.currentEpoch(healthyRegion).value()).isZero();

        double penaltyAfterHandoff = reliability.score(laggingPrimary.nodeId());
        assertThat(CommitteeFailover.promoteOnLag(decision, leases, reliability, 21)).isNull();
        assertThat(CommitteeFailover.promoteOnLag(
                new LagHandoffPolicy.Decision(laggingRegion, decision.epoch(), promoted.primary()),
                leases, reliability, 22)).isNull();
        assertThat(CommitteeFailover.promoteOnLag(
                new LagHandoffPolicy.Decision(laggingRegion, promoted.epoch(), laggingPrimary.nodeId()),
                leases, reliability, 23)).isNull();
        assertThat(leases.currentEpoch(laggingRegion)).isEqualTo(promoted.epoch());
        assertThat(reliability.score(laggingPrimary.nodeId())).isEqualTo(penaltyAfterHandoff);
        assertThat(reliability.score(promoted.primary())).isEqualTo(1.0);

        Map<NodeId, CommitteeMember> members = new HashMap<>();
        members.put(validatorB.nodeId(), new CommitteeMember(validatorB, CommFixtures.engine()));
        members.put(validatorC.nodeId(), new CommitteeMember(validatorC, CommFixtures.engine()));
        RegionSnapshot base = CommFixtures.fullUniformSnapshot(laggingRegion, 0);
        RegionSnapshot healthyBase = CommFixtures.fullUniformSnapshot(healthyRegion, 0);
        StateRoot healthyRoot = CommFixtures.rootOf(healthyBase);
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(base);
        world.load(healthyBase);
        CommitteeSession session = CommitteeSession.mvp(new WorldMutationApplier(world), reliability);
        var batch = CommFixtures.batch(laggingRegion, promoted.epoch(), SnapshotVersion.INITIAL, 0, 1,
                List.of(CommFixtures.place(laggingRegion, 1, 0, 5, 70, 5, 1)));
        RegionExecutionRequest request = CommFixtures.request(base, batch);
        var expectedExecution = CommFixtures.engine().execute(request);
        StateRoot expectedRoot = expectedExecution.resultingRoot();

        CommitResult result = session.runBatch(
                new ProposalKey(laggingRegion, promoted.epoch(), SnapshotVersion.INITIAL),
                CommFixtures.rootOf(base), committee(members, promoted), request);

        assertThat(result.isCommitted()).isTrue();
        assertThat(result.committedRoot()).isEqualTo(expectedRoot);
        assertThat(leases.currentEpoch(laggingRegion)).isEqualTo(decision.epoch().bump());
        RegionSnapshot committed = world.reExtract(laggingRegion, SnapshotVersion.INITIAL.next(), 1);
        assertThat(StateRoot.of(CommFixtures.hashes().hash(committed))).isEqualTo(expectedRoot);
        RegionSnapshot untouchedNeighbour = world.reExtract(
                healthyRegion, SnapshotVersion.INITIAL, 0);
        assertThat(StateRoot.of(CommFixtures.hashes().hash(untouchedNeighbour)))
                .isEqualTo(healthyRoot);

        InMemoryCertificateStore certificates =
                new InMemoryCertificateStore(CommFixtures.hashes());
        ContentId certificateId = certificates.put(result.certificate());
        InMemoryRegionEventStore events = new InMemoryRegionEventStore();
        BlockMutation mutation = expectedExecution.delta().blockMutations().getFirst();
        events.append(new CommittedEventEnvelope(
                laggingRegion,
                promoted.epoch(),
                result.certificate().version(),
                request.context().tickTo(),
                0,
                new BlockChangedEvent(
                        mutation.pos(),
                        mutation.expectedPreviousStateId(),
                        mutation.newStateId()),
                result.certificate().prevRoot(),
                result.certificate().resultingRoot(),
                certificateId.hash()));

        EventReplayer.ReplayResult replay = EventReplayer.replay(
                events, certificates, laggingRegion, CommFixtures.rootOf(base), 0);
        assertThat(replay.finalRoot()).isEqualTo(expectedRoot);
        assertThat(replay.eventsApplied()).isEqualTo(1);
        assertThat(replay.stoppedAtUncertified()).isFalse();
    }

    private static List<CommitteeMember> committee(
            Map<NodeId, CommitteeMember> members, RegionLease lease) {
        List<CommitteeMember> committee = new ArrayList<>();
        committee.add(members.get(lease.primary()));
        for (NodeId validator : lease.validators()) {
            committee.add(members.get(validator));
        }
        return committee;
    }
}
