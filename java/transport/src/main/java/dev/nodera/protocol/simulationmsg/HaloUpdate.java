package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.Bytes;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.protocol.NoderaMessage;

import java.util.List;
import java.util.Objects;

/**
 * Neighbor-edge slice refresh (Task 13 border lane): after {@code region} commits
 * {@code version}, its coordinator sends the region's EDGE COLUMNS (never the full region) to
 * the committees of every neighbor whose halo overlaps them. Redstone reads halo state, so the
 * halo must track neighbor versions — batch execution asserts halo versions {@code >=} required
 * and requests a refresh on staleness instead of guessing (spec: HaloUpdate flow).
 *
 * <p>The columns travel as opaque encoded {@code ChunkColumnState} frames — the transport plane
 * never interprets region state, mirroring {@link EventSyncAnswer}'s encoded-events discipline.
 *
 * @param region             the region whose edge just committed; not null.
 * @param version            the committed snapshot version the slices belong to; not null.
 * @param encodedEdgeColumns encoded {@code ChunkColumnState} frames for the edge columns only;
 *                           not null, elements not null.
 */
public record HaloUpdate(
        RegionId region,
        SnapshotVersion version,
        List<Bytes> encodedEdgeColumns) implements NoderaMessage {

    public HaloUpdate {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(encodedEdgeColumns, "encodedEdgeColumns");
        encodedEdgeColumns = List.copyOf(encodedEdgeColumns);
    }
}
