package dev.nodera.simulation.entity;

import dev.nodera.core.state.EntityMutation;
import dev.nodera.core.state.InventoryCredit;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministically ordered mutable entity table for one engine execution (Task 12a). The store
 * retains immutable base and mutable working maps, then derives one coalesced compare-and-set
 * mutation per changed id. A create followed by updates remains one CREATE; an update followed by
 * removal remains one REMOVE; a create followed by removal vanishes from the state delta.
 *
 * @Thread-context thread-confined to one region-engine execution.
 */
public final class EntityStore {

    private final TreeMap<NetworkEntityId, PersistedEntityState> base = new TreeMap<>();
    private final TreeMap<NetworkEntityId, PersistedEntityState> work = new TreeMap<>();
    private final List<InventoryCredit> credits = new ArrayList<>();

    public EntityStore(List<PersistedEntityState> entities) {
        if (entities == null) {
            throw new IllegalArgumentException("entities must not be null");
        }
        for (PersistedEntityState entity : entities) {
            PersistedEntityState prior = base.put(entity.id(), entity);
            if (prior != null) {
                throw new IllegalArgumentException("duplicate entity id: " + entity.id());
            }
        }
        work.putAll(base);
    }

    /** Return current state or {@code null} when absent. */
    public PersistedEntityState get(NetworkEntityId id) {
        return work.get(id);
    }

    /** Return a sorted immutable snapshot of current entities. */
    public List<PersistedEntityState> entities() {
        return List.copyOf(work.values());
    }

    /** Insert a newly allocated entity. */
    public void create(PersistedEntityState entity) {
        if (work.putIfAbsent(entity.id(), entity) != null) {
            throw new IllegalStateException("entity already exists: " + entity.id());
        }
    }

    /** Replace an existing entity with its next state. */
    public void update(PersistedEntityState entity) {
        if (work.replace(entity.id(), entity) == null) {
            throw new IllegalStateException("entity does not exist: " + entity.id());
        }
    }

    /** Remove and return an existing entity. */
    public PersistedEntityState remove(NetworkEntityId id) {
        PersistedEntityState removed = work.remove(id);
        if (removed == null) {
            throw new IllegalStateException("entity does not exist: " + id);
        }
        return removed;
    }

    /** Record a replay-safe one-way inventory effect. */
    public void credit(InventoryCredit credit) {
        if (credit == null) {
            throw new IllegalArgumentException("credit must not be null");
        }
        credits.add(credit);
    }

    /** Derive sorted, coalesced mutations by diffing base against current state. */
    public List<EntityMutation> mutations() {
        TreeSet<NetworkEntityId> ids = new TreeSet<>(base.keySet());
        ids.addAll(work.keySet());
        List<EntityMutation> mutations = new ArrayList<>();
        for (NetworkEntityId id : ids) {
            PersistedEntityState before = base.get(id);
            PersistedEntityState after = work.get(id);
            if (!java.util.Objects.equals(before, after)) {
                mutations.add(new EntityMutation(id, before, after));
            }
        }
        return List.copyOf(mutations);
    }

    /** Return inventory effects in insertion order; RegionDelta canonicalizes them. */
    public List<InventoryCredit> credits() {
        return List.copyOf(credits);
    }

    /** Package-visible immutable base map for focused tests. */
    Map<NetworkEntityId, PersistedEntityState> baseView() {
        return Map.copyOf(base);
    }
}
