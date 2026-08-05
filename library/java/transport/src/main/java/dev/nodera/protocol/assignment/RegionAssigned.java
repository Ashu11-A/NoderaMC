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
 * <h2>The base a seat is taken on (v2)</h2>
 *
 * <p>A member had to derive its own starting state, and derived it as an <b>all-air</b> region —
 * which was only workable because every member derived the same nothing, so their first
 * {@code prevRoot} comparison lined up without any transfer. The cost was that the validated lane
 * held a world made of the edits it had happened to witness rather than the world the players were
 * standing in, and a region seeded from it carried air.
 *
 * <p>v2 names the base instead: {@code baseIndexRoot} is the chunk-index root of the region state
 * the assigner is seating this committee on. A member fetches that content before it activates, and
 * refuses the seat if it cannot — which is the honest answer, because a member that activates on
 * the wrong base disagrees with every proposal it will ever see and calls it divergence.
 *
 * <p>A v1 assignment carries no root. Its meaning is unchanged and it is still accepted: an older
 * assigner is seating a committee the old way, and refusing would make a mixed-release session
 * unable to validate anything at all.
 *
 * @param region           assigned region.
 * @param epoch            region epoch at assignment time.
 * @param role             PRIMARY or VALIDATOR.
 * @param snapshotVersion  snapshot the replica should boot from.
 * @param leaseExpiryTick  tick at which the lease expires unless renewed.
 * @param committee        full committee (primary + validators), in assigned order.
 * @param baseIndexRoot    v2: the chunk-index root of the state to activate on, or {@code null} for
 *                         a v1 assignment.
 * @param bodyVersion      the frame version this was decoded from, so a re-encode is byte-identical.
 */
public record RegionAssigned(
        RegionId region,
        RegionEpoch epoch,
        RegionReplicaRole role,
        SnapshotVersion snapshotVersion,
        long leaseExpiryTick,
        List<NodeId> committee,
        dev.nodera.core.Bytes baseIndexRoot,
        int bodyVersion
) implements NoderaMessage {

    /** The original frame: no base named, every member derives the same nothing. */
    public static final int V1 = 1;

    /** Names the base a seat is taken on. */
    public static final int V2_BASE_INDEX = 2;

    /** v1 shape, for callers that have no base to name. */
    public RegionAssigned(RegionId region, RegionEpoch epoch, RegionReplicaRole role,
                          SnapshotVersion snapshotVersion, long leaseExpiryTick,
                          List<NodeId> committee) {
        this(region, epoch, role, snapshotVersion, leaseExpiryTick, committee, null, V1);
    }

    /** v2 shape: name the base this committee is being seated on. */
    public RegionAssigned(RegionId region, RegionEpoch epoch, RegionReplicaRole role,
                          SnapshotVersion snapshotVersion, long leaseExpiryTick,
                          List<NodeId> committee, dev.nodera.core.Bytes baseIndexRoot) {
        this(region, epoch, role, snapshotVersion, leaseExpiryTick, committee, baseIndexRoot,
                baseIndexRoot == null ? V1 : V2_BASE_INDEX);
    }

    /**
     * Compact constructor; defensive-copies {@code committee} into an unmodifiable list.
     *
     * @throws IllegalArgumentException if any argument is null, or the body version and the
     *                                  presence of a base root disagree.
     */
    public RegionAssigned {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(snapshotVersion, "snapshotVersion");
        Objects.requireNonNull(committee, "committee");
        if (bodyVersion < V1 || bodyVersion > V2_BASE_INDEX) {
            throw new IllegalArgumentException("unsupported RegionAssigned body version "
                    + bodyVersion);
        }
        if (bodyVersion >= V2_BASE_INDEX && baseIndexRoot == null) {
            throw new IllegalArgumentException("a v2 assignment must name its base index root");
        }
        if (bodyVersion < V2_BASE_INDEX && baseIndexRoot != null) {
            throw new IllegalArgumentException(
                    "a v1 assignment cannot carry a base index root");
        }
        committee = List.copyOf(committee);
    }

    /** @return whether this assignment names the state its committee is being seated on. */
    public boolean namesBase() {
        return baseIndexRoot != null;
    }
}
