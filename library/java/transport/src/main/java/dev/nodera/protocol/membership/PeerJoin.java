package dev.nodera.protocol.membership;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * A peer announces itself to the session (Phase 6 P2P continuity).
 *
 * <p>Sent by a joining peer to the bootstrap peer (or to any already-connected member) as the
 * first application message after the transport connects. The recipient adds the joiner to its
 * membership table and replies with a {@link MembershipUpdate} carrying the full current view,
 * then gossips the new member to the rest of the mesh. The joiner's {@code listenRoute} is the
 * route other peers dial to reach it directly, so that the peer mesh survives the loss of the
 * bootstrap peer.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param joiner       the joining peer's stable id.
 * @param listenRoute  the transport route at which the joiner accepts inbound connections.
 * @param capabilities the joiner's self-declared capability profile.
 * @param bootstrap    {@code true} if the joiner is itself a bootstrap-capable node.
 * @param publicKey    the joiner's Ed25519 public key, or empty when it publishes none. Carried
 *                     here so the whole mesh learns it through ordinary membership gossip — the
 *                     prerequisite for giving a headless peer a real committee seat.
 * @param clientVersion the joiner's self-declared client agent, e.g. {@code "NoderaMC 0.1.0"}.
 *                     Gossiped onward in the joiner's {@link PeerEntry} so the whole mesh — and the
 *                     companion app's peer list — can report what each member runs. Descriptive
 *                     only: never an authorization or compatibility input.
 */
public record PeerJoin(NodeId joiner, String listenRoute, NodeCapabilities capabilities, boolean bootstrap,
                      Bytes publicKey, String clientVersion) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code joiner}, {@code listenRoute}, or
     *                                  {@code capabilities} is null.
     */
    public PeerJoin {
        Objects.requireNonNull(joiner, "joiner");
        Objects.requireNonNull(listenRoute, "listenRoute");
        Objects.requireNonNull(capabilities, "capabilities");
        publicKey = publicKey == null ? Bytes.empty() : publicKey;
        clientVersion = clientVersion == null ? "" : clientVersion;
    }

    /** Back-compat constructor for callers with no key to publish (tests, probe-only meshes). */
    public PeerJoin(NodeId joiner, String listenRoute, NodeCapabilities capabilities, boolean bootstrap) {
        this(joiner, listenRoute, capabilities, bootstrap, Bytes.empty(), "");
    }
}
