package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * Announcement that a fresh snapshot is available for streaming (Task 4).
 *
 * <p>Precedes a sequence of {@link StreamChunk}s carrying the compressed snapshot payload. The
 * receiver allocates a reassembly buffer of {@code contentLength} bytes and expects
 * {@code chunkCount} chunks; the {@link StateRoot} lets the receiver verify the reassembled
 * bytes once the stream completes.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param region        region whose snapshot is announced.
 * @param version       snapshot version.
 * @param contentLength uncompressed content length, in bytes (for receiver buffer sizing).
 * @param chunkCount    number of {@link StreamChunk}s the receiver should expect.
 * @param root          {@link StateRoot} of the snapshot, for post-reassembly verification.
 */
public record SnapshotAnnounce(
        RegionId region,
        SnapshotVersion version,
        int contentLength,
        int chunkCount,
        StateRoot root
) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any argument is null.
     */
    public SnapshotAnnounce {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(root, "root");
    }
}
