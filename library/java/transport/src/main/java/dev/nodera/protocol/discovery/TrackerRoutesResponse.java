package dev.nodera.protocol.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;

import java.util.List;
import java.util.Objects;

/**
 * A tracker's answer to a {@link TrackerRoutesQuery} (wire tag 50): the full claimed dial-route
 * list of every live peer of one world, in the peers' own announce preference order.
 *
 * <p>Route strings are free-form dial claims. Two forms exist today: a bare {@code host:port}
 * (the P2P socket lane) and {@code "mc/host:port"} (the host's Minecraft game endpoint, present
 * only while the hosting player's game is open). Consumers skip forms they do not understand.
 *
 * <p>An unknown world answers with an empty peer list — indistinguishable from an empty world,
 * exactly like {@link TrackerResponse}.
 *
 * @param genesisHash the queried world.
 * @param peers       one entry per live peer, each with its full claimed route list.
 * @Thread-context immutable record, safe for any thread.
 */
public record TrackerRoutesResponse(
        Bytes genesisHash,
        List<PeerRoutes> peers
) implements NoderaMessage {

    public TrackerRoutesResponse {
        Objects.requireNonNull(genesisHash, "genesisHash");
        Objects.requireNonNull(peers, "peers");
        peers = List.copyOf(peers);
    }

    /**
     * One live peer's claimed dial routes.
     *
     * @param peer   the peer's node id.
     * @param routes its claimed dial routes, in the peer's own preference order.
     */
    public record PeerRoutes(NodeId peer, List<String> routes) {
        public PeerRoutes {
            Objects.requireNonNull(peer, "peer");
            Objects.requireNonNull(routes, "routes");
            routes = List.copyOf(routes);
        }
    }

    /**
     * The first route of any live peer carrying the given prefix, with the prefix stripped.
     *
     * @param prefix the route-form prefix (e.g. {@code "mc/"}).
     * @return the first matching route's remainder, or empty when no peer claims that form.
     */
    public java.util.Optional<String> firstRouteWithPrefix(String prefix) {
        for (PeerRoutes peer : peers) {
            for (String route : peer.routes()) {
                if (route.startsWith(prefix)) {
                    return java.util.Optional.of(route.substring(prefix.length()));
                }
            }
        }
        return java.util.Optional.empty();
    }
}
