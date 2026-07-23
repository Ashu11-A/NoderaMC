package dev.nodera.coordinator.entity;

import dev.nodera.core.consensuscert.EntityTransferCertificate;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.event.EntityTransferAcceptedEvent;
import dev.nodera.core.event.EntityTransferCommittedEvent;
import dev.nodera.core.event.EntityTransferPreparedEvent;
import dev.nodera.core.region.RegionBounds;
import dev.nodera.core.state.EntityMutation;
import dev.nodera.core.state.EntityTransferDescriptor;
import dev.nodera.core.state.EntityTransferIntent;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.coordinator.PipelineState;
import dev.nodera.coordinator.RegionPipeline;
import dev.nodera.coordinator.WorldMutationApplier;
import dev.nodera.shadow.SnapshotDeltaApplier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-main-thread Task-12c transfer state machine. Both pipelines pause at committed boundaries,
 * PREPARED and dual-committee ACCEPTED stages cross durable storage before one paired CAS, then
 * COMMITTED advances both versions. Recovery may resume after every durable stage.
 *
 * @Thread-context server main thread only; not thread-safe.
 */
public final class EntityTransferCoordinator {

    private static final HashService HASHES = new HashService();

    /** Closed outcome hierarchy for one transfer attempt. */
    public sealed interface TransferOutcome permits TransferResult, TransferFailure {
    }

    /** Produces and verifies independent source/target committee proofs. */
    public interface TransferApprovalProvider {
        EntityTransferCertificate approve(
                EntityTransferDescriptor descriptor,
                RegionDelta sourceDelta,
                RegionDelta targetDelta);

        boolean verify(EntityTransferCertificate certificate);
    }

    /** Durable stage boundary. Implementations must make each method idempotent by transfer id. */
    public interface TransferJournal {
        void prepared(TransferPlan plan);

        void accepted(TransferPlan plan, EntityTransferCertificate certificate);

        void applied(TransferPlan plan, EntityTransferCertificate certificate);

        void committed(TransferPlan plan, EntityTransferCertificate certificate);

        void aborted(long transferId, String reason);

        TransferJournal NOOP = new TransferJournal() {
            @Override public void prepared(TransferPlan plan) {}
            @Override public void accepted(TransferPlan plan, EntityTransferCertificate certificate) {}
            @Override public void applied(TransferPlan plan, EntityTransferCertificate certificate) {}
            @Override public void committed(TransferPlan plan, EntityTransferCertificate certificate) {}
            @Override public void aborted(long transferId, String reason) {}
        };
    }

    /** Immutable plan persisted before either committee emits its transfer acceptance. */
    public record TransferPlan(
            EntityTransferDescriptor descriptor,
            RegionDelta sourceDelta,
            RegionDelta targetDelta,
            EntityTransferPreparedEvent sourcePrepared,
            EntityTransferPreparedEvent targetPrepared,
            EntityTransferAcceptedEvent sourceAccepted,
            EntityTransferAcceptedEvent targetAccepted,
            EntityTransferCommittedEvent sourceCommitted,
            EntityTransferCommittedEvent targetCommitted) {
        public TransferPlan {
            if (descriptor == null || sourceDelta == null || targetDelta == null
                    || sourcePrepared == null || targetPrepared == null
                    || sourceAccepted == null || targetAccepted == null
                    || sourceCommitted == null || targetCommitted == null) {
                throw new IllegalArgumentException("transfer plan values must not be null");
            }
        }
    }

    /** Durable stage restored after process restart. */
    public enum TransferStage {
        PREPARED,
        ACCEPTED,
        APPLIED,
        COMMITTED,
        ABORTED
    }

    /** Startup recovery input reconstructed by a durable journal. */
    public record TransferRecovery(
            TransferPlan plan, EntityTransferCertificate certificate, TransferStage stage) {
        public TransferRecovery {
            if (plan == null || stage == null) {
                throw new IllegalArgumentException("recovery plan and stage must not be null");
            }
            if (stage.ordinal() >= TransferStage.ACCEPTED.ordinal() && certificate == null
                    && stage != TransferStage.ABORTED) {
                throw new IllegalArgumentException("accepted/applied/committed recovery requires certificate");
            }
        }
    }

    /** Successful transfer artifacts consumed by event storage and transport. */
    public record TransferResult(
            long transferId,
            boolean replay,
            RegionDelta sourceDelta,
            RegionDelta targetDelta,
            EntityTransferCertificate certificate,
            EntityTransferPreparedEvent sourcePrepared,
            EntityTransferPreparedEvent targetPrepared,
            EntityTransferAcceptedEvent sourceAccepted,
            EntityTransferAcceptedEvent targetAccepted,
            EntityTransferCommittedEvent sourceCommitted,
            EntityTransferCommittedEvent targetCommitted,
            WorldMutationApplier.ApplyResult applyResult,
            long tick
    ) implements TransferOutcome {
        TransferResult asReplay() {
            return new TransferResult(
                    transferId, true, sourceDelta, targetDelta, certificate,
                    sourcePrepared, targetPrepared, sourceAccepted, targetAccepted,
                    sourceCommitted, targetCommitted,
                    WorldMutationApplier.ApplyResult.committedReplay(), tick);
        }
    }

    /** Failed CAS outcome; both pipelines resume without a version advance. */
    public record TransferFailure(long transferId, WorldMutationApplier.ApplyResult applyResult)
            implements TransferOutcome {
    }

    private final WorldMutationApplier applier;
    private final TransferApprovalProvider approvals;
    private final TransferJournal journal;
    private final Map<Long, TransferResult> completed = new HashMap<>();
    private final Map<Long, PendingTransfer> pending = new HashMap<>();

    public EntityTransferCoordinator(
            WorldMutationApplier applier,
            TransferApprovalProvider approvals,
            TransferJournal journal) {
        if (applier == null || approvals == null || journal == null) {
            throw new IllegalArgumentException("applier, approvals, and journal must not be null");
        }
        this.applier = applier;
        this.approvals = approvals;
        this.journal = journal;
    }

    /** Prepare and atomically commit one delegated-to-delegated transfer. */
    public TransferOutcome transfer(
            long transferId,
            RegionPipeline source,
            RegionPipeline target,
            RegionSnapshot sourceSnapshot,
            RegionSnapshot targetSnapshot,
            RegionDelta certifiedSourceDelta,
            long transferTick) {
        TransferStates states = validate(
                source, target, sourceSnapshot, targetSnapshot, certifiedSourceDelta, transferTick);
        StateRoot sourceTransitionRoot = StateRoot.of(HASHES.hash(certifiedSourceDelta));
        TransferKey requestKey = new TransferKey(
                source.region(), target.region(), sourceSnapshot.version(), targetSnapshot.version(),
                states.sourceState(), states.targetState(), sourceTransitionRoot);
        TransferResult prior = completed.get(transferId);
        if (prior != null) {
            requireSameTransfer(requestKey, keyOf(prior), transferId);
            return prior.asReplay();
        }
        PendingTransfer retry = pending.get(transferId);
        if (retry != null) {
            requireSameTransfer(requestKey, retry.key, transferId);
            ensurePaused(source, target);
            return finish(retry, source, target);
        }

        pause(source, target);
        SnapshotVersion sourceBase = source.lastCommittedVersion();
        SnapshotVersion targetBase = target.lastCommittedVersion();
        if (!sourceBase.equals(sourceSnapshot.version()) || !targetBase.equals(targetSnapshot.version())) {
            abortPipelines(source, target);
            throw new IllegalStateException("transfer snapshots must match committed pipeline versions");
        }
        if (!applier.matchesSnapshot(sourceSnapshot) || !applier.matchesSnapshot(targetSnapshot)) {
            abortPipelines(source, target);
            throw new IllegalStateException("transfer snapshots do not match live committed state");
        }

        SnapshotVersion sourceNext = sourceBase.next();
        SnapshotVersion targetNext = targetBase.next();
        StateRoot sourcePrevRoot = StateRoot.of(HASHES.hash(sourceSnapshot));
        StateRoot targetPrevRoot = StateRoot.of(HASHES.hash(targetSnapshot));
        StateRoot targetResultingRoot = rootAfter(
                targetSnapshot, null, states.targetState(), targetNext, transferTick);
        RegionDelta targetDelta = new RegionDelta(
                target.region(), targetBase, targetNext, List.of(), targetResultingRoot,
                List.of(new EntityMutation(
                        states.targetState().id(), null, states.targetState())), List.of());
        StateRoot targetTransitionRoot = StateRoot.of(HASHES.hash(targetDelta));
        EntityTransferDescriptor descriptor = new EntityTransferDescriptor(
                transferId, source.region(), target.region(), source.epoch(), target.epoch(),
                states.sourceState().id(), sourceBase, sourceNext, sourcePrevRoot,
                certifiedSourceDelta.resultingRoot(), sourceTransitionRoot,
                targetBase, targetNext, targetPrevRoot, targetResultingRoot,
                targetTransitionRoot, transferTick);
        TransferPlan plan = plan(descriptor, certifiedSourceDelta, targetDelta,
                states.sourceState(), states.targetState());
        PendingTransfer pendingTransfer = new PendingTransfer(requestKey, plan, false);
        pending.put(transferId, pendingTransfer);
        try {
            journal.prepared(plan);
        } catch (RuntimeException failure) {
            pending.remove(transferId);
            abortPipelines(source, target);
            throw failure;
        }
        return finish(pendingTransfer, source, target);
    }

    /** Resume one PREPARED, ACCEPTED, or APPLIED transfer after journal replay. */
    public TransferOutcome restorePending(
            TransferRecovery recovery, RegionPipeline source, RegionPipeline target) {
        if (recovery.stage() == TransferStage.COMMITTED
                || recovery.stage() == TransferStage.ABORTED) {
            throw new IllegalArgumentException("terminal transfer is not pending");
        }
        TransferPlan plan = recovery.plan();
        validatePlan(plan);
        if (!source.region().equals(plan.descriptor().sourceRegion())
                || !target.region().equals(plan.descriptor().targetRegion())) {
            throw new IllegalArgumentException("recovery pipelines do not match transfer plan");
        }
        PendingTransfer restored = new PendingTransfer(keyOf(plan), plan, true);
        restored.certificate = recovery.certificate();
        restored.stage = recovery.stage();
        PendingTransfer prior = pending.putIfAbsent(
                plan.descriptor().transferId(), restored);
        if (prior != null) {
            requireSameTransfer(restored.key, prior.key, plan.descriptor().transferId());
            restored = prior;
        }
        ensurePaused(source, target);
        return finish(restored, source, target);
    }

    /** Recover a previously committed transfer id from durable journal replay. */
    public void restoreCompleted(TransferResult result) {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        validateResult(result);
        TransferResult prior = completed.get(result.transferId());
        if (prior != null) {
            requireSameTransfer(keyOf(result), keyOf(prior), result.transferId());
            return;
        }
        // Terminal history may precede newer entity updates. Restore only the replay/id-conflict
        // cache; canonical startup replay owns reconstruction of current world state.
        completed.put(result.transferId(), result);
    }

    private TransferOutcome finish(
            PendingTransfer pendingTransfer, RegionPipeline source, RegionPipeline target) {
        ensurePaused(source, target);
        TransferPlan plan = pendingTransfer.plan;
        if (pendingTransfer.stage == TransferStage.PREPARED) {
            EntityTransferCertificate certificate = pendingTransfer.certificate;
            if (certificate == null) {
                certificate = approvals.approve(
                        plan.descriptor(), plan.sourceDelta(), plan.targetDelta());
                pendingTransfer.certificate = certificate;
            }
            validateCertificate(plan, certificate);
            journal.accepted(plan, certificate);
            pendingTransfer.stage = TransferStage.ACCEPTED;
        }

        if (pendingTransfer.stage == TransferStage.ACCEPTED) {
            WorldMutationApplier.ApplyResult applied = pendingTransfer.recovery
                    ? applier.recoverTransfer(List.of(plan.sourceDelta(), plan.targetDelta()))
                    : applier.applyTransfer(List.of(plan.sourceDelta(), plan.targetDelta()));
            if (!applied.committed()) {
                pendingTransfer.failure = applied;
                return abort(pendingTransfer, source, target);
            }
            pendingTransfer.applied = applied;
            pendingTransfer.recovery = true;
            verifyCertifiedRoots(plan);
            journal.applied(plan, pendingTransfer.certificate);
            pendingTransfer.stage = TransferStage.APPLIED;
        }

        verifyCertifiedRoots(plan);
        journal.committed(plan, pendingTransfer.certificate);
        source.crossRegionCommitted(plan.sourceDelta().resultingVersion());
        target.crossRegionCommitted(plan.targetDelta().resultingVersion());
        TransferResult result = result(pendingTransfer);
        pending.remove(result.transferId());
        completed.put(result.transferId(), result);
        pendingTransfer.stage = TransferStage.COMMITTED;
        return result;
    }

    private TransferFailure abort(
            PendingTransfer pendingTransfer, RegionPipeline source, RegionPipeline target) {
        WorldMutationApplier.ApplyResult failure = pendingTransfer.failure;
        journal.aborted(pendingTransfer.plan.descriptor().transferId(), failure.failure());
        pending.remove(pendingTransfer.plan.descriptor().transferId());
        abortPipelines(source, target);
        pendingTransfer.stage = TransferStage.ABORTED;
        return new TransferFailure(pendingTransfer.plan.descriptor().transferId(), failure);
    }

    private void validateCertificate(
            TransferPlan plan, EntityTransferCertificate certificate) {
        if (!certificate.descriptor().equals(plan.descriptor()) || !approvals.verify(certificate)) {
            throw new IllegalArgumentException("joint transfer certificate is invalid");
        }
    }

    private void verifyCertifiedRoots(TransferPlan plan) {
        EntityTransferDescriptor descriptor = plan.descriptor();
        if (!applier.currentRoot(
                descriptor.sourceRegion(), descriptor.sourceResultingVersion(), descriptor.tick())
                .equals(descriptor.sourceResultingRoot())
                || !applier.currentRoot(
                descriptor.targetRegion(), descriptor.targetResultingVersion(), descriptor.tick())
                .equals(descriptor.targetResultingRoot())) {
            throw new IllegalStateException("atomic transfer did not reproduce certified roots");
        }
    }

    private void validateResult(TransferResult result) {
        TransferPlan plan = planOf(result);
        validatePlan(plan);
        validateCertificate(plan, result.certificate());
    }

    private static void validatePlan(TransferPlan plan) {
        EntityTransferDescriptor descriptor = plan.descriptor();
        if (!descriptor.sourceRegion().equals(plan.sourceDelta().region())
                || !descriptor.targetRegion().equals(plan.targetDelta().region())
                || !descriptor.sourceBaseVersion().equals(plan.sourceDelta().baseVersion())
                || !descriptor.sourceResultingVersion().equals(plan.sourceDelta().resultingVersion())
                || !descriptor.targetBaseVersion().equals(plan.targetDelta().baseVersion())
                || !descriptor.targetResultingVersion().equals(plan.targetDelta().resultingVersion())
                || !descriptor.sourceResultingRoot().equals(plan.sourceDelta().resultingRoot())
                || !descriptor.targetResultingRoot().equals(plan.targetDelta().resultingRoot())
                || !descriptor.sourceTransitionRoot().equals(
                StateRoot.of(HASHES.hash(plan.sourceDelta())))
                || !descriptor.targetTransitionRoot().equals(
                StateRoot.of(HASHES.hash(plan.targetDelta())))) {
            throw new IllegalArgumentException("transfer plan does not match descriptor");
        }
    }

    private static TransferStates validate(
            RegionPipeline source,
            RegionPipeline target,
            RegionSnapshot sourceSnapshot,
            RegionSnapshot targetSnapshot,
            RegionDelta sourceDelta,
            long transferTick) {
        if (source == null || target == null || sourceSnapshot == null || targetSnapshot == null
                || sourceDelta == null) {
            throw new IllegalArgumentException("transfer arguments must not be null");
        }
        if (source.region().equals(target.region())) {
            throw new IllegalArgumentException("source and target regions must differ");
        }
        if (!sourceSnapshot.region().equals(source.region())
                || !targetSnapshot.region().equals(target.region())) {
            throw new IllegalArgumentException("transfer snapshots must match pipeline regions");
        }
        if (targetSnapshot.tick() != transferTick) {
            throw new IllegalArgumentException("target snapshot must be caught up to transfer tick");
        }
        if (!sourceDelta.region().equals(source.region())
                || !sourceDelta.baseVersion().equals(sourceSnapshot.version())
                || !sourceDelta.resultingVersion().equals(sourceSnapshot.version().next())
                || sourceDelta.transferIntents().size() != 1) {
            throw new IllegalArgumentException(
                    "source delta must be the next certified transition with one transfer intent");
        }
        EntityTransferIntent intent = sourceDelta.transferIntents().getFirst();
        EntityMutation removal = sourceDelta.entityMutations().stream()
                .filter(mutation -> mutation.id().equals(intent.entityId())
                        && mutation.expectedPrevious() != null && mutation.newState() == null)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "source delta must remove the transferred entity"));
        PersistedEntityState sourceState = removal.expectedPrevious();
        PersistedEntityState targetState = intent.targetState();
        if (!intent.targetRegion().equals(target.region())) {
            throw new IllegalArgumentException("transfer intent target does not match target pipeline");
        }
        SnapshotDeltaApplier.apply(sourceSnapshot, sourceDelta, transferTick);
        if (!sourceSnapshot.entities().contains(sourceState)) {
            throw new IllegalArgumentException("source snapshot does not contain source entity");
        }
        if (targetSnapshot.entities().stream().anyMatch(e -> e.id().equals(targetState.id()))) {
            throw new IllegalArgumentException("target snapshot already contains entity id");
        }
        if (!RegionBounds.of(source.region()).ownsBlock(
                sourceState.pos().blockX(), sourceState.pos().blockZ())) {
            throw new IllegalArgumentException("source entity is outside source region");
        }
        if (!RegionBounds.of(target.region()).ownsBlock(
                targetState.pos().blockX(), targetState.pos().blockZ())) {
            throw new IllegalArgumentException("target entity is outside target region");
        }
        if (sourceState.kind() != targetState.kind()
                || sourceState.typeId() != targetState.typeId()
                || sourceState.despawnTick() != targetState.despawnTick()
                || !sourceState.payload().equals(targetState.payload())) {
            throw new IllegalArgumentException("transfer changed immutable entity identity or payload");
        }
        return new TransferStates(sourceState, targetState);
    }

    private static TransferPlan plan(
            EntityTransferDescriptor descriptor,
            RegionDelta sourceDelta,
            RegionDelta targetDelta,
            PersistedEntityState sourceState,
            PersistedEntityState targetState) {
        return new TransferPlan(
                descriptor, sourceDelta, targetDelta,
                new EntityTransferPreparedEvent(
                        descriptor.transferId(), descriptor.targetRegion(), sourceState),
                new EntityTransferPreparedEvent(
                        descriptor.transferId(), descriptor.sourceRegion(), targetState),
                new EntityTransferAcceptedEvent(
                        descriptor.transferId(), descriptor.targetRegion(), descriptor.entityId()),
                new EntityTransferAcceptedEvent(
                        descriptor.transferId(), descriptor.sourceRegion(), descriptor.entityId()),
                new EntityTransferCommittedEvent(
                        descriptor.transferId(), descriptor.targetRegion(), descriptor.entityId()),
                new EntityTransferCommittedEvent(
                        descriptor.transferId(), descriptor.sourceRegion(), descriptor.entityId()));
    }

    private static StateRoot rootAfter(
            RegionSnapshot base,
            PersistedEntityState expected,
            PersistedEntityState replacement,
            SnapshotVersion version,
            long tick) {
        List<PersistedEntityState> entities = new ArrayList<>(base.entities());
        if (expected != null && !entities.remove(expected)) {
            throw new IllegalArgumentException("expected entity missing from source snapshot");
        }
        if (replacement != null) {
            entities.add(replacement);
        }
        return StateRoot.of(HASHES.hash(new RegionSnapshot(
                base.region(), version, tick, base.chunks(), entities)));
    }

    private static void pause(RegionPipeline source, RegionPipeline target) {
        source.pauseForCrossRegion();
        try {
            target.pauseForCrossRegion();
        } catch (RuntimeException failure) {
            source.crossRegionAborted();
            throw failure;
        }
    }

    private static void ensurePaused(RegionPipeline source, RegionPipeline target) {
        if (source.state() == PipelineState.ACTIVE && target.state() == PipelineState.ACTIVE) {
            pause(source, target);
        }
        if (source.state() != PipelineState.PAUSED_FOR_XR
                || target.state() != PipelineState.PAUSED_FOR_XR) {
            throw new IllegalStateException("pending transfer pipelines must remain paused");
        }
    }

    private static void abortPipelines(RegionPipeline source, RegionPipeline target) {
        if (source.state() == PipelineState.PAUSED_FOR_XR) {
            source.crossRegionAborted();
        }
        if (target.state() == PipelineState.PAUSED_FOR_XR) {
            target.crossRegionAborted();
        }
    }

    private static TransferResult result(PendingTransfer pendingTransfer) {
        TransferPlan plan = pendingTransfer.plan;
        return new TransferResult(
                plan.descriptor().transferId(), false, plan.sourceDelta(), plan.targetDelta(),
                pendingTransfer.certificate, plan.sourcePrepared(), plan.targetPrepared(),
                plan.sourceAccepted(), plan.targetAccepted(), plan.sourceCommitted(),
                plan.targetCommitted(), pendingTransfer.applied, plan.descriptor().tick());
    }

    private static TransferPlan planOf(TransferResult result) {
        return new TransferPlan(
                result.certificate().descriptor(), result.sourceDelta(), result.targetDelta(),
                result.sourcePrepared(), result.targetPrepared(), result.sourceAccepted(),
                result.targetAccepted(), result.sourceCommitted(), result.targetCommitted());
    }

    private static TransferKey keyOf(TransferResult result) {
        return keyOf(planOf(result));
    }

    private static TransferKey keyOf(TransferPlan plan) {
        EntityMutation sourceMutation = plan.sourceDelta().entityMutations().stream()
                .filter(mutation -> mutation.id().equals(plan.descriptor().entityId())
                        && mutation.expectedPrevious() != null && mutation.newState() == null)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "transfer source delta does not remove certified entity"));
        EntityMutation targetMutation = plan.targetDelta().entityMutations().stream()
                .filter(mutation -> mutation.id().equals(plan.descriptor().entityId())
                        && mutation.expectedPrevious() == null && mutation.newState() != null)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "transfer target delta does not create certified entity"));
        return new TransferKey(
                plan.descriptor().sourceRegion(), plan.descriptor().targetRegion(),
                plan.descriptor().sourceBaseVersion(), plan.descriptor().targetBaseVersion(),
                sourceMutation.expectedPrevious(), targetMutation.newState(),
                plan.descriptor().sourceTransitionRoot());
    }

    private static void requireSameTransfer(
            TransferKey requested, TransferKey stored, long transferId) {
        if (!requested.equals(stored)) {
            throw new IllegalArgumentException(
                    "transfer id reused with different request: " + transferId);
        }
    }

    private record TransferKey(
            dev.nodera.core.region.RegionId sourceRegion,
            dev.nodera.core.region.RegionId targetRegion,
            SnapshotVersion sourceBaseVersion,
            SnapshotVersion targetBaseVersion,
            PersistedEntityState sourceState,
            PersistedEntityState targetState,
            StateRoot sourceTransitionRoot) {
    }

    private record TransferStates(
            PersistedEntityState sourceState, PersistedEntityState targetState) {
    }

    private static final class PendingTransfer {
        private final TransferKey key;
        private final TransferPlan plan;
        private boolean recovery;
        private EntityTransferCertificate certificate;
        private WorldMutationApplier.ApplyResult applied;
        private WorldMutationApplier.ApplyResult failure;
        private TransferStage stage = TransferStage.PREPARED;

        private PendingTransfer(TransferKey key, TransferPlan plan, boolean recovery) {
            this.key = key;
            this.plan = plan;
            this.recovery = recovery;
        }
    }
}
