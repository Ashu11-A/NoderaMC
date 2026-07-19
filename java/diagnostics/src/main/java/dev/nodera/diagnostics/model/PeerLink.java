package dev.nodera.diagnostics.model;

import dev.nodera.core.identity.NodeId;

/**
 * One peer as seen by this peer's session view, for the {@code /nodera peers} table (Task 18).
 *
 * @param id                 the peer's stable id.
 * @param route              the transport route at which it accepts inbound ({@code "host:port"}).
 * @param bootstrap          {@code true} if the peer is bootstrap-capable (the dedicated server).
 * @param role               a short role label (e.g. {@code "gateway"}, {@code "bootstrap"}, {@code "peer"}).
 * @param lastSeenAgoMillis  how long ago we last heard from it (ms); {@code -1} if never / self.
 * @param keepAlives         how many keep-alives we have received from it.
 * @param up                 {@code true} if the link is currently considered alive.
 * @Thread-context immutable record, any thread.
 */
public record PeerLink(
        NodeId id,
        String route,
        boolean bootstrap,
        String role,
        long lastSeenAgoMillis,
        long keepAlives,
        boolean up) {
}
