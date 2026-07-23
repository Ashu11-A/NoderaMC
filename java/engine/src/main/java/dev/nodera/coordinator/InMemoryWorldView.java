package dev.nodera.coordinator;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.InventoryCredit;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A section-granularity in-memory {@link MutableWorldView} for headless tests and the reference
 * chain (Task 6). Backed by the same {@link ChunkColumnState} model as the engine, so
 * {@link #reExtract(RegionId, SnapshotVersion, long)} produces a snapshot whose root can be compared
 * against an engine recompute — the "world state provably uncorrupted" check (Task 6 criterion 3).
 *
 * @Thread-context confined to the owning thread; not thread-safe.
 */
public final class InMemoryWorldView implements MutableWorldView {

    private static final int CHUNK_SIZE = 16;
    private static final int AIR = 0;

    /** region → (packed chunk → column model). */
    private final Map<RegionId, Map<Long, Column>> world = new HashMap<>();
    private final Map<RegionId, Map<NetworkEntityId, PersistedEntityState>> entities = new HashMap<>();
    private final Map<RegionId, Integer> snapshotBodyVersions = new HashMap<>();
    private final Map<CreditKey, InventoryCredit> credits = new HashMap<>();
    private boolean mutationOpen;

    /** Load a region's state from a snapshot (assignment / resync). */
    public void load(RegionSnapshot snapshot) {
        Map<Long, Column> cols = new HashMap<>(snapshot.chunks().size());
        for (ChunkColumnState c : snapshot.chunks()) {
            cols.put(pack(c.chunkX(), c.chunkZ()), new Column(c));
        }
        world.put(snapshot.region(), cols);
        Map<NetworkEntityId, PersistedEntityState> entityRows = new HashMap<>();
        for (PersistedEntityState entity : snapshot.entities()) {
            entityRows.put(entity.id(), entity);
        }
        entities.put(snapshot.region(), entityRows);
        snapshotBodyVersions.put(snapshot.region(), snapshot.bodyVersion());
    }

    @Override
    public boolean isRegionLoaded(RegionId region) {
        return world.containsKey(region) && entities.containsKey(region);
    }

    @Override
    public MutationScope beginMutation() {
        if (mutationOpen) {
            throw new IllegalStateException("nested world mutation");
        }
        mutationOpen = true;
        Map<RegionId, Map<Long, Column>> worldBefore = copyWorld();
        Map<RegionId, Map<NetworkEntityId, PersistedEntityState>> entitiesBefore = copyEntities();
        Map<RegionId, Integer> bodyVersionsBefore = new HashMap<>(snapshotBodyVersions);
        Map<CreditKey, InventoryCredit> creditsBefore = new HashMap<>(credits);
        return new MutationScope() {
            private boolean committed;
            private boolean closed;

            @Override
            public void commit() {
                if (closed) {
                    throw new IllegalStateException("mutation scope already closed");
                }
                committed = true;
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                if (!committed) {
                    world.clear();
                    world.putAll(worldBefore);
                    entities.clear();
                    entities.putAll(entitiesBefore);
                    snapshotBodyVersions.clear();
                    snapshotBodyVersions.putAll(bodyVersionsBefore);
                    credits.clear();
                    credits.putAll(creditsBefore);
                }
                mutationOpen = false;
            }
        };
    }

    @Override
    public int getBlock(RegionId region, NBlockPos pos) {
        Column col = columnAt(region, pos);
        if (col == null) {
            return AIR;
        }
        int section = Math.floorDiv(pos.y() - col.minY, CHUNK_SIZE);
        if (section < 0 || section >= col.sectionCount) {
            return AIR;
        }
        return col.state.blockAt(section,
                Math.floorMod(pos.x(), CHUNK_SIZE),
                Math.floorMod(pos.y() - col.minY, CHUNK_SIZE),
                Math.floorMod(pos.z(), CHUNK_SIZE));
    }

    @Override
    public void setBlock(RegionId region, NBlockPos pos, int stateId) {
        Column col = columnAt(region, pos);
        if (col == null) {
            throw new IllegalStateException("setBlock on unloaded chunk for " + region + " at " + pos);
        }
        int section = Math.floorDiv(pos.y() - col.minY, CHUNK_SIZE);
        if (section < 0 || section >= col.sectionCount) {
            throw new IllegalStateException("setBlock outside section range at " + pos);
        }
        col.state = col.state.withBlock(section,
                Math.floorMod(pos.x(), CHUNK_SIZE),
                Math.floorMod(pos.y() - col.minY, CHUNK_SIZE),
                Math.floorMod(pos.z(), CHUNK_SIZE),
                stateId);
    }

    @Override
    public PersistedEntityState getEntity(RegionId region, NetworkEntityId id) {
        Map<NetworkEntityId, PersistedEntityState> rows = entities.get(region);
        return rows == null ? null : rows.get(id);
    }

    @Override
    public void setEntity(RegionId region, PersistedEntityState entity) {
        Map<NetworkEntityId, PersistedEntityState> rows = entities.get(region);
        if (rows == null) {
            throw new IllegalStateException("region not loaded: " + region);
        }
        rows.put(entity.id(), entity);
        snapshotBodyVersions.put(region, RegionSnapshot.STATE_ENCODING_VERSION);
    }

    @Override
    public void removeEntity(RegionId region, NetworkEntityId id) {
        Map<NetworkEntityId, PersistedEntityState> rows = entities.get(region);
        if (rows == null) {
            throw new IllegalStateException("region not loaded: " + region);
        }
        rows.remove(id);
    }

    @Override
    public InventoryCredit getInventoryCredit(InventoryCredit credit) {
        return credits.get(new CreditKey(credit.actor(), credit.entityId()));
    }

    @Override
    public void creditInventory(InventoryCredit credit) {
        CreditKey key = new CreditKey(credit.actor(), credit.entityId());
        InventoryCredit prior = credits.putIfAbsent(key, credit);
        if (prior != null && !prior.equals(credit)) {
            throw new IllegalStateException("conflicting inventory credit for " + key);
        }
    }

    /** Re-extract a region's live state as a canonical snapshot (for post-commit verification). */
    @Override
    public RegionSnapshot reExtract(RegionId region, SnapshotVersion version, long tick) {
        Map<Long, Column> cols = world.get(region);
        if (cols == null) {
            throw new IllegalStateException("region not loaded: " + region);
        }
        List<ChunkColumnState> out = new ArrayList<>(cols.size());
        for (Column col : cols.values()) {
            out.add(col.state);
        }
        return new RegionSnapshot(region, version, tick, out,
                new ArrayList<>(entities.getOrDefault(region, Map.of()).values()),
                snapshotBodyVersions.getOrDefault(region, RegionSnapshot.STATE_ENCODING_VERSION));
    }

    @Override
    public void setSnapshotBodyVersion(RegionId region, int bodyVersion) {
        if (!isRegionLoaded(region)) {
            throw new IllegalStateException("region not loaded: " + region);
        }
        if (bodyVersion < 1 || bodyVersion > RegionSnapshot.STATE_ENCODING_VERSION) {
            throw new IllegalArgumentException("unsupported snapshot body version " + bodyVersion);
        }
        snapshotBodyVersions.merge(region, bodyVersion, Math::max);
    }

    private Column columnAt(RegionId region, NBlockPos pos) {
        Map<Long, Column> cols = world.get(region);
        if (cols == null) {
            return null;
        }
        return cols.get(pack(Math.floorDiv(pos.x(), CHUNK_SIZE), Math.floorDiv(pos.z(), CHUNK_SIZE)));
    }

    private static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private Map<RegionId, Map<Long, Column>> copyWorld() {
        Map<RegionId, Map<Long, Column>> copy = new HashMap<>();
        for (Map.Entry<RegionId, Map<Long, Column>> region : world.entrySet()) {
            Map<Long, Column> columns = new HashMap<>();
            for (Map.Entry<Long, Column> column : region.getValue().entrySet()) {
                columns.put(column.getKey(), new Column(column.getValue()));
            }
            copy.put(region.getKey(), columns);
        }
        return copy;
    }

    private Map<RegionId, Map<NetworkEntityId, PersistedEntityState>> copyEntities() {
        Map<RegionId, Map<NetworkEntityId, PersistedEntityState>> copy = new HashMap<>();
        for (Map.Entry<RegionId, Map<NetworkEntityId, PersistedEntityState>> region
                : entities.entrySet()) {
            copy.put(region.getKey(), new HashMap<>(region.getValue()));
        }
        return copy;
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    private static final class Column {
        final int minY;
        final int sectionCount;
        ChunkColumnState state;

        Column(ChunkColumnState c) {
            this.minY = c.minY();
            this.sectionCount = c.sectionCount();
            this.state = c;
        }

        Column(Column c) {
            this.minY = c.minY;
            this.sectionCount = c.sectionCount;
            this.state = c.state; // immutable — sharing is safe
        }
    }

    private record CreditKey(dev.nodera.core.identity.NodeId actor, NetworkEntityId entityId) {
    }
}
