package dev.nodera.storage.event;

import dev.nodera.core.state.EntityTransferRecord;
import dev.nodera.storage.TransferStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** In-memory transfer store with the same monotonic contract as RocksDB. */
public final class InMemoryTransferStore implements TransferStore {

    private final Map<Long, EntityTransferRecord> records = new TreeMap<>();

    @Override
    public void put(EntityTransferRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("transfer record must not be null");
        }
        TransferStore.checkAdvance(records.get(record.transferId()), record);
        records.put(record.transferId(), record);
    }

    @Override
    public Optional<EntityTransferRecord> get(long transferId) {
        return Optional.ofNullable(records.get(transferId));
    }

    @Override
    public List<EntityTransferRecord> recoverable() {
        return records.values().stream()
                .filter(record -> record.stage() != EntityTransferRecord.Stage.COMMITTED
                        && record.stage() != EntityTransferRecord.Stage.ABORTED)
                .toList();
    }

    @Override
    public List<EntityTransferRecord> all() {
        return new ArrayList<>(records.values());
    }
}
