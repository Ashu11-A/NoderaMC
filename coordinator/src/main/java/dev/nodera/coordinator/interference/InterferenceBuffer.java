package dev.nodera.coordinator.interference;

import dev.nodera.core.region.RegionId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-region collection of foreign mutations recorded during the current tick (Task 11). Writes to
 * the same position coalesce: the first write's {@code prevStateId} is kept (that is the state the
 * committed version chain knows) and the last write's {@code newStateId} wins, so a drained region
 * yields at most one mutation per position and a converted {@code RegionDelta} can never carry two
 * CAS guards for one block. A coalesced write that lands back on its original state
 * ({@code prev == new}) drops out entirely — the world is bit-identical, there is nothing to
 * certify.
 *
 * @Thread-context server main thread only (single writer, like the guard that feeds it).
 */
public final class InterferenceBuffer {

    private final Map<RegionId, Map<Long, RecordedMutation>> byRegion = new LinkedHashMap<>();

    /** Record one foreign write. Coalesces with an earlier write to the same position. */
    public void record(RegionId region, RecordedMutation mutation) {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        if (mutation == null) {
            throw new IllegalArgumentException("mutation must not be null");
        }
        Map<Long, RecordedMutation> perPos = byRegion.computeIfAbsent(region, r -> new LinkedHashMap<>());
        perPos.merge(posKey(mutation), mutation, (first, latest) -> new RecordedMutation(
                first.pos(), first.prevStateId(), latest.newStateId(), latest.source()));
    }

    /** Regions with at least one buffered mutation, in first-recorded order. */
    public Set<RegionId> pendingRegions() {
        return Set.copyOf(byRegion.keySet());
    }

    /** True when {@code region} has nothing buffered. */
    public boolean isEmpty(RegionId region) {
        Map<Long, RecordedMutation> perPos = byRegion.get(region);
        return perPos == null || perPos.isEmpty();
    }

    /**
     * Remove and return {@code region}'s buffered mutations (coalesced, first-recorded order).
     * No-op writes ({@code prev == new} after coalescing) are dropped here.
     */
    public List<RecordedMutation> drain(RegionId region) {
        Map<Long, RecordedMutation> perPos = byRegion.remove(region);
        if (perPos == null) {
            return List.of();
        }
        List<RecordedMutation> out = new ArrayList<>(perPos.size());
        for (RecordedMutation m : perPos.values()) {
            if (m.prevStateId() != m.newStateId()) {
                out.add(m);
            }
        }
        return List.copyOf(out);
    }

    private static long posKey(RecordedMutation m) {
        // Positions are region-local block coordinates; 21 bits per axis is ample.
        return ((long) m.pos().y() << 42) ^ ((long) (m.pos().z() & 0x1FFFFF) << 21)
                ^ (m.pos().x() & 0x1FFFFF);
    }
}
