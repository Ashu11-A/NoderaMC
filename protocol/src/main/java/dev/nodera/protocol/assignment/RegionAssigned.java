package dev.nodera.protocol.assignment;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionReplicaRole;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.protocol.NoderaMessage;

import java.util.List;
import java.util.Objects;

/**
 * Server→client region assignment (Task 4). Tells a worker it now holds a replica of
 * {@code region} at {@code epoch} in the given {@link RegionReplicaRole}, anchored at
 * {@code snapshotVersion}, with a committee lease expiring at {@code leaseExpiryTick}, naming
 * the full committee by {@link NodeId}.
 *
 * <p>The committee list is written in the order given (it is NOT a set keyed for canonical
 * sorting here — the assignment is a transport-time directive, not a hashed value); the codec
 * copies it into an unmodifiable list for safety.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param region           assigned region.
 * @param epoch            region epoch at assignment time.
 * @param role             PRIMARY or VALIDATOR.
 * @param snapshotVersion  snapshot the replica should boot from.
 * @param leaseExpiryTick  tick at which the lease expires unless renewed.
 * @param committee        full committee (primary + validators), in assigned order.
 */
public record RegionAssigned(
        RegionId region,
        RegionEpoch epoch,
        RegionReplicaRole role,
        SnapshotVersion snapshotVersion,
        long leaseExpiryTick,
        List<NodeId> committee
) implements NoderaMessage {

    /**
     * Compact constructor; defensive-copies {@code committee} into an unmodifiable list.
     *
     * @throws IllegalArgumentException if any argument is null.
     */
    public RegionAssigned {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(snapshotVersion, "snapshotVersion");
        Objects.requireNonNull(committee, "committee");
        committee = List.copyOf(committee);
    }
}
