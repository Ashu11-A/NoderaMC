package dev.nodera.peer;

import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.membership.PeerEntry;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * An immutable snapshot of a peer's view of the session (Phase 6 P2P continuity).
 *
 * <p>Carries the current membership {@code epoch}, the elected {@code gatewayId}, and the member
 * set (sorted by {@link NodeId} for a stable, deterministic order). {@link PeerRuntime} republishes
 * a fresh {@code SessionView} after every membership or gateway change; callers (tests, the mod's
 * status command) read the latest via {@link PeerRuntime#sessionView()}.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param epoch     the membership/gateway epoch this view reflects.
 * @param gatewayId the elected gateway at {@code epoch}, or {@code null} before the first view is
 *                  learned by a freshly-joined peer.
 * @param members   the member set, sorted ascending by node UUID.
 */
public record SessionView(long epoch, NodeId gatewayId, List<PeerEntry> members) {

    private static final Comparator<PeerEntry> BY_NODE_ID =
            Comparator.comparing(e -> e.nodeId().value());

    /**
     * Compact constructor; copies and sorts {@code members} into an immutable, order-stable list.
     *
     * @throws IllegalArgumentException if {@code members} is null.
     */
    public SessionView {
        Objects.requireNonNull(members, "members");
        members = members.stream().sorted(BY_NODE_ID).toList();
    }

    /** @return {@code true} if a peer with {@code id} is a member of this view. */
    public boolean contains(NodeId id) {
        return members.stream().anyMatch(e -> e.nodeId().equals(id));
    }

    /** @return the number of members. */
    public int size() {
        return members.size();
    }

    /** @return the sorted list of member node ids. */
    public List<NodeId> memberIds() {
        return members.stream().map(PeerEntry::nodeId).toList();
    }
}
