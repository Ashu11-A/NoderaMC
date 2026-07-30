package dev.nodera.transport;

import dev.nodera.core.identity.NodeId;

import java.util.Objects;

/**
 * Opaque routing handle for a Nodera peer on a {@link PeerTransport} (Task 4 transport-api).
 *
 * <p>A {@code PeerAddress} pairs the canonical {@link NodeId} with a transport-specific
 * {@code route} string. The route's meaning is owned entirely by the concrete transport
 * implementation: for the NeoForge relay transport it is the literal {@code "server"} while
 * the client speaks directly to the integrated server, or a relay token while routing to
 * another client through the server. For a future libp2p transport it may be a multiaddr.
 *
 * <p>The transport layer never interprets {@code route} bytes; it is an opaque, comparable
 * string the transport uses to look up its own internal connection state. Higher layers
 * (coordinator, vote collector) key peers by {@link NodeId} alone.
 *
 * <p>{@code nodeId} MAY be {@code null}: a client addressing <i>the server</i> before the
 * configuration handshake completes does not yet know the server's {@code NodeId}, so
 * {@link #server()} returns an address whose {@code nodeId} is null and whose {@code route}
 * is {@code "server"}. The transport routes by {@code route} alone in that case; once the
 * handshake completes, callers switch to {@link #server(NodeId)} or {@link #of(NodeId, String)}
 * with the learned id. Peer addresses (route ≠ {@code "server"}) should always carry a non-null
 * {@code nodeId}.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param nodeId stable peer identifier, or {@code null} for a pre-handshake server address.
 * @param route  transport-specific routing hint (never {@code null}).
 */
public record PeerAddress(NodeId nodeId, String route) {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code route} is null.
     */
    public PeerAddress {
        Objects.requireNonNull(route, "route");
    }

    /**
     * The canonical address of the integrated server endpoint, addressed by route alone.
     * Used by a client before it has learned the server's {@link NodeId} through the
     * configuration handshake; {@code nodeId()} will be {@code null}. Once the handshake
     * completes, prefer {@link #server(NodeId)}.
     *
     * @return a peer address with {@code nodeId=null} and route {@code "server"}.
     * @Thread-context any thread.
     */
    public static PeerAddress server() {
        return new PeerAddress(null, "server");
    }

    /**
     * Address the server by both its {@link NodeId} and the well-known route
     * {@code "server"}. Used once the server's identity is known (post-handshake).
     *
     * @param serverNodeId the server's stable node identifier.
     * @return a peer address with the given {@code nodeId} and route {@code "server"}.
     * @Thread-context any thread.
     */
    public static PeerAddress server(NodeId serverNodeId) {
        return new PeerAddress(serverNodeId, "server");
    }

    /**
     * Generic factory; equivalent to the canonical constructor. Provided for readability at
     * call sites that build addresses from arbitrary routes (e.g. relay tokens issued by the
     * server).
     *
     * @param nodeId stable peer identifier (may be {@code null} for a pre-handshake server).
     * @param route  transport-specific routing hint.
     * @return a new {@link PeerAddress}.
     * @Thread-context any thread.
     */
    public static PeerAddress of(NodeId nodeId, String route) {
        return new PeerAddress(nodeId, route);
    }
}
