package dev.nodera.protocol.membership;

import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;

import java.util.List;
import java.util.Objects;

/**
 * The full membership view of a Nodera session at a given epoch (Phase 6 P2P continuity).
 *
 * <p>Sent (a) by a member in reply to a {@link PeerJoin} to bring the joiner up to date, and
 * (b) as a gossip broadcast whenever the membership changes (a peer joins or leaves). A peer
 * that receives an update with a <b>higher</b> {@code epoch} than its own adopts the carried
 * {@code gatewayId} and member set; ties on {@code epoch} are merged (union of members). The
 * epoch is the monotonic session-view counter that is bumped on every gateway election, so
 * "highest epoch wins" gives every peer a consistent, converging view without a central
 * coordinator.
 *
 * <p>Thread-context: immutable record, safe for any thread; {@code members} is copied
 * defensively.
 *
 * @param epoch     monotonic membership/gateway epoch (bumped on every election).
 * @param gatewayId the current session gateway (coordinator) at {@code epoch}.
 * @param members   the full member set at {@code epoch} (order not significant).
 */
public record MembershipUpdate(long epoch, NodeId gatewayId, List<PeerEntry> members)
        implements NoderaMessage {

    /**
     * Compact constructor; copies {@code members} into an immutable list.
     *
     * @throws IllegalArgumentException if {@code gatewayId} or {@code members} is null.
     */
    public MembershipUpdate {
        Objects.requireNonNull(gatewayId, "gatewayId");
        Objects.requireNonNull(members, "members");
        members = List.copyOf(members);
    }
}
