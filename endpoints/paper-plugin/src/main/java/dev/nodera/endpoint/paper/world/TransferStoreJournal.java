package dev.nodera.endpoint.paper.world;

import dev.nodera.core.consensuscert.EntityTransferCertificate;
import dev.nodera.core.state.EntityTransferRecord;
import dev.nodera.coordinator.entity.EntityTransferCoordinator;
import dev.nodera.storage.TransferStore;

import java.util.Optional;

/**
 * The endpoint's durable transfer journal: {@link EntityTransferCoordinator.TransferJournal} on top
 * of the {@link TransferStore} the storage tier already carries (server task 4, L-64).
 *
 * <p>L-64's exit clause asks for a cross-region commit "journalled with durable transfer stages".
 * Those stages exist — {@link EntityTransferRecord.Stage} and {@link TransferStore}, with the
 * monotonicity rule enforced once in {@link TransferStore#checkAdvance} and implemented by both the
 * in-memory and RocksDB tiers. What did not exist was anything on the plugin path that wrote them.
 * This is the adapter, and it is deliberately the whole of the mapping: no second journal format,
 * no second store.
 *
 * <p><b>An abort is reconstructed, not remembered.</b> The coordinator's {@code aborted} callback
 * carries only a transfer id and a reason, because by then the plan may have come back from a
 * restart rather than from this process. The descriptor and both deltas are therefore read back out
 * of the store — the same place a recovery would read them — so a crash between the abort decision
 * and the abort record cannot leave a stage this class alone knows about.
 *
 * @Thread-context not thread-safe; it belongs to the single thread that owns one transfer's state
 *                 machine (see {@link CrossRegionCommit}, which is that thread).
 */
public final class TransferStoreJournal implements EntityTransferCoordinator.TransferJournal {

    private final TransferStore store;

    public TransferStoreJournal(TransferStore store) {
        if (store == null) {
            throw new IllegalArgumentException("transfer store must not be null");
        }
        this.store = store;
    }

    /** @return the store this journal writes, for recovery and for assertions about stage counts. */
    public TransferStore store() {
        return store;
    }

    @Override
    public void prepared(EntityTransferCoordinator.TransferPlan plan) {
        store.put(record(EntityTransferRecord.Stage.PREPARED, plan, null, ""));
    }

    @Override
    public void accepted(
            EntityTransferCoordinator.TransferPlan plan, EntityTransferCertificate certificate) {
        store.put(record(EntityTransferRecord.Stage.ACCEPTED, plan, certificate, ""));
    }

    @Override
    public void applied(
            EntityTransferCoordinator.TransferPlan plan, EntityTransferCertificate certificate) {
        store.put(record(EntityTransferRecord.Stage.APPLIED, plan, certificate, ""));
    }

    @Override
    public void committed(
            EntityTransferCoordinator.TransferPlan plan, EntityTransferCertificate certificate) {
        store.put(record(EntityTransferRecord.Stage.COMMITTED, plan, certificate, ""));
    }

    @Override
    public void aborted(long transferId, String reason) {
        Optional<EntityTransferRecord> current = store.get(transferId);
        if (current.isEmpty()) {
            // Nothing durable was ever written under this id, so there is no stage to terminate.
            // Inventing one would make a refusal look like a failed attempt.
            return;
        }
        EntityTransferRecord open = current.get();
        store.put(new EntityTransferRecord(
                EntityTransferRecord.Stage.ABORTED, open.descriptor(), open.sourceDelta(),
                open.targetDelta(), null,
                reason == null || reason.isBlank() ? "cross-region commit aborted" : reason));
    }

    private static EntityTransferRecord record(
            EntityTransferRecord.Stage stage,
            EntityTransferCoordinator.TransferPlan plan,
            EntityTransferCertificate certificate,
            String failure) {
        return new EntityTransferRecord(
                stage, plan.descriptor(), plan.sourceDelta(), plan.targetDelta(),
                certificate, failure);
    }
}
