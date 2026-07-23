package dev.nodera.coordinator.entity;

import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.EntityMutation;
import dev.nodera.core.state.EntityTransferIntent;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.coordinator.InMemoryWorldView;
import dev.nodera.coordinator.PipelineState;
import dev.nodera.coordinator.RegionPipeline;
import dev.nodera.coordinator.WorldMutationApplier;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.entity.ItemEntityRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class EntityTransferCoordinatorTest {

    private final RegionId sourceRegion = TestFixtures.region(0, 0);
    private final RegionId targetRegion = TestFixtures.region(1, 0);
    private final JointTransferApprover approvals = JointTransferApprover.mvp(members(), members());
    private final InMemoryWorldView world = new InMemoryWorldView();
    private final List<String> journalOrder = new ArrayList<>();
    private RegionPipeline sourcePipeline;
    private RegionPipeline targetPipeline;
    private PersistedEntityState sourceEntity;
    private PersistedEntityState targetEntity;
    private RegionSnapshot sourceSnapshot;
    private RegionSnapshot targetSnapshot;
    private RegionDelta sourceDelta;
    private EntityTransferCoordinator coordinator;

    @BeforeEach
    void setUp() {
        sourceEntity = item(FixedVec3.ofBlock(127, 5, 1), 3);
        targetEntity = new PersistedEntityState(
                sourceEntity.id(), sourceEntity.kind(), sourceEntity.typeId(),
                FixedVec3.ofBlock(128, 5, 1), new FixedVec3(1, -2, 3),
                sourceEntity.ageTicks() + 1, sourceEntity.despawnTick(), sourceEntity.payload());
        RegionSnapshot sourceBlocks = TestFixtures.fullUniformSnapshot(sourceRegion, 0);
        RegionSnapshot targetBlocks = TestFixtures.fullUniformSnapshot(targetRegion, 0);
        sourceSnapshot = new RegionSnapshot(
                sourceRegion, SnapshotVersion.INITIAL, 0,
                sourceBlocks.chunks(), List.of(sourceEntity));
        targetSnapshot = targetBlocks;
        sourceDelta = sourceDelta(targetEntity);
        world.load(sourceSnapshot);
        world.load(targetSnapshot);
        sourcePipeline = active(sourceRegion);
        targetPipeline = active(targetRegion);
        coordinator = new EntityTransferCoordinator(
                new WorldMutationApplier(world), approvals, journal());
    }

    @Test
    void transferMovesSameIdAtomicallyWithIndependentQuorums() {
        EntityTransferCoordinator.TransferResult result = transfer(coordinator);
        assertThat(result.replay()).isFalse();
        assertThat(result.applyResult().applied()).isEqualTo(2);
        assertThat(world.getEntity(sourceRegion, sourceEntity.id())).isNull();
        assertThat(world.getEntity(targetRegion, sourceEntity.id())).isEqualTo(targetEntity);
        assertThat(sourcePipeline.lastCommittedVersion()).isEqualTo(new SnapshotVersion(1));
        assertThat(targetPipeline.lastCommittedVersion()).isEqualTo(new SnapshotVersion(1));
        assertThat(journalOrder).containsExactly("PREPARED", "ACCEPTED", "APPLIED", "COMMITTED");
        assertThat(approvals.verify(result.certificate())).isTrue();
        assertThat(result.certificate().sourceProof().voteCount()).isEqualTo(3);
        assertThat(result.certificate().targetProof().voteCount()).isEqualTo(3);
        assertThat(result.certificate().descriptor().sourceResultingRoot())
                .isEqualTo(result.sourceDelta().resultingRoot());
        assertThat(result.certificate().descriptor().targetResultingRoot())
                .isEqualTo(result.targetDelta().resultingRoot());
        assertThat(result.sourceAccepted().entityId()).isEqualTo(sourceEntity.id());
        assertThat(result.targetAccepted().entityId()).isEqualTo(sourceEntity.id());
    }

    @Test
    void replayOfCompletedTransferIsNoOp() {
        EntityTransferCoordinator.TransferResult first = transfer(coordinator);
        EntityTransferCoordinator.TransferResult replay = transfer(coordinator);
        assertThat(replay.replay()).isTrue();
        assertThat(replay.applyResult().applied()).isZero();
        assertThat(replay.certificate()).isEqualTo(first.certificate());
        assertThat(journalOrder).containsExactly("PREPARED", "ACCEPTED", "APPLIED", "COMMITTED");
    }

    @Test
    void staleTargetSnapshotAbortsBothPipelinesWithNoSourceLoss() {
        world.setEntity(targetRegion, item(FixedVec3.ofBlock(129, 5, 1), 4));
        assertThatThrownBy(() -> transfer(coordinator))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("do not match live");
        assertThat(world.getEntity(sourceRegion, sourceEntity.id())).isEqualTo(sourceEntity);
        assertThat(sourcePipeline.state()).isEqualTo(PipelineState.ACTIVE);
        assertThat(targetPipeline.state()).isEqualTo(PipelineState.ACTIVE);
        assertThat(journalOrder).isEmpty();
    }

    @Test
    void failedPrepareJournalLeavesWorldUntouchedAndPipelinesActive() {
        EntityTransferCoordinator failing = new EntityTransferCoordinator(
                new WorldMutationApplier(world), approvals, new JournalAdapter() {
            @Override
            public void prepared(EntityTransferCoordinator.TransferPlan plan) {
                throw new IllegalStateException("disk unavailable");
            }
        });
        assertThatThrownBy(() -> transfer(failing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disk unavailable");
        assertThat(world.getEntity(sourceRegion, sourceEntity.id())).isEqualTo(sourceEntity);
        assertThat(world.getEntity(targetRegion, sourceEntity.id())).isNull();
        assertThat(sourcePipeline.state()).isEqualTo(PipelineState.ACTIVE);
        assertThat(targetPipeline.state()).isEqualTo(PipelineState.ACTIVE);
    }

    @Test
    void restoredCompletedTransferReplaysAfterCoordinatorRestart() {
        EntityTransferCoordinator.TransferResult committed = transfer(coordinator);
        EntityTransferCoordinator restarted = new EntityTransferCoordinator(
                new WorldMutationApplier(world), approvals,
                EntityTransferCoordinator.TransferJournal.NOOP);
        restarted.restoreCompleted(committed);
        EntityTransferCoordinator.TransferResult replay = transfer(restarted);
        assertThat(replay.replay()).isTrue();
        assertThat(world.getEntity(targetRegion, sourceEntity.id())).isEqualTo(targetEntity);
    }

    @Test
    void historicalCompletedTransferCanRestoreAfterEntityMovedAgain() {
        EntityTransferCoordinator.TransferResult committed = transfer(coordinator);
        PersistedEntityState movedAgain = new PersistedEntityState(
                targetEntity.id(), targetEntity.kind(), targetEntity.typeId(),
                FixedVec3.ofBlock(130, 5, 1), targetEntity.vel(), targetEntity.ageTicks() + 1,
                targetEntity.despawnTick(), targetEntity.payload());
        world.setEntity(targetRegion, movedAgain);
        EntityTransferCoordinator restarted = new EntityTransferCoordinator(
                new WorldMutationApplier(world), approvals,
                EntityTransferCoordinator.TransferJournal.NOOP);

        restarted.restoreCompleted(committed);

        assertThat(world.getEntity(targetRegion, targetEntity.id())).isEqualTo(movedAgain);
    }

    @Test
    @Invariant(11)
    void acceptedStageRecoveryCompletesPartiallyAppliedWorld() {
        EntityTransferCoordinator.TransferResult committed = transfer(coordinator);
        EntityTransferCoordinator.TransferPlan plan = planOf(committed);

        InMemoryWorldView recoveredWorld = new InMemoryWorldView();
        recoveredWorld.load(sourceSnapshot);
        recoveredWorld.load(targetSnapshot);
        recoveredWorld.removeEntity(sourceRegion, sourceEntity.id());
        RegionPipeline recoveredSource = active(sourceRegion);
        RegionPipeline recoveredTarget = active(targetRegion);
        EntityTransferCoordinator restarted = new EntityTransferCoordinator(
                new WorldMutationApplier(recoveredWorld), approvals,
                EntityTransferCoordinator.TransferJournal.NOOP);

        EntityTransferCoordinator.TransferResult recovered =
                (EntityTransferCoordinator.TransferResult) restarted.restorePending(
                        new EntityTransferCoordinator.TransferRecovery(
                                plan, committed.certificate(),
                                EntityTransferCoordinator.TransferStage.ACCEPTED),
                        recoveredSource, recoveredTarget);
        assertThat(recoveredWorld.getEntity(sourceRegion, sourceEntity.id())).isNull();
        assertThat(recoveredWorld.getEntity(targetRegion, sourceEntity.id())).isEqualTo(targetEntity);
        assertThat(recovered.applyResult().applied()).isEqualTo(1);
    }

    @Test
    void committedJournalFailureKeepsPipelinesPausedAndRetryCompletes() {
        int[] commits = {0};
        EntityTransferCoordinator retrying = new EntityTransferCoordinator(
                new WorldMutationApplier(world), approvals, new JournalAdapter() {
            @Override
            public void committed(
                    EntityTransferCoordinator.TransferPlan plan,
                    dev.nodera.core.consensuscert.EntityTransferCertificate certificate) {
                if (commits[0]++ == 0) {
                    throw new IllegalStateException("commit log unavailable");
                }
            }
        });

        assertThatThrownBy(() -> transfer(retrying))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("commit log unavailable");
        assertThat(sourcePipeline.state()).isEqualTo(PipelineState.PAUSED_FOR_XR);
        assertThat(targetPipeline.state()).isEqualTo(PipelineState.PAUSED_FOR_XR);
        assertThat(world.getEntity(sourceRegion, sourceEntity.id())).isNull();
        assertThat(world.getEntity(targetRegion, targetEntity.id())).isEqualTo(targetEntity);

        assertThat(transfer(retrying)).isNotNull();
        assertThat(sourcePipeline.state()).isEqualTo(PipelineState.ACTIVE);
        assertThat(targetPipeline.state()).isEqualTo(PipelineState.ACTIVE);
        assertThat(commits[0]).isEqualTo(2);
    }

    @Test
    void invalidJointCertificateNeverMutatesWorld() {
        EntityTransferCoordinator.TransferApprovalProvider invalid =
                new EntityTransferCoordinator.TransferApprovalProvider() {
                    @Override
                    public dev.nodera.core.consensuscert.EntityTransferCertificate approve(
                            dev.nodera.core.state.EntityTransferDescriptor descriptor,
                            RegionDelta source, RegionDelta target) {
                        return approvals.approve(descriptor, source, target);
                    }

                    @Override
                    public boolean verify(
                            dev.nodera.core.consensuscert.EntityTransferCertificate certificate) {
                        return false;
                    }
                };
        EntityTransferCoordinator rejecting = new EntityTransferCoordinator(
                new WorldMutationApplier(world), invalid, EntityTransferCoordinator.TransferJournal.NOOP);
        assertThatThrownBy(() -> transfer(rejecting))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("certificate");
        assertThat(world.getEntity(sourceRegion, sourceEntity.id())).isEqualTo(sourceEntity);
        assertThat(world.getEntity(targetRegion, sourceEntity.id())).isNull();
        assertThat(sourcePipeline.state()).isEqualTo(PipelineState.PAUSED_FOR_XR);
        assertThat(targetPipeline.state()).isEqualTo(PipelineState.PAUSED_FOR_XR);
    }

    @Test
    void reusedTransferIdWithDifferentTargetIsRejected() {
        transfer(coordinator);
        PersistedEntityState differentTarget = new PersistedEntityState(
                targetEntity.id(), targetEntity.kind(), targetEntity.typeId(),
                FixedVec3.ofBlock(129, 5, 1), targetEntity.vel(), targetEntity.ageTicks(),
                targetEntity.despawnTick(), targetEntity.payload());
        assertThatThrownBy(() -> coordinator.transfer(
                77, sourcePipeline, targetPipeline, sourceSnapshot, targetSnapshot,
                sourceDelta(differentTarget), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reused");
    }

    @Test
    void sameRegionAndDifferentEntityIdsAreRejectedBeforePause() {
        assertThatThrownBy(() -> coordinator.transfer(
                1, sourcePipeline, sourcePipeline, sourceSnapshot, sourceSnapshot,
                sourceDelta, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regions must differ");
        PersistedEntityState wrongId = new PersistedEntityState(
                new NetworkEntityId(99), targetEntity.kind(), targetEntity.typeId(),
                targetEntity.pos(), targetEntity.vel(), targetEntity.ageTicks(),
                targetEntity.despawnTick(), targetEntity.payload());
        assertThatThrownBy(() -> coordinator.transfer(
                2, sourcePipeline, targetPipeline, sourceSnapshot, targetSnapshot,
                sourceDelta(wrongId), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remove the transferred entity");
        assertThat(sourcePipeline.state()).isEqualTo(PipelineState.ACTIVE);
    }

    private EntityTransferCoordinator.TransferResult transfer(EntityTransferCoordinator target) {
        return (EntityTransferCoordinator.TransferResult) target.transfer(
                77, sourcePipeline, targetPipeline, sourceSnapshot, targetSnapshot, sourceDelta, 0);
    }

    private EntityTransferCoordinator.TransferJournal journal() {
        return new JournalAdapter() {
            @Override public void prepared(EntityTransferCoordinator.TransferPlan plan) {
                journalOrder.add("PREPARED");
            }

            @Override public void accepted(
                    EntityTransferCoordinator.TransferPlan plan,
                    dev.nodera.core.consensuscert.EntityTransferCertificate certificate) {
                journalOrder.add("ACCEPTED");
            }

            @Override public void applied(
                    EntityTransferCoordinator.TransferPlan plan,
                    dev.nodera.core.consensuscert.EntityTransferCertificate certificate) {
                journalOrder.add("APPLIED");
            }

            @Override public void committed(
                    EntityTransferCoordinator.TransferPlan plan,
                    dev.nodera.core.consensuscert.EntityTransferCertificate certificate) {
                journalOrder.add("COMMITTED");
            }
        };
    }

    private static EntityTransferCoordinator.TransferPlan planOf(
            EntityTransferCoordinator.TransferResult result) {
        return new EntityTransferCoordinator.TransferPlan(
                result.certificate().descriptor(), result.sourceDelta(), result.targetDelta(),
                result.sourcePrepared(), result.targetPrepared(), result.sourceAccepted(),
                result.targetAccepted(), result.sourceCommitted(), result.targetCommitted());
    }

    private static RegionPipeline active(RegionId region) {
        RegionPipeline pipeline = new RegionPipeline(region);
        pipeline.assign(new RegionEpoch(1));
        pipeline.snapshotSynced();
        return pipeline;
    }

    private static PersistedEntityState item(FixedVec3 position, int count) {
        return new PersistedEntityState(
                new NetworkEntityId(7), EntityKind.ITEM, 42,
                position, FixedVec3.ZERO, 3, 6_000,
                ItemEntityRules.payload(42, count));
    }

    private RegionDelta sourceDelta(PersistedEntityState target) {
        RegionSnapshot after = new RegionSnapshot(
                sourceRegion, sourceSnapshot.version().next(), 0,
                sourceSnapshot.chunks(), List.of());
        return new RegionDelta(
                sourceRegion, sourceSnapshot.version(), sourceSnapshot.version().next(), List.of(),
                StateRoot.of(new HashService().hash(after)),
                List.of(new EntityMutation(sourceEntity.id(), sourceEntity, null)), List.of(),
                List.of(new EntityTransferIntent(targetRegion, target)));
    }

    private static List<NodeIdentity> members() {
        return List.of(NodeIdentity.generate(), NodeIdentity.generate(), NodeIdentity.generate());
    }

    private abstract static class JournalAdapter
            implements EntityTransferCoordinator.TransferJournal {
        @Override public void prepared(EntityTransferCoordinator.TransferPlan plan) {}
        @Override public void accepted(
                EntityTransferCoordinator.TransferPlan plan,
                dev.nodera.core.consensuscert.EntityTransferCertificate certificate) {}
        @Override public void applied(
                EntityTransferCoordinator.TransferPlan plan,
                dev.nodera.core.consensuscert.EntityTransferCertificate certificate) {}
        @Override public void committed(
                EntityTransferCoordinator.TransferPlan plan,
                dev.nodera.core.consensuscert.EntityTransferCertificate certificate) {}
        @Override public void aborted(long transferId, String reason) {}
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    private @interface Invariant {
        int value();
    }
}
