package dev.nodera.coordinator;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
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

    /** Load a region's state from a snapshot (assignment / resync). */
    public void load(RegionSnapshot snapshot) {
        Map<Long, Column> cols = new HashMap<>(snapshot.chunks().size());
        for (ChunkColumnState c : snapshot.chunks()) {
            cols.put(pack(c.chunkX(), c.chunkZ()), new Column(c));
        }
        world.put(snapshot.region(), cols);
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
        return col.palette[section];
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
        col.palette[section] = stateId;
    }

    /** Re-extract a region's live state as a canonical snapshot (for post-commit verification). */
    public RegionSnapshot reExtract(RegionId region, SnapshotVersion version, long tick) {
        Map<Long, Column> cols = world.get(region);
        if (cols == null) {
            throw new IllegalStateException("region not loaded: " + region);
        }
        List<ChunkColumnState> out = new ArrayList<>(cols.size());
        for (Map.Entry<Long, Column> e : cols.entrySet()) {
            Column col = e.getValue();
            out.add(new ChunkColumnState(unpackX(e.getKey()), unpackZ(e.getKey()),
                    col.palette.clone(), col.minY, col.sectionCount));
        }
        return new RegionSnapshot(region, version, tick, out);
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

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    private static final class Column {
        final int minY;
        final int sectionCount;
        final int[] palette;

        Column(ChunkColumnState c) {
            this.minY = c.minY();
            this.sectionCount = c.sectionCount();
            this.palette = c.paletteStateIdsPerSection();
        }
    }
}
