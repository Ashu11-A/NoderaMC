package dev.nodera.protocol.assignment;

import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * Server→client notice that a previously-held region assignment is revoked (Task 4).
 *
 * <p>Causes include lease expiry, foreign-mutation interference, committee reshuffle, or
 * operator intervention. The {@code reason} is a human-readable diagnostic string (never
 * parsed by code) suitable for logging or surfacing to the client worker.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param region  region whose assignment is revoked.
 * @param epoch   region epoch at revocation time.
 * @param reason  free-form diagnostic; never null, never parsed.
 */
public record RegionRevoked(RegionId region, RegionEpoch epoch, String reason)
        implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any argument is null.
     */
    public RegionRevoked {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(reason, "reason");
    }
}
