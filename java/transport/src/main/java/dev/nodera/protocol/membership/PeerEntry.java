package dev.nodera.protocol.membership;

import dev.nodera.core.Bytes;
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
 * @param publicKey    the peer's Ed25519 public key, or empty when unknown.
 *                     Membership is the one place every member learns every other member's key,
 *                     which is what lets a headless worker be a real committee validator: the
 *                     mod verifies a worker's {@code ValidationVote} and the worker verifies the
 *                     primary's {@code RegionProposal}, neither having ever exchanged an
 *                     application-level identity message. Empty is tolerated so a peer that
 *                     predates this field still joins — it simply cannot be given a committee
 *                     seat.
 * @param clientVersion the peer's self-declared client agent, e.g.
 *                     {@code "NoderaMC 0.1.0"} ({@link dev.nodera.core.NoderaConstants#CLIENT_AGENT}).
 *                     Empty when the peer publishes none. Purely descriptive — it populates the
 *                     "Client" column of the companion app's peer list and the {@code /nodera}
 *                     diagnostics, and is <b>never</b> an authorization or compatibility input:
 *                     a peer can claim anything, so nothing may branch on it.
 */
public record PeerEntry(NodeId nodeId, String route, NodeCapabilities capabilities, boolean bootstrap,
                        Bytes publicKey, String clientVersion) {

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
        publicKey = publicKey == null ? Bytes.empty() : publicKey;
        clientVersion = clientVersion == null ? "" : clientVersion;
    }

    /** Back-compat constructor for callers that publish a key but no client agent. */
    public PeerEntry(NodeId nodeId, String route, NodeCapabilities capabilities, boolean bootstrap,
                     Bytes publicKey) {
        this(nodeId, route, capabilities, bootstrap, publicKey, "");
    }

    /** Back-compat constructor for callers with no key to publish (tests, probe-only meshes). */
    public PeerEntry(NodeId nodeId, String route, NodeCapabilities capabilities, boolean bootstrap) {
        this(nodeId, route, capabilities, bootstrap, Bytes.empty(), "");
    }

    /** @return {@code true} if this entry carries a usable key (committee-seat eligible). */
    public boolean hasPublicKey() {
        return publicKey != null && !publicKey.isEmpty();
    }
}
