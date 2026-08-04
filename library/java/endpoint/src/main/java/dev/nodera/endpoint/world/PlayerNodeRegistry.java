package dev.nodera.endpoint.world;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The session's player↔node association (the no-host ownership prerequisite): which peer node each
 * connected player <b>is</b>. A joining client announces its own peer identity
 * ({@code NoderaNodeAnnouncePayload}) after dialing the mesh; the region-ownership plan then
 * assigns every player's node its own FOV region set, and committee traffic dials these routes.
 * A player without an announced node simply is not an owner yet — ownership follows the
 * announcement, never a privileged default.
 *
 * <p>Thread-context: written from the payload handler (main thread), read from anywhere; backed by
 * a concurrent map. Cleared when the session's server stops.
 */
public final class PlayerNodeRegistry {

    /**
     * One player's peer node: identity id, Ed25519 public key, dialable P2P route — and, separately,
     * the identity whose <b>authority</b> this session carries.
     *
     * <h2>Why there are two identities here</h2>
     *
     * <p>{@code nodeId}/{@code publicKey} are the session's own: a per-session transport keypair the
     * client generates at join and proves possession of over the announce challenge. They are what
     * the mesh dials and what the transport authenticates, and they are correct for both of those
     * jobs.
     *
     * <p>They are the wrong thing to evaluate <b>permissions</b> against, because a world's author
     * and every grant it has issued are anchored to a persistent key living in the player's
     * always-on worker. A session key is by construction a key no world has ever heard of, so
     * evaluating it answered {@code MEMBER} for everybody — including a world's own creator, who was
     * then de-opped from their own world.
     *
     * <p>So a session may additionally present a {@code SessionDelegation}: its worker's signature
     * that this session key speaks in the worker's name, for this world, until a stated instant.
     * When one verifies, {@code authorityNodeId}/{@code authorityPublicKey} hold the worker's
     * identity; with no delegation they hold the session's own, which is exactly the behaviour that
     * existed before delegations did.
     *
     * @param nodeId             the session peer's NodeId.
     * @param publicKey          the session peer's proven Ed25519 public key.
     * @param route              the session peer's dialable {@code host:port} P2P route.
     * @param authorityNodeId    the NodeId permissions are evaluated against.
     * @param authorityPublicKey the public key permissions are evaluated against.
     */
    public record PlayerNode(NodeId nodeId, Bytes publicKey, String route,
                             NodeId authorityNodeId, Bytes authorityPublicKey) {
        public PlayerNode {
            if (nodeId == null || publicKey == null || route == null || route.isBlank()) {
                throw new IllegalArgumentException("player node fields must not be null/blank");
            }
            if (authorityNodeId == null || authorityPublicKey == null
                    || authorityPublicKey.isEmpty()) {
                throw new IllegalArgumentException("a player node always has an authority identity");
            }
        }

        /** A session speaking only for itself — no delegation presented, or none that verified. */
        public PlayerNode(NodeId nodeId, Bytes publicKey, String route) {
            this(nodeId, publicKey, route, nodeId, publicKey);
        }

        /** @return whether this session speaks for a persistent identity other than its own. */
        public boolean isDelegated() {
            return !authorityNodeId.equals(nodeId);
        }
    }

    private static final Map<UUID, PlayerNode> NODES = new ConcurrentHashMap<>();

    private PlayerNodeRegistry() {
    }

    /** Record (or refresh) one player's announced peer node. */
    public static void announce(UUID player, PlayerNode node) {
        NODES.put(player, node);
    }

    /** Forget one player (logout). */
    public static void forget(UUID player) {
        NODES.remove(player);
    }

    /** @return the player's announced node, or {@code null}. */
    public static PlayerNode nodeOf(UUID player) {
        return NODES.get(player);
    }

    /** @return an insertion-stable snapshot of every announced player node. */
    public static Map<UUID, PlayerNode> snapshot() {
        return new LinkedHashMap<>(NODES);
    }

    /** Drop everything (server stopped). */
    public static void clear() {
        NODES.clear();
    }
}
