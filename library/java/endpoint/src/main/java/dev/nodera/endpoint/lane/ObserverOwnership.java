package dev.nodera.endpoint.lane;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import dev.nodera.diagnostics.state.OwnershipState;

import java.util.List;

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

    /**
     * One region's planned committee.
     *
     * @param primary    the node that runs the region.
     * @param validators the nodes that re-execute and vote on it.
     */
    public record Seats(NodeId primary, List<NodeId> validators) {

        /** Compact constructor. */
        public Seats {
            validators = validators == null ? List.of() : List.copyOf(validators);
        }

        /** @return this node's relationship to the region: OWNED, VALIDATING, or FOREIGN. */
        public OwnershipState stateFor(NodeId node) {
            if (node == null) {
                return OwnershipState.FOREIGN;
            }
            if (node.equals(primary)) {
                return OwnershipState.OWNED;
            }
            return validators.contains(node) ? OwnershipState.VALIDATING : OwnershipState.FOREIGN;
        }
    }

    private static final Map<RegionId, Seats> COMMITTEES = new ConcurrentHashMap<>();

    private ObserverOwnership() {
    }

    /** Replace the index with the current plan. */
    public static void publish(Map<RegionId, Seats> committees) {
        COMMITTEES.clear();
        if (committees != null) {
            committees.forEach((region, seats) -> {
                if (region != null && seats != null && seats.primary() != null) {
                    COMMITTEES.put(region, seats);
                }
            });
        }
    }

    /** @return the planned primary for {@code region}, or null when the plan does not cover it. */
    public static NodeId primaryOf(RegionId region) {
        Seats seats = region == null ? null : COMMITTEES.get(region);
        return seats == null ? null : seats.primary();
    }

    /**
     * What {@code node}'s relationship to {@code region} is, according to the plan this session
     * broadcast.
     *
     * <h2>Why this is asked of the plan and not of a lane</h2>
     *
     * <p>A lane knows only its own seats. That is the right answer to "what am I responsible for"
     * and the wrong answer to "what is that player over there responsible for" — and the boss bar
     * asks the second question, once per online player, from the host. Asking a lane instead meant
     * every player's bar was rendered from the <b>host's</b> seats evaluated at that player's
     * coordinates, so a joiner standing on ground its own node owned read {@code FOREIGN} forever
     * while the host player read {@code OWNED}, and after a teleport both read {@code OWNED} because
     * both were one node's answer printed on two screens.
     *
     * <p>The plan is the only thing that knows every node's seats, and the node that broadcasts it
     * already computes it. This index just keeps it.
     *
     * @param region the region to ask about.
     * @param node   the node to ask about.
     * @return {@link OwnershipState#UNASSIGNED} when the plan does not cover the region at all
     *         (nothing is delegated there yet), otherwise OWNED / VALIDATING / FOREIGN.
     * @Thread-context any thread.
     */
    public static OwnershipState stateFor(RegionId region, NodeId node) {
        Seats seats = region == null ? null : COMMITTEES.get(region);
        return seats == null ? OwnershipState.UNASSIGNED : seats.stateFor(node);
    }

    /** @return whether a plan has been published at all — false means "ask something else". */
    public static boolean hasPlan() {
        return !COMMITTEES.isEmpty();
    }

    /** @return how many regions the current plan covers. */
    public static int size() {
        return COMMITTEES.size();
    }

    /** Session shutdown: the plan dies with the session that computed it. */
    public static void clear() {
        COMMITTEES.clear();
    }
}
