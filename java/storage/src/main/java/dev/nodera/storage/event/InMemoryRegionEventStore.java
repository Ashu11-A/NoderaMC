package dev.nodera.storage.event;

import dev.nodera.core.event.CommittedEventEnvelope;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.StateRoot;
import dev.nodera.storage.EventChainGuard;
import dev.nodera.storage.RegionEventStore;
import dev.nodera.storage.RegionOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * In-memory append-only per-region event log (Task 9). Enforces the two log invariants: {@code
 * eventId} is strictly monotonic per region (next id = last + 1), and the {@code prevRoot →
 * resultingRoot} chain is unbroken (each event's {@code prevRoot} equals the current head root). A
 * violation throws rather than silently corrupting the certified history (Invariant 3).
 *
 * @Thread-context confined to the owning thread; not thread-safe.
 */
public final class InMemoryRegionEventStore implements RegionEventStore {

    private final Map<RegionId, List<CommittedEventEnvelope>> logs = new TreeMap<>(RegionOrder.BY_DIMENSION_XZ);
    private final Map<RegionId, StateRoot> heads = new TreeMap<>(RegionOrder.BY_DIMENSION_XZ);

    @Override
    public void append(CommittedEventEnvelope event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        List<CommittedEventEnvelope> log = logs.computeIfAbsent(event.region(), k -> new ArrayList<>());
        long lastId = log.isEmpty() ? -1L : log.get(log.size() - 1).eventId();
        EventChainGuard.checkAppend(event, lastId, heads.get(event.region()));
        log.add(event);
        heads.put(event.region(), event.resultingRoot());
    }

    @Override
    public List<CommittedEventEnvelope> readFrom(RegionId region, long fromEventId) {
        List<CommittedEventEnvelope> log = logs.get(region);
        if (log == null) {
            return List.of();
        }
        List<CommittedEventEnvelope> out = new ArrayList<>();
        for (CommittedEventEnvelope e : log) {
            if (e.eventId() >= fromEventId) {
                out.add(e);
            }
        }
        return out;
    }

    @Override
    public long lastEventId(RegionId region) {
        List<CommittedEventEnvelope> log = logs.get(region);
        return (log == null || log.isEmpty()) ? -1L : log.get(log.size() - 1).eventId();
    }

    @Override
    public Optional<StateRoot> headRoot(RegionId region) {
        return Optional.ofNullable(heads.get(region));
    }

    @Override
    public List<RegionId> regions() {
        return new ArrayList<>(logs.keySet());
    }
}
