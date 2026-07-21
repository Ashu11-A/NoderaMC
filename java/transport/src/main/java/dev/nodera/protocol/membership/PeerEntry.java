package dev.nodera.protocol.membership;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;

import java.util.Objects;

/**
 * One member of a Nodera session's membership view (Phase 6 P2P continuity).
 *
 * <p>A {@code PeerEntry} is the wire-level description of a peer that other peers need in order
 * to (a) address it directly over a {@link dev.nodera.transport.PeerTransport} and (b) score it
 * in the deterministic gateway election. It pairs the canonical {@link NodeId} with the
 * transport {@code route} at which the peer accepts inbound connections (for the socket
 * transport this is a {@code "host:port"} string), the peer's self-declared
 * {@link NodeCapabilities}, and whether the peer is a bootstrap-capable node (the dedicated
 * server acting as a peer).
 *
 * <p>This is not itself a {@link dev.nodera.protocol.NoderaMessage}; it is a body component
 * encoded inline by {@code MessageCodec} inside {@link PeerJoin} / {@link MembershipUpdate}
 * (exactly as {@code WorkerLoad} is encoded inline inside {@code Heartbeat}), so it does not
 * carry its own type tag.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param nodeId       the peer's stable identifier.
 * @param route        the transport route at which the peer accepts inbound connections
 *                     (e.g. {@code "127.0.0.1:25566"}); never {@code null}, may be empty for a
 *                     peer that dials out only.
 * @param capabilities the peer's self-declared resource/capability profile (feeds election).
 * @param bootstrap    {@code true} for a bootstrap-capable peer (the preferred gateway while
 *                     it is alive); {@code false} for an ordinary player peer.
 */
public record PeerEntry(NodeId nodeId, String route, NodeCapabilities capabilities, boolean bootstrap) {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code nodeId}, {@code route}, or {@code capabilities}
     *                                  is null.
     */
    public PeerEntry {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(capabilities, "capabilities");
    }
}
