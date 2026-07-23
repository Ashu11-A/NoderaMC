package dev.nodera.mod.common;

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

    /** One player's peer node: identity id, Ed25519 public key, and dialable P2P route. */
    public record PlayerNode(NodeId nodeId, Bytes publicKey, String route) {
        public PlayerNode {
            if (nodeId == null || publicKey == null || route == null || route.isBlank()) {
                throw new IllegalArgumentException("player node fields must not be null/blank");
            }
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
