package dev.nodera.coordinator;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkKey;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.ChunkStamp;
import dev.nodera.core.state.ChunkStampBook;
import dev.nodera.core.state.Hlc;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.InventoryCredit;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionChunkIndex;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;

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

    /**
     * How many times each region's canonical state has been written.
     *
     * <p>The cache key for {@link #regionRoot}. It only has to satisfy one property — equal revision
     * implies equal state — so it is bumped on every write that {@link #reExtract} would observe and
     * on every rollback, and never anywhere else. Inventory credits are deliberately absent: they do
     * not appear in a {@link RegionSnapshot}, so they cannot change its root.
     */
    private final Map<RegionId, Long> revisions = new HashMap<>();
    private final Map<RegionId, CachedRoot> roots = new HashMap<>();
    private final HashService hashes = new HashService();
    private ChunkStampBook stampBook;
    private boolean mutationOpen;

    /**
     * Install the book that records when each column was last written here.
     *
     * <p>Stamping happens in {@link #setBlock} rather than at the write guard because this is the one
     * funnel every canonical write passes through — a foreign write converted by the guard, and a
     * certified delta applied by {@link WorldMutationApplier}, both land here and both change what
     * the column holds. Hooking the guard would have stamped the first and missed the second, so a
     * validator that applied somebody else's commit would report the column as never having been
     * written and lose the merge for content it holds correctly.
     *
     * @param book the book, or {@code null} to stop stamping.
     * @Thread-context server main thread.
     */
    public void stampBook(ChunkStampBook book) {
        this.stampBook = book;
    }

    /** Load a region's state from a snapshot (assignment / resync). */
    public void load(RegionSnapshot snapshot) {
        Map<Long, Column> cols = new HashMap<>(snapshot.chunks().size());
        for (ChunkColumnState c : snapshot.chunks()) {
            cols.put(ChunkKey.pack(c.chunkX(), c.chunkZ()), new Column(c));
        }
        world.put(snapshot.region(), cols);
        Map<NetworkEntityId, PersistedEntityState> entityRows = new HashMap<>();
        for (PersistedEntityState entity : snapshot.entities()) {
            entityRows.put(entity.id(), entity);
        }
        entities.put(snapshot.region(), entityRows);
        snapshotBodyVersions.put(snapshot.region(), snapshot.bodyVersion());
        touch(snapshot.region());
    }

    /** Record that {@code region}'s canonical state changed, so any cached root for it is void. */
    private void touch(RegionId region) {
        revisions.merge(region, 1L, Long::sum);
        roots.remove(region);
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
                    // A rollback is a state change like any other. The restored columns carry their
                    // own hashes (immutable, shared through the copy constructor), but a root cached
                    // against a revision that has now been undone must not be answered again — so
                    // every region's revision moves rather than returning to what it was.
                    for (RegionId region : new ArrayList<>(revisions.keySet())) {
                        touch(region);
                    }
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
        return col.state().blockAt(section,
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
        col.replace(col.state().withBlock(section,
                Math.floorMod(pos.x(), CHUNK_SIZE),
                Math.floorMod(pos.y() - col.minY, CHUNK_SIZE),
                Math.floorMod(pos.z(), CHUNK_SIZE),
                stateId));
        touch(region);
        if (stampBook != null) {
            stampBook.touch(col.state().chunkX(), col.state().chunkZ());
        }
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
        touch(region);
    }

    @Override
    public void removeEntity(RegionId region, NetworkEntityId id) {
        Map<NetworkEntityId, PersistedEntityState> rows = entities.get(region);
        if (rows == null) {
            throw new IllegalStateException("region not loaded: " + region);
        }
        rows.remove(id);
        touch(region);
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
            out.add(col.state());
        }
        return new RegionSnapshot(region, version, tick, out,
                new ArrayList<>(entities.getOrDefault(region, Map.of()).values()),
                snapshotBodyVersions.getOrDefault(region, RegionSnapshot.STATE_ENCODING_VERSION));
    }

    /**
     * The region's root, recomputed only when the region has actually changed.
     *
     * <p>Deliberately still {@code hash(reExtract(...))} on a miss rather than a hand-rolled
     * incremental encoding. This value is a <b>consensus</b> root: a second encoder that drifted from
     * {@link RegionSnapshot#encode} by one byte would make two peers disagree about committed state,
     * silently, with no test that naturally catches it. So the fast path is "do not compute it
     * again", never "compute it differently".
     */
    @Override
    public StateRoot regionRoot(RegionId region, SnapshotVersion version, long tick) {
        long revision = revisions.getOrDefault(region, 0L);
        CachedRoot cached = roots.get(region);
        if (cached != null && cached.matches(revision, version, tick)) {
            return cached.root();
        }
        StateRoot root = StateRoot.of(hashes.hash(reExtract(region, version, tick)));
        roots.put(region, new CachedRoot(revision, version, tick, root));
        return root;
    }

    /**
     * The region's index, re-hashing only the columns written since the last call.
     *
     * <p>Unlike the root, this decomposes: a column's stamp is a hash over that column alone, so a
     * clean column's stamp is simply the one already computed. Placing one block re-hashes one
     * column out of sixty-four.
     */
    @Override
    public RegionChunkIndex chunkIndex(RegionId region, ChunkStampBook book) {
        Map<Long, Column> cols = world.get(region);
        if (cols == null) {
            throw new IllegalStateException("region not loaded: " + region);
        }
        List<ChunkStamp> stamps = new ArrayList<>(cols.size());
        for (Column col : cols.values()) {
            ChunkColumnState state = col.state();
            Hlc stamp = book == null ? Hlc.ZERO
                    : book.stampFor(state.chunkX(), state.chunkZ(), Hlc.ZERO);
            stamps.add(new ChunkStamp(state.chunkX(), state.chunkZ(), col.contentHash(), stamp));
        }
        return RegionChunkIndex.of(region, stamps);
    }

    /** A root and the exact inputs it was computed from. */
    private record CachedRoot(long revision, SnapshotVersion version, long tick, StateRoot root) {
        boolean matches(long revision, SnapshotVersion version, long tick) {
            return this.revision == revision && this.tick == tick && this.version.equals(version);
        }
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
        touch(region);
    }

    private Column columnAt(RegionId region, NBlockPos pos) {
        Map<Long, Column> cols = world.get(region);
        if (cols == null) {
            return null;
        }
        return cols.get(ChunkKey.pack(
                Math.floorDiv(pos.x(), CHUNK_SIZE), Math.floorDiv(pos.z(), CHUNK_SIZE)));
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

    /**
     * One chunk column, and the hash of what it currently holds.
     *
     * <p>The hash is computed on demand and thrown away on write. Columns are replaced <b>wholesale</b>
     * — {@link ChunkColumnState} is immutable and {@link #setBlock} assigns a new one — so "has this
     * column changed" is exactly "was {@code state} reassigned", and the cache cannot go stale
     * without the assignment that clears it. Both fields are immutable values, so the copy
     * constructor the rollback path uses shares them and a rolled-back scope keeps its hashes.
     */
    private static final class Column {
        final int minY;
        final int sectionCount;
        private ChunkColumnState state;
        private Bytes contentHash;

        Column(ChunkColumnState c) {
            this.minY = c.minY();
            this.sectionCount = c.sectionCount();
            this.state = c;
        }

        Column(Column c) {
            this.minY = c.minY;
            this.sectionCount = c.sectionCount;
            this.state = c.state; // immutable — sharing is safe
            this.contentHash = c.contentHash;
        }

        ChunkColumnState state() {
            return state;
        }

        void replace(ChunkColumnState next) {
            state = next;
            contentHash = null;
        }

        Bytes contentHash() {
            if (contentHash == null) {
                contentHash = ChunkStamp.of(state, Hlc.ZERO).contentHash();
            }
            return contentHash;
        }
    }

    private record CreditKey(dev.nodera.core.identity.NodeId actor, NetworkEntityId entityId) {
    }
}
