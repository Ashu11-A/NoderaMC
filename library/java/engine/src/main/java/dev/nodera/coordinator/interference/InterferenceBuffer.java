package dev.nodera.coordinator.interference;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityMutation;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.PersistedEntityState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Objects;

/**
 * Per-region collection of foreign mutations recorded during the current tick (Task 11; per-block
 * since the Task 13 densification). Writes to the same BLOCK POSITION coalesce: the first write's
 * {@code prevStateId} is kept (the state the committed version chain knows) and the last write's
 * {@code newStateId} wins, so a drained region yields at most one mutation per position and a
 * converted {@code RegionDelta} can never carry two CAS guards for one position. A coalesced write
 * that lands back on its original state ({@code prev == new}) drops out entirely — the world is
 * bit-identical, there is nothing to certify.
 *
 * @Thread-context server main thread only (single writer, like the guard that feeds it).
 */
public final class InterferenceBuffer {

    private final Map<RegionId, Map<NBlockPos, RecordedMutation>> byRegion = new LinkedHashMap<>();
    private final Map<RegionId, Map<NetworkEntityId, RecordedEntityChange>> entitiesByRegion =
            new LinkedHashMap<>();

    /** Record one foreign write. Coalesces with an earlier write to the same block position. */
    public void record(RegionId region, RecordedMutation mutation) {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        if (mutation == null) {
            throw new IllegalArgumentException("mutation must not be null");
        }
        Map<NBlockPos, RecordedMutation> perPos =
                byRegion.computeIfAbsent(region, r -> new LinkedHashMap<>());
        perPos.merge(mutation.pos(), mutation, (first, latest) -> new RecordedMutation(
                first.pos(), first.prevStateId(), latest.newStateId(), latest.source()));
    }

    /**
     * Record one vanilla-authoritative ghost entity change. Repeated updates coalesce to the first
     * expected state and latest new state; create-then-remove vanishes when drained.
     */
    public void recordEntity(RegionId region, PersistedEntityState expectedPrevious,
                             PersistedEntityState newState) {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        if (expectedPrevious == null && newState == null) {
            throw new IllegalArgumentException("entity change must have an expected or new state");
        }
        NetworkEntityId id = expectedPrevious != null ? expectedPrevious.id() : newState.id();
        if (expectedPrevious != null && newState != null
                && !expectedPrevious.id().equals(newState.id())) {
            throw new IllegalArgumentException("entity change ids do not match");
        }
        Map<NetworkEntityId, RecordedEntityChange> perEntity = entitiesByRegion.computeIfAbsent(
                region, ignored -> new LinkedHashMap<>());
        RecordedEntityChange latest = new RecordedEntityChange(id, expectedPrevious, newState);
        perEntity.merge(id, latest, (first, next) ->
                new RecordedEntityChange(id, first.expectedPrevious(), next.newState()));
    }

    /** Regions with at least one buffered mutation, in first-recorded order. */
    public Set<RegionId> pendingRegions() {
        LinkedHashSet<RegionId> regions = new LinkedHashSet<>(byRegion.keySet());
        regions.addAll(entitiesByRegion.keySet());
        return java.util.Collections.unmodifiableSet(regions);
    }

    /** True when {@code region} has nothing buffered. */
    public boolean isEmpty(RegionId region) {
        Map<NBlockPos, RecordedMutation> perPos = byRegion.get(region);
        Map<NetworkEntityId, RecordedEntityChange> perEntity = entitiesByRegion.get(region);
        return (perPos == null || perPos.isEmpty())
                && (perEntity == null || perEntity.isEmpty());
    }

    /**
     * Remove and return {@code region}'s buffered mutations (coalesced, first-recorded order).
     * No-op writes ({@code prev == new} after coalescing) are dropped here.
     */
    public List<RecordedMutation> drain(RegionId region) {
        Map<NBlockPos, RecordedMutation> perPos = byRegion.remove(region);
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

    /** Drain coalesced entity-table changes in first-recorded order. */
    public List<EntityMutation> drainEntities(RegionId region) {
        Map<NetworkEntityId, RecordedEntityChange> perEntity = entitiesByRegion.remove(region);
        if (perEntity == null) {
            return List.of();
        }
        List<EntityMutation> out = new ArrayList<>(perEntity.size());
        for (RecordedEntityChange change : perEntity.values()) {
            if (!Objects.equals(change.expectedPrevious(), change.newState())) {
                out.add(new EntityMutation(
                        change.id(), change.expectedPrevious(), change.newState()));
            }
        }
        return List.copyOf(out);
    }

    /** Restore drained block mutations after downstream persistence/broadcast failure. */
    public void restore(RegionId region, List<RecordedMutation> mutations) {
        for (RecordedMutation mutation : mutations) {
            record(region, mutation);
        }
    }

    /** Restore drained entity mutations after downstream persistence/broadcast failure. */
    public void restoreEntities(RegionId region, List<EntityMutation> mutations) {
        for (EntityMutation mutation : mutations) {
            recordEntity(region, mutation.expectedPrevious(), mutation.newState());
        }
    }

    private record RecordedEntityChange(
            NetworkEntityId id,
            PersistedEntityState expectedPrevious,
            PersistedEntityState newState) {
    }
}
