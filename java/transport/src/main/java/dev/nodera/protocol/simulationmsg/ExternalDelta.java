package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.Bytes;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * A server-authority version advance for a delegated region (Task 11). Carries the canonical
 * {@code RegionDelta} bytes plus the encoded {@code ServerAuthorityCertificate} that authorises
 * them. Clients verify the certificate (signature + reason) and apply the delta to their replica
 * WITHOUT voting — the same replica-advance code path as {@code CommitAnnounce}, differing only in
 * what proves the advance.
 *
 * @param baseVersion the replica version the delta applies on top of (redundant with the encoded
 *                    delta's own base, carried flat so a replica can cheaply drop stale frames
 *                    without decoding the delta).
 */
public record ExternalDelta(
        RegionId region,
        SnapshotVersion baseVersion,
        Bytes encodedDelta,
        Bytes certificateBytes
) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any argument is null.
     */
    public ExternalDelta {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(baseVersion, "baseVersion");
        Objects.requireNonNull(encodedDelta, "encodedDelta");
        Objects.requireNonNull(certificateBytes, "certificateBytes");
    }
}
