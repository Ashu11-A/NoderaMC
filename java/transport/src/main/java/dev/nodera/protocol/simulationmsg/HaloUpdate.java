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
 * <p><b>Version 2 carries an endorsement</b> (engine L-2). The halo is execution input, so a
 * receiver that cannot tell which slice its fellow committee members also hold must either trust
 * the sender or refuse to use the slice at all. Version 2 attaches the sender's encoded
 * {@code HaloEndorsement}: its own signature over the exact edge columns in this frame. A receiver
 * collects endorsements from the SOURCE region's committee and applies the slice only once a
 * strict majority of that committee has signed the identical slice root — provenance, not trust.
 *
 * @param region             the region whose edge just committed; not null.
 * @param version            the committed snapshot version the slices belong to; not null.
 * @param encodedEdgeColumns encoded {@code ChunkColumnState} frames for the edge columns only;
 *                           not null, elements not null.
 * @param encodedEndorsement encoded {@code HaloEndorsement} of the sender; null exactly when
 *                           {@code bodyVersion} is 1 (a pre-L-2 peer's unattested frame).
 * @param bodyVersion        1 = unattested (legacy), 2 = endorsed.
 */
public record HaloUpdate(
        RegionId region,
        SnapshotVersion version,
        List<Bytes> encodedEdgeColumns,
        Bytes encodedEndorsement,
        int bodyVersion) implements NoderaMessage {

    /** Version 1 was the unattested slice; version 2 binds it to a committee signature. */
    public static final int HALO_UPDATE_ENCODING_VERSION = 2;

    /** Legacy constructor: an unattested slice, which a receiver may store but never execute on. */
    public HaloUpdate(RegionId region, SnapshotVersion version, List<Bytes> encodedEdgeColumns) {
        this(region, version, encodedEdgeColumns, null, 1);
    }

    /** Current constructor: the slice plus the sending member's endorsement of it. */
    public HaloUpdate(RegionId region, SnapshotVersion version, List<Bytes> encodedEdgeColumns,
                      Bytes encodedEndorsement) {
        this(region, version, encodedEdgeColumns, Objects.requireNonNull(
                encodedEndorsement, "encodedEndorsement"), HALO_UPDATE_ENCODING_VERSION);
    }

    public HaloUpdate {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(encodedEdgeColumns, "encodedEdgeColumns");
        encodedEdgeColumns = List.copyOf(encodedEdgeColumns);
        if (bodyVersion < 1 || bodyVersion > HALO_UPDATE_ENCODING_VERSION) {
            throw new IllegalArgumentException("unsupported HaloUpdate body version " + bodyVersion);
        }
        if (bodyVersion == 1 && encodedEndorsement != null) {
            throw new IllegalArgumentException("legacy halo update cannot carry an endorsement");
        }
        if (bodyVersion >= 2 && encodedEndorsement == null) {
            throw new IllegalArgumentException("version 2 halo update requires an endorsement");
        }
    }
}
