package dev.nodera.storage;

import dev.nodera.core.state.EntityTransferRecord;

import java.util.List;
import java.util.Optional;

/** Durable monotonic stage store for Task-12 cross-region transfers. */
public interface TransferStore {

    /** Persist an idempotent same-stage record or advance one transfer to its next durable stage. */
    void put(EntityTransferRecord record);

    /** Return one transfer record by id. */
    Optional<EntityTransferRecord> get(long transferId);

    /** Return all non-terminal records in transfer-id order for startup recovery. */
    List<EntityTransferRecord> recoverable();

    /** Return every record in transfer-id order. */
    List<EntityTransferRecord> all();

    /** Shared monotonicity check used by memory and durable implementations. */
    static void checkAdvance(EntityTransferRecord current, EntityTransferRecord next) {
        if (next == null) {
            throw new IllegalArgumentException("transfer record must not be null");
        }
        if (current == null) {
            return;
        }
        if (current.transferId() != next.transferId()
                || !current.descriptor().equals(next.descriptor())
                || !current.sourceDelta().equals(next.sourceDelta())
                || !current.targetDelta().equals(next.targetDelta())) {
            throw new IllegalArgumentException("transfer id reused with different plan");
        }
        if (current.stage() == next.stage()) {
            if (!current.equals(next)) {
                throw new IllegalArgumentException("same transfer stage changed durable payload");
            }
            return;
        }
        if (current.stage() == EntityTransferRecord.Stage.COMMITTED
                || current.stage() == EntityTransferRecord.Stage.ABORTED) {
            throw new IllegalStateException("terminal transfer cannot advance");
        }
        if (next.stage() == EntityTransferRecord.Stage.ABORTED) {
            if (current.stage() == EntityTransferRecord.Stage.APPLIED) {
                throw new IllegalStateException("applied transfer cannot abort");
            }
            return;
        }
        if (next.stage().ordinal() != current.stage().ordinal() + 1) {
            throw new IllegalStateException(
                    "transfer stage must advance exactly once: "
                            + current.stage() + " -> " + next.stage());
        }
    }
}
