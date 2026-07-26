package dev.nodera.simulation.border;

import dev.nodera.core.region.RegionBounds;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.SnapshotVersion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Read-only view of a region's halo ring: the {@code HALO_CHUNKS}-chunk ring a region must read for
 * border mechanics but never owns (Task 3 seam, Task 13 backing).
 *
 * <p><b>What changed, and why it matters.</b> This class was a stub that returned {@link #AIR} for
 * every read, with the real halo "arriving with Task 13". That stub is what makes a fluid stop dead
 * at a region boundary (engine L-2): the cell driving a spread across the border lives in the
 * neighbour's region, so the receiving region reads air where there is water and correctly concludes
 * that nothing should flow. It now holds real neighbour edge columns.
 *
 * <p><b>The halo is execution INPUT, not state.</b> Two consequences follow, and both are load-bearing:
 *
 * <ul>
 *   <li>It is immutable and travels in the {@code RegionExecutionRequest}, so
 *       {@code RegionEngine.execute} stays self-contained — the property that lets any replica
 *       re-execute a batch from the bundle alone and get the same root.</li>
 *   <li>Every replica of a region must hold the <b>identical</b> halo, or they will diverge without
 *       either of them being wrong. That is a delivery problem, not an engine one, which is why each
 *       slice records the source region and the exact snapshot version it was cut from: a replica
 *       can tell that its halo is stale rather than silently executing on a different world.</li>
 * </ul>
 *
 * <p><b>What it deliberately does not do.</b> It does not verify anything. A slice is bytes somebody
 * handed over, and this class has no way to know whether they match the source region's committed
 * root — checking that is the caller's job, and until a caller does it, a halo-backed read trusts
 * whoever produced the slice. An empty halo (the default everywhere) reads AIR exactly as before, so
 * nothing gains that trust dependency by accident.
 *
 * @Thread-context immutable, any thread.
 */
public final class RegionHalo {

    /** Air state id (palette constant mirrored locally). */
    public static final int AIR = 0;

    private static final int CHUNK_SIZE = 16;

    /**
     * One neighbour's contribution to this halo: the edge columns of {@code source} as they stood at
     * {@code version}.
     *
     * @param source  the region the columns were cut from.
     * @param version the source's snapshot version at the time of the cut — what makes staleness
     *                detectable instead of invisible.
     * @param columns the edge columns, in canonical (chunkX, chunkZ) order.
     */
    public record Slice(RegionId source, SnapshotVersion version, List<ChunkColumnState> columns) {

        /** Canonical order, so two replicas assembling the same slices assemble them identically. */
        public static final Comparator<Slice> CANONICAL_ORDER = Comparator
                .comparing((Slice s) -> s.source().dimension().toString())
                .thenComparingInt(s -> s.source().regionX())
                .thenComparingInt(s -> s.source().regionZ());

        public Slice {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(columns, "columns");
            List<ChunkColumnState> sorted = new ArrayList<>(columns);
            sorted.sort(Comparator.comparingInt(ChunkColumnState::chunkX)
                    .thenComparingInt(ChunkColumnState::chunkZ));
            columns = List.copyOf(sorted);
        }
    }

    private final RegionId region;
    /** packed (chunkX, chunkZ) → the column backing it. */
    private final Map<Long, ChunkColumnState> columns;
    /** source region → the version its columns were cut at, in canonical order. */
    private final TreeMap<String, SnapshotVersion> versions;
    private final List<Slice> slices;

    /**
     * An empty halo: every read is {@link #AIR}, which is exactly the behaviour every caller had
     * before neighbour slices existed.
     *
     * @param region the region whose halo this view represents.
     */
    public RegionHalo(RegionId region) {
        this(region, List.of());
    }

    /**
     * A halo backed by neighbour edge slices.
     *
     * <p>Columns that fall outside this region's halo ring are <b>dropped</b> rather than accepted:
     * a neighbour handing over columns this region does not read is either confused or probing, and
     * silently letting them through would let a neighbour define state inside the region's own
     * owned area. Overlapping claims resolve by canonical slice order, so the result does not depend
     * on delivery order.
     *
     * @param region the region whose halo this is.
     * @param slices the neighbour contributions.
     */
    public RegionHalo(RegionId region, List<Slice> slices) {
        if (region == null || slices == null) {
            throw new IllegalArgumentException("region and slices must not be null");
        }
        this.region = region;
        List<Slice> ordered = new ArrayList<>(slices);
        ordered.sort(Slice.CANONICAL_ORDER);
        this.slices = List.copyOf(ordered);
        Map<Long, ChunkColumnState> backing = new HashMap<>();
        TreeMap<String, SnapshotVersion> bySource = new TreeMap<>();
        RegionBounds bounds = RegionBounds.of(region);
        for (Slice slice : this.slices) {
            bySource.put(slice.source().toString(), slice.version());
            for (ChunkColumnState column : slice.columns()) {
                if (!bounds.isHaloChunk(column.chunkX(), column.chunkZ())) {
                    continue;
                }
                backing.putIfAbsent(key(column.chunkX(), column.chunkZ()), column);
            }
        }
        this.columns = Map.copyOf(backing);
        this.versions = bySource;
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** @return the region whose halo this view represents. */
    public RegionId region() {
        return region;
    }

    /** @return whether this halo holds nothing (the default, and the MVP behaviour). */
    public boolean isEmpty() {
        return columns.isEmpty();
    }

    /** @return the neighbour contributions, in canonical order. */
    public List<Slice> slices() {
        return slices;
    }

    /** @return how many halo chunk columns are backed by neighbour state. */
    public int backedColumns() {
        return columns.size();
    }

    /**
     * The backed halo columns, in canonical (chunkX, chunkZ) order.
     *
     * <p>Exposed for iteration because the cheap direction of a border question is to walk what the
     * halo actually holds — a few dozen columns — rather than the owned region's whole boundary,
     * which is a hundred thousand reads a batch for a border that is usually empty.
     */
    public List<ChunkColumnState> backing() {
        List<ChunkColumnState> out = new ArrayList<>(columns.values());
        out.sort(Comparator.comparingInt(ChunkColumnState::chunkX)
                .thenComparingInt(ChunkColumnState::chunkZ));
        return out;
    }

    /**
     * The version this halo carries for one source region.
     *
     * @return the version, or empty when no slice from that region is present — which a caller
     *         asserting freshness must treat as stale, not as "nothing to wait for".
     */
    public Optional<SnapshotVersion> versionOf(RegionId source) {
        return source == null ? Optional.empty()
                : Optional.ofNullable(versions.get(source.toString()));
    }

    /**
     * Read the neighbour-backed block at {@code pos}.
     *
     * @return the state id, or {@link #AIR} when no slice covers it. Air is the honest answer for an
     *         unbacked halo cell: it is what the region has always assumed, and a rule that would
     *         behave differently on real neighbour state is exactly the rule this backing exists to
     *         serve.
     */
    public int getBlock(NBlockPos pos) {
        if (pos == null) {
            return AIR;
        }
        ChunkColumnState column = columns.get(
                key(Math.floorDiv(pos.x(), CHUNK_SIZE), Math.floorDiv(pos.z(), CHUNK_SIZE)));
        if (column == null) {
            return AIR;
        }
        int section = Math.floorDiv(pos.y() - column.minY(), CHUNK_SIZE);
        if (section < 0 || section >= column.sectionCount()) {
            return AIR;
        }
        return column.blockAt(section,
                Math.floorMod(pos.x(), CHUNK_SIZE),
                Math.floorMod(pos.y() - column.minY(), CHUNK_SIZE),
                Math.floorMod(pos.z(), CHUNK_SIZE));
    }
}
