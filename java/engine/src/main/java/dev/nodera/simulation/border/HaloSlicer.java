package dev.nodera.simulation.border;

import dev.nodera.core.region.RegionBounds;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The producing half of the halo exchange (engine L-2): cut a committed snapshot's EDGE COLUMNS
 * for one neighbour.
 *
 * <p><b>Why this exists.</b> {@link RegionHalo} could always hold neighbour edge columns, and the
 * fluid automaton always flowed correctly once it had them — but nothing in production ever cut a
 * slice, so every halo everywhere was empty and a river stopped dead at the region boundary. This
 * class is the missing cut; {@link HaloStore} is the missing assembly.
 *
 * <p><b>Edges only, never the region.</b> A neighbour reads a {@code HALO_CHUNKS}-wide ring around
 * its owned square, so the only columns worth sending are the ones of ours that fall inside that
 * ring — a handful per neighbour rather than the 64-column region. Selection asks the RECEIVER's
 * bounds, so the sender cannot widen its own contribution: anything outside the receiver's ring is
 * dropped here, and dropped again by {@link RegionHalo}'s constructor.
 *
 * @Thread-context stateless, any thread.
 */
public final class HaloSlicer {

    private HaloSlicer() {
    }

    /**
     * The eight regions whose halo ring can overlap {@code region}'s owned square.
     *
     * @param region the region that just committed; must not be null.
     * @return the neighbours in canonical (dx, dz) order — deterministic, so two nodes producing
     *         slices for the same commit produce them in the same order.
     */
    public static List<RegionId> neighboursOf(RegionId region) {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        List<RegionId> out = new ArrayList<>(8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                out.add(new RegionId(
                        region.dimension(), region.regionX() + dx, region.regionZ() + dz));
            }
        }
        return List.copyOf(out);
    }

    /**
     * The columns of {@code snapshot} that fall inside {@code neighbour}'s halo ring.
     *
     * @param snapshot  the committed snapshot to cut; must not be null.
     * @param neighbour the region that would read these columns; must not be null.
     * @return the columns in canonical (chunkX, chunkZ) order, possibly empty (regions that touch
     *         only at a corner still share columns; regions further apart share none).
     */
    public static List<ChunkColumnState> edgeColumnsFor(
            RegionSnapshot snapshot, RegionId neighbour) {
        if (snapshot == null || neighbour == null) {
            throw new IllegalArgumentException("snapshot and neighbour must not be null");
        }
        RegionBounds ring = RegionBounds.of(neighbour);
        List<ChunkColumnState> out = new ArrayList<>();
        for (ChunkColumnState column : snapshot.chunks()) {
            if (ring.isHaloChunk(column.chunkX(), column.chunkZ())) {
                out.add(column);
            }
        }
        out.sort(Comparator.comparingInt(ChunkColumnState::chunkX)
                .thenComparingInt(ChunkColumnState::chunkZ));
        return List.copyOf(out);
    }

    /**
     * The slice {@code snapshot}'s region should hand to {@code neighbour}.
     *
     * @return the slice, or {@code null} when the two regions share no columns — the caller has
     *         nothing to send, which is the common case for all but the four side neighbours.
     */
    public static RegionHalo.Slice sliceFor(RegionSnapshot snapshot, RegionId neighbour) {
        List<ChunkColumnState> columns = edgeColumnsFor(snapshot, neighbour);
        return columns.isEmpty() ? null
                : new RegionHalo.Slice(snapshot.region(), snapshot.version(), columns);
    }
}
