package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * "Send me a fresh snapshot" request (Task 4). Emitted by a replica that has fallen too far
 * behind to apply incremental deltas — typically after a {@code RESYNC_REQUIRED} vote decision
 * — naming the region and the last version it has.
 *
 * <p>The server (or a peer with archival duty) responds with a {@link SnapshotAnnounce}
 * followed by the stream.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param region       region that needs a fresh snapshot.
 * @param haveVersion  last snapshot version the requester holds.
 */
public record ResyncRequest(RegionId region, SnapshotVersion haveVersion)
        implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any argument is null.
     */
    public ResyncRequest {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(haveVersion, "haveVersion");
    }
}
