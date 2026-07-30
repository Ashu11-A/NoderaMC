package dev.nodera.shadow;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A bounded in-memory replica cache: {@code region → latest RegionSnapshot} (Task 5). Holds the
 * shadow-lane snapshots a worker (or the server's reference chain) advances batch by batch. Bounded
 * by region count with LRU eviction — a remote peer can grow the assigned-region set, so the cache
 * must never be unbounded (Plan §3.13). Eviction of an assigned region is what triggers a
 * {@code ResyncRequest} in the mod-side wiring.
 *
 * @Thread-context not thread-safe; confined to the owning worker/coordinator thread.
 */
public final class ReplicaStore {

    private final int maxRegions;
    private final LinkedHashMap<RegionId, RegionSnapshot> replicas;
    private long evictions;

    /**
     * @param maxRegions the maximum number of regions retained; older (least-recently-used) regions
     *                   are evicted beyond this bound.
     * @throws IllegalArgumentException if {@code maxRegions < 1}.
     */
    public ReplicaStore(int maxRegions) {
        if (maxRegions < 1) {
            throw new IllegalArgumentException("maxRegions must be >= 1, got " + maxRegions);
        }
        this.maxRegions = maxRegions;
        this.replicas = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<RegionId, RegionSnapshot> eldest) {
                boolean evict = size() > ReplicaStore.this.maxRegions;
                if (evict) {
                    ReplicaStore.this.evictions++;
                }
                return evict;
            }
        };
    }

    /**
     * Seed (or replace) the replica for a region — used on assignment and after a resync.
     *
     * @param snapshot the snapshot to hold; must not be null.
     * @throws IllegalArgumentException if {@code snapshot} is null.
     */
    public void seed(RegionSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        replicas.put(snapshot.region(), snapshot);
    }

    /** @return the current replica for {@code region}, or {@code null} if none is held. */
    public RegionSnapshot get(RegionId region) {
        return replicas.get(region);
    }

    /** @return the current version held for {@code region}, or {@code null} if none is held. */
    public SnapshotVersion version(RegionId region) {
        RegionSnapshot s = replicas.get(region);
        return s == null ? null : s.version();
    }

    /** @return {@code true} if a replica for {@code region} is held. */
    public boolean holds(RegionId region) {
        return replicas.containsKey(region);
    }

    /**
     * Advance the replica for {@code delta.region()} by applying {@code delta}. The held snapshot's
     * version must equal {@code delta.baseVersion()}.
     *
     * @param delta         the delta to apply.
     * @param resultingTick the post-state tick.
     * @return the advanced snapshot.
     * @throws IllegalStateException if no replica is held for the delta's region.
     * @throws ReplicaDriftException if the delta's CAS guard fails (caller re-snapshots).
     */
    public RegionSnapshot advance(RegionDelta delta, long resultingTick) {
        if (delta == null) {
            throw new IllegalArgumentException("delta must not be null");
        }
        RegionSnapshot base = replicas.get(delta.region());
        if (base == null) {
            throw new IllegalStateException("no replica held for " + delta.region());
        }
        RegionSnapshot next = SnapshotDeltaApplier.apply(base, delta, resultingTick);
        replicas.put(delta.region(), next);
        return next;
    }

    /** @return the number of regions currently held. */
    public int size() {
        return replicas.size();
    }

    /** @return the cumulative count of LRU evictions (bounded-cache health metric). */
    public long evictions() {
        return evictions;
    }
}
