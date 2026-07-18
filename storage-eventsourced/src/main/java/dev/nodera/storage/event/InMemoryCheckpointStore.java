package dev.nodera.storage.event;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.storage.Checkpoint;
import dev.nodera.storage.CheckpointStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * In-memory per-region checkpoint index (Task 9), ordered by version so {@link #latest} is O(1) at
 * the tail. A new checkpoint must have a strictly greater version than the current latest.
 *
 * @Thread-context confined to the owning thread; not thread-safe.
 */
public final class InMemoryCheckpointStore implements CheckpointStore {

    private static final Comparator<RegionId> REGION_ORDER =
            Comparator.comparing((RegionId r) -> r.dimension().toString())
                    .thenComparingInt(RegionId::regionX)
                    .thenComparingInt(RegionId::regionZ);

    private final Map<RegionId, TreeMap<Long, Checkpoint>> byRegion = new TreeMap<>(REGION_ORDER);

    @Override
    public void put(Checkpoint checkpoint) {
        if (checkpoint == null) {
            throw new IllegalArgumentException("checkpoint must not be null");
        }
        TreeMap<Long, Checkpoint> log = byRegion.computeIfAbsent(checkpoint.region(), k -> new TreeMap<>());
        if (!log.isEmpty() && checkpoint.version().value() <= log.lastKey()) {
            throw new IllegalStateException("checkpoint version " + checkpoint.version().value()
                    + " not greater than latest " + log.lastKey() + " for " + checkpoint.region());
        }
        log.put(checkpoint.version().value(), checkpoint);
    }

    @Override
    public Optional<Checkpoint> latest(RegionId region) {
        TreeMap<Long, Checkpoint> log = byRegion.get(region);
        return (log == null || log.isEmpty()) ? Optional.empty() : Optional.of(log.lastEntry().getValue());
    }

    @Override
    public Optional<Checkpoint> at(RegionId region, SnapshotVersion version) {
        TreeMap<Long, Checkpoint> log = byRegion.get(region);
        return log == null ? Optional.empty() : Optional.ofNullable(log.get(version.value()));
    }

    @Override
    public List<Checkpoint> all(RegionId region) {
        TreeMap<Long, Checkpoint> log = byRegion.get(region);
        return log == null ? List.of() : new ArrayList<>(log.values());
    }
}
