package dev.nodera.diagnostics.model;

import dev.nodera.core.region.RegionId;

import java.util.List;
import java.util.Map;

/**
 * Entities delegated to this peer, grouped by region (Task 18).
 *
 * <p>Placeholder until Task 12's entity lane supplies a real
 * {@link dev.nodera.diagnostics.source.EntityControlProvider}; the empty instance renders "no
 * controlled entities" (LIMITATIONS L-31). Entity ids are opaque {@code long} network ids.
 *
 * @param entities region → list of controlled entity network ids.
 * @Thread-context immutable record, any thread.
 */
public record EntityControl(Map<RegionId, List<Long>> entities) {

    /** Compact constructor copies the map (and each list) into immutable containers. */
    public EntityControl {
        if (entities == null) {
            entities = Map.of();
        } else {
            java.util.Map<RegionId, List<Long>> copy = new java.util.LinkedHashMap<>();
            for (Map.Entry<RegionId, List<Long>> e : entities.entrySet()) {
                copy.put(e.getKey(), e.getValue() == null ? List.of() : List.copyOf(e.getValue()));
            }
            entities = Map.copyOf(copy);
        }
    }

    /** @return the total controlled-entity count across all regions. */
    public int totalCount() {
        int n = 0;
        for (List<Long> v : entities.values()) {
            n += v.size();
        }
        return n;
    }

    /** The empty placeholder instance (Task 18 staging — owned by Task 12). */
    public static EntityControl empty() {
        return new EntityControl(Map.of());
    }
}
