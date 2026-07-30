package dev.nodera.protocol.assignment;

import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * Server→client lease renewal notice (Task 4). Extends the expiry of an existing assignment
 * without changing the committee or snapshot version; the worker simply keeps its current
 * replica alive until the new tick.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param region         region whose lease is renewed.
 * @param epoch          region epoch; must match the worker's current assignment.
 * @param newExpiryTick  new tick at which the lease will expire.
 */
public record LeaseRenewal(RegionId region, RegionEpoch epoch, long newExpiryTick)
        implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code region} or {@code epoch} is null.
     */
    public LeaseRenewal {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(epoch, "epoch");
    }
}
