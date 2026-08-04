package dev.nodera.endpoint.lane;

import dev.nodera.core.identity.NodeId;
import dev.nodera.peer.GatewayElection;
import dev.nodera.protocol.membership.PeerEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Who takes over a world when the player hosting it leaves.
 *
 * <h2>The gap this fills</h2>
 *
 * <p>There was no answer to this question anywhere in the codebase. {@code CommitteeFailover}
 * promotes a <i>region</i> primary and {@code GatewayElection} picks a <i>mesh</i> coordinator;
 * neither has anything to do with who hosts a Minecraft world. The client-side recovery path
 * consulted only local state, so every remaining player independently fetched the world's archive,
 * unpacked it into their own save directory, opened it and re-shared it.
 *
 * <p>The result was not a handover. One world became <i>N</i> divergent worlds, all announcing the
 * same world id, each with its own game endpoint — which is what a live session saw as "the world
 * was cloned into everyone's singleplayer list" and "two versions exist and the owner cannot enter
 * the new one".
 *
 * <p>So exactly one peer takes over, and every peer works out which one <b>without asking anybody</b>:
 * the choice is a pure function of the membership set and the departed host, so all of them reach the
 * same answer at the same moment with no round trip and nothing to disagree about. That is the same
 * property {@link GatewayElection} already relies on, and this reuses its ordering rather than
 * inventing a second one.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class HostSuccession {

    private HostSuccession() {
    }

    /**
     * Elect the single peer that should take over hosting.
     *
     * @param members  the session's current membership, as this node sees it.
     * @param departed the host that is going away; excluded from the election.
     * @param epoch    mixed into the ordering so a second departure cannot re-elect the same peer
     *                 by construction. Every peer must pass the same value — derive it from
     *                 something they all share, never from a local clock.
     * @return the elected successor, or empty when no eligible member remains.
     * @Thread-context any thread.
     */
    public static Optional<NodeId> elect(Collection<PeerEntry> members, NodeId departed,
                                         long epoch) {
        List<PeerEntry> eligible = new ArrayList<>();
        if (members != null) {
            for (PeerEntry entry : members) {
                if (entry == null || entry.nodeId() == null) {
                    continue;
                }
                if (departed != null && departed.equals(entry.nodeId())) {
                    continue;
                }
                // A peer nobody can dial cannot host a world for anybody. Excluding it here rather
                // than discovering it later is what stops an election from succeeding and then
                // producing a world with no reachable endpoint.
                if (entry.route() == null || entry.route().isEmpty()) {
                    continue;
                }
                eligible.add(entry);
            }
        }
        if (eligible.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(GatewayElection.elect(eligible, epoch));
    }

    /**
     * Whether {@code self} is the peer that should take over.
     *
     * <p>Answers {@code false} when there is no eligible member at all — including when this node
     * cannot see the membership. A node that cannot tell whether it won must not act as though it
     * did, because the cost of two winners is two worlds and the cost of none is a wait.
     *
     * @param self     this node.
     * @param members  the session's current membership.
     * @param departed the departing host.
     * @param epoch    the shared epoch; see {@link #elect}.
     * @return whether this node should open the world.
     * @Thread-context any thread.
     */
    public static boolean isSuccessor(NodeId self, Collection<PeerEntry> members, NodeId departed,
                                      long epoch) {
        if (self == null) {
            return false;
        }
        return elect(members, departed, epoch).map(self::equals).orElse(false);
    }

    /**
     * The epoch every peer derives identically from the world they are in.
     *
     * <p>Not a clock and not a counter: two peers reading their own clocks would elect differently,
     * and a counter would need somewhere to live. The world id is the one number every member of a
     * session already agrees on.
     *
     * @param worldIdHex the world's id, hex-encoded.
     * @return the epoch to elect under.
     * @Thread-context any thread.
     */
    public static long epochFor(String worldIdHex) {
        if (worldIdHex == null || worldIdHex.isBlank()) {
            return 0L;
        }
        return worldIdHex.trim().toLowerCase(java.util.Locale.ROOT).hashCode() & 0xFFFFFFFFL;
    }
}
