package dev.nodera.protocol.membership;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;

/**
 * Advisory progress for one region, carried inline by {@link SessionKeepAlive} (Task 25).
 *
 * <p>This operational signal is used to observe tick lag and is not consensus truth. The
 * {@code epoch} and {@code primary} identify the assignment to which {@code lastAppliedTick}
 * applies, so receivers can discard stale reports after a handoff.
 *
 * <p>This is not itself a {@link dev.nodera.protocol.NoderaMessage}; it is an untagged body
 * component encoded inline by {@code MessageCodec} inside {@link SessionKeepAlive}.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param region          the region whose progress is reported.
 * @param epoch           the assignment epoch for the report.
 * @param primary         the region primary for that epoch.
 * @param lastAppliedTick the sender's last applied tick for the region; non-negative.
 */
public record RegionProgress(
        RegionId region,
        RegionEpoch epoch,
        NodeId primary,
        long lastAppliedTick
) {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if a reference is null or {@code lastAppliedTick} is
     *                                  negative.
     */
    public RegionProgress {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        if (epoch == null) {
            throw new IllegalArgumentException("epoch must not be null");
        }
        if (primary == null) {
            throw new IllegalArgumentException("primary must not be null");
        }
        if (lastAppliedTick < 0) {
            throw new IllegalArgumentException("lastAppliedTick must be non-negative");
        }
    }
}
