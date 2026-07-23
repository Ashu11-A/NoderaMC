package dev.nodera.peer.validation;

import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.EntityTransferCertificate;
import dev.nodera.core.event.CommittedEventEnvelope;
import dev.nodera.core.event.EntityTransferAcceptedEvent;
import dev.nodera.core.event.EntityTransferCommittedEvent;
import dev.nodera.core.event.EntityTransferPreparedEvent;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityMutation;
import dev.nodera.core.state.EntityTransferRecord;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.coordinator.WorldMutationApplier;
import dev.nodera.coordinator.entity.EntityTransferCoordinator;
import dev.nodera.storage.ContentId;
import dev.nodera.storage.RegionEventStore;
import dev.nodera.storage.WorldStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Task-12 transfer journal backed by a {@link WorldStore}. Stage records survive process loss;
 * terminal commit stores one joint certificate and atomically appends Prepared/Accepted/Committed
 * histories to both region logs.
 */
public final class WorldStoreTransferJournal
        implements EntityTransferCoordinator.TransferJournal {

    private final WorldStore store;

    public WorldStoreTransferJournal(WorldStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        this.store = store;
    }

    @Override
    public synchronized void prepared(EntityTransferCoordinator.TransferPlan plan) {
        put(EntityTransferRecord.Stage.PREPARED, plan, null, "");
    }

    @Override
    public synchronized void accepted(
            EntityTransferCoordinator.TransferPlan plan,
            EntityTransferCertificate certificate) {
        put(EntityTransferRecord.Stage.ACCEPTED, plan, certificate, "");
    }

    @Override
    public synchronized void applied(
            EntityTransferCoordinator.TransferPlan plan,
            EntityTransferCertificate certificate) {
        put(EntityTransferRecord.Stage.APPLIED, plan, certificate, "");
    }

    @Override
    public synchronized void committed(
            EntityTransferCoordinator.TransferPlan plan,
            EntityTransferCertificate certificate) {
        ContentId certificateId = store.certificates().put(certificate);
        if (!terminalEventsPresent(plan, certificateId.hash())) {
            store.events().appendAtomic(events(plan, certificateId.hash()));
        }
        put(EntityTransferRecord.Stage.COMMITTED, plan, certificate, "");
    }

    @Override
    public synchronized void aborted(long transferId, String reason) {
        EntityTransferRecord current = store.transfers().get(transferId)
                .orElseThrow(() -> new IllegalStateException(
                        "cannot abort unknown transfer " + transferId));
        store.transfers().put(new EntityTransferRecord(
                EntityTransferRecord.Stage.ABORTED,
                current.descriptor(), current.sourceDelta(), current.targetDelta(),
                null, reason == null || reason.isBlank() ? "UNKNOWN" : reason));
    }

    /** Records that must resume before their region pipelines accept new work. */
    public synchronized List<EntityTransferCoordinator.TransferRecovery> recoverable() {
        return store.transfers().recoverable().stream().map(record ->
                new EntityTransferCoordinator.TransferRecovery(
                        plan(record), record.certificate(), stage(record.stage())))
                .toList();
    }

    /** Terminal records restored into the coordinator's replay cache on startup. */
    public synchronized List<EntityTransferCoordinator.TransferResult> completed() {
        return store.transfers().all().stream()
                .filter(record -> record.stage() == EntityTransferRecord.Stage.COMMITTED)
                .map(this::completedResult)
                .toList();
    }

    private void put(
            EntityTransferRecord.Stage stage,
            EntityTransferCoordinator.TransferPlan plan,
            EntityTransferCertificate certificate,
            String failure) {
        store.transfers().put(new EntityTransferRecord(
                stage, plan.descriptor(), plan.sourceDelta(), plan.targetDelta(),
                certificate, failure));
    }

    private EntityTransferCoordinator.TransferResult completedResult(EntityTransferRecord record) {
        EntityTransferCoordinator.TransferPlan plan = plan(record);
        return new EntityTransferCoordinator.TransferResult(
                record.transferId(), true, record.sourceDelta(), record.targetDelta(),
                record.certificate(), plan.sourcePrepared(), plan.targetPrepared(),
                plan.sourceAccepted(), plan.targetAccepted(), plan.sourceCommitted(),
                plan.targetCommitted(), WorldMutationApplier.ApplyResult.committedReplay(),
                record.descriptor().tick());
    }

    private static EntityTransferCoordinator.TransferPlan plan(EntityTransferRecord record) {
        EntityMutation sourceMutation = record.sourceDelta().entityMutations().stream()
                .filter(mutation -> mutation.id().equals(record.descriptor().entityId())
                        && mutation.expectedPrevious() != null && mutation.newState() == null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "durable transfer source removal is missing"));
        EntityMutation targetMutation = record.targetDelta().entityMutations().stream()
                .filter(mutation -> mutation.id().equals(record.descriptor().entityId())
                        && mutation.expectedPrevious() == null && mutation.newState() != null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "durable transfer target creation is missing"));
        PersistedEntityState sourceState = sourceMutation.expectedPrevious();
        PersistedEntityState targetState = targetMutation.newState();
        long transferId = record.transferId();
        RegionId source = record.descriptor().sourceRegion();
        RegionId target = record.descriptor().targetRegion();
        return new EntityTransferCoordinator.TransferPlan(
                record.descriptor(), record.sourceDelta(), record.targetDelta(),
                new EntityTransferPreparedEvent(transferId, target, sourceState),
                new EntityTransferPreparedEvent(transferId, source, targetState),
                new EntityTransferAcceptedEvent(transferId, target, sourceState.id()),
                new EntityTransferAcceptedEvent(transferId, source, sourceState.id()),
                new EntityTransferCommittedEvent(transferId, target, sourceState.id()),
                new EntityTransferCommittedEvent(transferId, source, sourceState.id()));
    }

    private List<CommittedEventEnvelope> events(
            EntityTransferCoordinator.TransferPlan plan, Bytes certificateRef) {
        var descriptor = plan.descriptor();
        long sourceId = store.events().lastEventId(descriptor.sourceRegion()) + 1;
        long targetId = store.events().lastEventId(descriptor.targetRegion()) + 1;
        List<CommittedEventEnvelope> events = new ArrayList<>(6);
        events.add(new CommittedEventEnvelope(
                descriptor.sourceRegion(), descriptor.sourceEpoch(), descriptor.sourceBaseVersion(),
                descriptor.tick(), sourceId, plan.sourcePrepared(), descriptor.sourcePrevRoot(),
                descriptor.sourcePrevRoot(), certificateRef));
        events.add(new CommittedEventEnvelope(
                descriptor.sourceRegion(), descriptor.sourceEpoch(), descriptor.sourceBaseVersion(),
                descriptor.tick(), sourceId + 1, plan.sourceAccepted(), descriptor.sourcePrevRoot(),
                descriptor.sourcePrevRoot(), certificateRef));
        events.add(new CommittedEventEnvelope(
                descriptor.sourceRegion(), descriptor.sourceEpoch(),
                descriptor.sourceResultingVersion(), descriptor.tick(), sourceId + 2,
                plan.sourceCommitted(), descriptor.sourcePrevRoot(),
                descriptor.sourceResultingRoot(), certificateRef));
        events.add(new CommittedEventEnvelope(
                descriptor.targetRegion(), descriptor.targetEpoch(), descriptor.targetBaseVersion(),
                descriptor.tick(), targetId, plan.targetPrepared(), descriptor.targetPrevRoot(),
                descriptor.targetPrevRoot(), certificateRef));
        events.add(new CommittedEventEnvelope(
                descriptor.targetRegion(), descriptor.targetEpoch(), descriptor.targetBaseVersion(),
                descriptor.tick(), targetId + 1, plan.targetAccepted(), descriptor.targetPrevRoot(),
                descriptor.targetPrevRoot(), certificateRef));
        events.add(new CommittedEventEnvelope(
                descriptor.targetRegion(), descriptor.targetEpoch(),
                descriptor.targetResultingVersion(), descriptor.tick(), targetId + 2,
                plan.targetCommitted(), descriptor.targetPrevRoot(),
                descriptor.targetResultingRoot(), certificateRef));
        return events;
    }

    private boolean terminalEventsPresent(
            EntityTransferCoordinator.TransferPlan plan, Bytes certificateRef) {
        return terminalEventsPresent(
                store.events(), plan.descriptor().sourceRegion(), plan.descriptor().transferId(),
                certificateRef)
                && terminalEventsPresent(
                store.events(), plan.descriptor().targetRegion(), plan.descriptor().transferId(),
                certificateRef);
    }

    private static boolean terminalEventsPresent(
            RegionEventStore events, RegionId region, long transferId, Bytes certificateRef) {
        long last = events.lastEventId(region);
        if (last < 2) {
            return false;
        }
        List<CommittedEventEnvelope> tail = events.readFrom(region, last - 2);
        return tail.size() == 3
                && tail.stream().allMatch(event -> event.certificateRef().equals(certificateRef))
                && tail.get(0).event() instanceof EntityTransferPreparedEvent prepared
                && prepared.transferId() == transferId
                && tail.get(1).event() instanceof EntityTransferAcceptedEvent accepted
                && accepted.transferId() == transferId
                && tail.get(2).event() instanceof EntityTransferCommittedEvent committed
                && committed.transferId() == transferId;
    }

    private static EntityTransferCoordinator.TransferStage stage(
            EntityTransferRecord.Stage stage) {
        return EntityTransferCoordinator.TransferStage.valueOf(stage.name());
    }
}
