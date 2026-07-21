package dev.nodera.diagnostics.model;

import dev.nodera.core.identity.NodeId;

import java.util.List;

/**
 * Session membership as of a sample (Task 18).
 *
 * @param epoch      the membership/gateway epoch.
 * @param gatewayId  the elected gateway, or {@code null} before the first view is learned.
 * @param selfGateway {@code true} if this peer is the current gateway.
 * @param memberCount total members in the view.
 * @param selfRole   a short label for this peer's role ({@code "bootstrap"} / {@code "peer"} / …).
 * @param peers      the per-peer links, sorted by {@link NodeId} for a stable table.
 * @Thread-context immutable record, any thread.
 */
public record SessionInfo(
        long epoch,
        NodeId gatewayId,
        boolean selfGateway,
        int memberCount,
        String selfRole,
        List<PeerLink> peers) {

    /** Compact constructor copies {@code peers} into an immutable list. */
    public SessionInfo {
        peers = peers == null ? List.of() : List.copyOf(peers);
    }
}
