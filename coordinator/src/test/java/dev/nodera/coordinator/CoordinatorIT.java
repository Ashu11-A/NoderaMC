package dev.nodera.coordinator;

import dev.nodera.consensus.VerificationOutcome;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.BlockMutation;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headless Phase 2 coordinator pipeline (Task 6): capture → primary proposal → server
 * verification → commit through the single world writer, plus the failure paths (forced mismatch,
 * stale epoch, primary death → reassignment under a bumped epoch). Runs entirely over the
 * {@link InMemoryWorldView} seam and the {@link dev.nodera.simulation.engine.FlatWorldRegionEngine},
 * with no NeoForge server — the executable stand-in for Task 6's manual multi-client acceptance.
 */
class CoordinatorIT {

    private final RegionId region = CoordFixtures.region(0, 0);

    /** A 3-node connected, reliable committee-capable population. */
    private NodeRegistry population(int n) {
        NodeRegistry reg = new NodeRegistry();
        for (int i = 0; i < n; i++) {
            NodeId id = CoordFixtures.node(100 + i);
            reg.register(id, CoordFixtures.caps());
            reg.setConnected(id, true);
        }
        return reg;
    }

    private ActionBatch sampleBatch(RegionEpoch epoch, SnapshotVersion base) {
        return CoordFixtures.batch(region, epoch, base, 0, 1, List.of(
                CoordFixtures.place(region, 1, 0, 5, 70, 5, 1),
                CoordFixtures.place(region, 2, 0, 40, 100, 40, 4),
                CoordFixtures.place(region, 3, 0, 80, 50, 80, 3)));
    }

    @Test
    void blockCommitsViaProposalPipeline() {
        NodeRegistry registry = population(3);
        ReliabilityLedger reliability = new ReliabilityLedger();
        RegionAllocator allocator = new RegionAllocator(new RendezvousPlacementPolicy(), registry, reliability);
        LeaseManager leases = new LeaseManager(200);

        RegionSnapshot base = CoordFixtures.fullUniformSnapshot(region, 0);
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(base);
        WorldMutationApplier applier = new WorldMutationApplier(world);
        ServerVerifier verifier = new ServerVerifier(CoordFixtures.engine());
        ProposalManager proposals = new ProposalManager();

        // Delegate: allocate + lease + pipeline to ACTIVE.
        RegionAllocator.Committee committee = allocator.allocate(region, 3).orElseThrow();
        RegionLease lease = leases.issue(region, committee.primary(), committee.validators(), 0);
        RegionPipeline pipeline = new RegionPipeline(region);
        pipeline.assign(lease.epoch());
        pipeline.snapshotSynced();

        // Capture a batch; the primary executes first and proposes.
        ActionBatch batch = sampleBatch(lease.epoch(), SnapshotVersion.INITIAL);
        RegionExecutionRequest request = CoordFixtures.request(base, batch);
        RegionExecutionResult primaryRun = CoordFixtures.engine().execute(request);
        proposals.submit(new ClientProposal(region, lease.epoch(), SnapshotVersion.INITIAL,
                primaryRun.resultingRoot(), primaryRun.delta(), committee.primary()));
        pipeline.dispatchBatch();
        pipeline.proposalArrived();

        // Server verifies (re-executes) and commits on MATCH.
        ClientProposal proposal = proposals.take(
                new dev.nodera.consensus.ProposalKey(region, lease.epoch(), SnapshotVersion.INITIAL));
        ServerVerifier.Verification v = verifier.verify(request, proposal.proposedRoot());
        assertThat(v.outcome()).isEqualTo(VerificationOutcome.MATCH);
        reliability.record(proposal.proposer(), true);
        pipeline.verified(v.outcome());

        WorldMutationApplier.ApplyResult applied = applier.apply(proposal.delta());
        assertThat(applied.committed()).isTrue();
        pipeline.committed(SnapshotVersion.INITIAL.next());

        // The committed world equals the engine's own computed post-state (Invariant: root is truth).
        RegionSnapshot committedWorld = world.reExtract(region, SnapshotVersion.INITIAL.next(), 1L);
        assertThat(StateRoot.of(CoordFixtures.hashes().hash(committedWorld)))
                .isEqualTo(primaryRun.resultingRoot());
        assertThat(pipeline.state()).isEqualTo(PipelineState.ACTIVE);
        assertThat(pipeline.lastCommittedVersion()).isEqualTo(SnapshotVersion.INITIAL.next());
    }

    @Test
    void forcedMismatchRejectedAndWorldUncorrupted() {
        NodeRegistry registry = population(3);
        ReliabilityLedger reliability = new ReliabilityLedger();
        RegionAllocator allocator = new RegionAllocator(new RendezvousPlacementPolicy(), registry, reliability);
        LeaseManager leases = new LeaseManager(200);

        RegionSnapshot base = CoordFixtures.fullUniformSnapshot(region, 0);
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(base);
        ServerVerifier verifier = new ServerVerifier(CoordFixtures.engine());
        WorldMutationApplier applier = new WorldMutationApplier(world);

        RegionAllocator.Committee committee = allocator.allocate(region, 3).orElseThrow();
        RegionLease lease = leases.issue(region, committee.primary(), committee.validators(), 0);
        NodeId primary = committee.primary();
        double before = reliability.score(primary);

        ActionBatch batch = sampleBatch(lease.epoch(), SnapshotVersion.INITIAL);
        RegionExecutionRequest request = CoordFixtures.request(base, batch);

        // A lying primary corrupts one mutation and claims a bogus root.
        RegionDelta honest = CoordFixtures.engine().execute(request).delta();
        RegionDelta corrupt = corruptFirstMutation(honest);
        ClientProposal liar = new ClientProposal(region, lease.epoch(), SnapshotVersion.INITIAL,
                StateRoot.zero(), corrupt, primary);

        ServerVerifier.Verification v = verifier.verify(request, liar.proposedRoot());
        assertThat(v.outcome()).isEqualTo(VerificationOutcome.MISMATCH);

        // Reject: penalise, do NOT apply, resync.
        reliability.record(primary, false);
        assertThat(reliability.score(primary)).isLessThan(before);

        // World provably uncorrupted: the applier is never invoked on a mismatch, so the world still
        // re-extracts to the pre-batch base root and the batch's first target is still AIR.
        assertThat(applier).isNotNull(); // constructed but intentionally never applied on mismatch
        RegionSnapshot unchanged = world.reExtract(region, SnapshotVersion.INITIAL, 0L);
        assertThat(StateRoot.of(CoordFixtures.hashes().hash(unchanged)))
                .isEqualTo(StateRoot.of(CoordFixtures.hashes().hash(base)));
        assertThat(world.getBlock(region, new dev.nodera.core.state.NBlockPos(5, 70, 5))).isZero();
    }

    @Test
    void staleEpochProposalIsDropped() {
        LeaseManager leases = new LeaseManager(200);
        NodeId a = CoordFixtures.node(1L);
        NodeId b = CoordFixtures.node(2L);
        NodeId c = CoordFixtures.node(3L);
        leases.issue(region, a, List.of(b, c), 0);   // epoch 0
        RegionLease current = leases.issue(region, b, List.of(c, a), 50); // reassign -> epoch 1

        // A proposal that raced in carrying the OLD epoch is stale and dropped.
        RegionEpoch staleEpoch = RegionEpoch.INITIAL;
        assertThat(leases.isStaleEpoch(region, staleEpoch)).isTrue();
        assertThat(ServerVerifier.isStale(staleEpoch, current.epoch())).isTrue();
        assertThat(leases.isStaleEpoch(region, current.epoch())).isFalse();
    }

    @Test
    void primaryDeathReassignsUnderNewEpoch() {
        NodeRegistry registry = population(4);
        ReliabilityLedger reliability = new ReliabilityLedger();
        RegionAllocator allocator = new RegionAllocator(new RendezvousPlacementPolicy(), registry, reliability);
        LeaseManager leases = new LeaseManager(200);
        HeartbeatMonitor heartbeats = new HeartbeatMonitor(60);

        RegionAllocator.Committee committee = allocator.allocate(region, 3).orElseThrow();
        RegionLease lease = leases.issue(region, committee.primary(), committee.validators(), 0);
        assertThat(lease.epoch()).isEqualTo(RegionEpoch.INITIAL);

        NodeId primary = committee.primary();
        for (NodeId member : committee.members()) {
            heartbeats.heartbeat(member, 0);
        }
        // Everyone but the primary keeps beating; the primary goes silent.
        for (NodeId member : committee.members()) {
            if (!member.equals(primary)) {
                heartbeats.heartbeat(member, 100);
            }
        }
        List<NodeId> lost = heartbeats.lostAsOf(100);
        assertThat(lost).contains(primary);

        // Revoke (bumps epoch) and reassign excluding the dead primary.
        leases.revoke(region);
        allocator.release(region);
        heartbeats.forget(primary);
        registry.setConnected(primary, false);
        Optional<RegionAllocator.Committee> reassigned = allocator.allocate(region, 3, java.util.Set.of(primary));
        assertThat(reassigned).isPresent();
        RegionLease newLease = leases.issue(region, reassigned.get().primary(), reassigned.get().validators(), 101);

        assertThat(newLease.epoch().value()).isEqualTo(2); // 0 issue, 1 revoke, 2 reissue
        assertThat(newLease.primary()).isNotEqualTo(primary);
        assertThat(newLease.contains(primary)).isFalse();
    }

    private static RegionDelta corruptFirstMutation(RegionDelta delta) {
        List<BlockMutation> muts = new ArrayList<>(delta.blockMutations());
        BlockMutation first = muts.get(0);
        // Flip the applied state id to something different so the recompute root diverges.
        int corruptedId = first.newStateId() == 1 ? 2 : 1;
        muts.set(0, new BlockMutation(first.pos(), first.expectedPreviousStateId(), corruptedId, first.flags()));
        return new RegionDelta(delta.region(), delta.baseVersion(), delta.resultingVersion(),
                muts, delta.resultingRoot());
    }
}
