package dev.nodera.endpoint.lane;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who owns each region, as known to a node that owns none of them (minecraft L-80).
 *
 * <p>The field-of-view planner gives every region to the players' nodes, so a dedicated server's
 * log reads "no regions fall to this node" — and yet that server is the one process that sees the
 * block events, the mob spawns and the item drops. Everything captured there had nowhere to go: the
 * forwarding path started from a local replica's lease, and an observer has no replica.
 *
 * <p>The plan itself is the missing lookup, and the observer already computes it — it is the node
 * that broadcasts the plan to everyone else. This index simply keeps what it computed, so a capture
 * can ask "who owns this region?" and forward to the answer.
 *
 * <p><b>Not authority.</b> The index says where to send a proposal, never whether it is valid: the
 * receiving primary re-verifies the actor signature, the admission rule and the batch before
 * anything is proposed. A wrong entry costs a dropped forward, not a wrong world.
 *
 * @Thread-context concurrent; written from the ownership-planning thread, read from the server
 *                 thread on every captured action.
 */
public final class ObserverOwnership {

    private static final Map<RegionId, NodeId> PRIMARIES = new ConcurrentHashMap<>();

    private ObserverOwnership() {
    }

    /** Replace the index with the current plan. */
    public static void publish(Map<RegionId, NodeId> primaries) {
        PRIMARIES.clear();
        if (primaries != null) {
            primaries.forEach((region, primary) -> {
                if (region != null && primary != null) {
                    PRIMARIES.put(region, primary);
                }
            });
        }
    }

    /** @return the planned primary for {@code region}, or null when the plan does not cover it. */
    public static NodeId primaryOf(RegionId region) {
        return region == null ? null : PRIMARIES.get(region);
    }

    /** @return how many regions the current plan covers. */
    public static int size() {
        return PRIMARIES.size();
    }

    /** Session shutdown: the plan dies with the session that computed it. */
    public static void clear() {
        PRIMARIES.clear();
    }
}
