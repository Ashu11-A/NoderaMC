package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.Bytes;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * Announcement that a region commit has been finalised (Task 4).
 *
 * <p>Carries the {@code region}, the new {@code version}, the resulting {@link StateRoot}, and
 * the assembled {@code certificateBytes} (canonical {@code QuorumCertificate} bytes). Replicas
 * apply the committed delta and advance to {@code version} on receipt.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param region           region that advanced.
 * @param version          newly-committed snapshot version.
 * @param resultingRoot    post-commit state root.
 * @param certificateBytes canonical {@code QuorumCertificate} bytes co-signed by the committee.
 */
public record CommitAnnounce(
        RegionId region,
        SnapshotVersion version,
        StateRoot resultingRoot,
        Bytes certificateBytes
) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any argument is null.
     */
    public CommitAnnounce {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(resultingRoot, "resultingRoot");
        Objects.requireNonNull(certificateBytes, "certificateBytes");
    }
}
