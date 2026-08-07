package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.membership.WorldGrantGossip;
import dev.nodera.storage.WorldPermissionGrant;
import dev.nodera.storage.WorldPermissions;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * L-54 — carries world-permission grants across the mesh.
 *
 * <p>{@code WorldPermissionStore} persisted grants on the author's own machine and nowhere else, so
 * a co-hosting peer's permission set stayed author-local until it happened to fetch the world: an
 * operator promotion or a ban simply did not exist for the rest of the mesh, and a banned peer
 * could still be admitted by any peer that had not been told.
 *
 * <p>Nothing here is trusted. A {@link WorldPermissionGrant} is self-authenticating — it carries the
 * granter's public key and a signature over its own signed portion, and {@link WorldPermissions#apply}
 * re-verifies both the signature and the granter's <i>authority</i> against the world's author key,
 * refusing key-less operator grants, operator-minting by non-owners, and stale replays. This service
 * therefore only has to move bytes and hand them to that applier: a forged, re-signed or replayed
 * grant is refused identically on every honest peer, whoever relayed it.
 *
 * <p>Replay/gossip loop safety: {@code apply} rejects any grant that is not strictly newer than the
 * one already held for that subject, so a grant is re-broadcast at most once per peer per version
 * and the flood terminates.
 *
 * <p>Thread-context: {@link #onMessage} runs on the runtime's state thread and must not block —
 * verification is local and sends are single frames. Everything else is safe from any thread.
 */
public final class WorldGrantGossipService {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaWorker");

    /** The one flood loop this package shares; see {@link SignedGossipRelay}. */
    private final SignedGossipRelay mesh;

    /** worldIdHex → the permission set this node maintains for that world. */
    private final Map<String, WorldPermissions> worlds = new ConcurrentHashMap<>();

    /** Notified whenever a grant is accepted, so the holder can persist it. */
    private volatile GrantListener listener;

    /** Called with every grant this node accepts (its own and the mesh's). */
    @FunctionalInterface
    public interface GrantListener {
        /**
         * @param worldIdHex the world the grant belongs to.
         * @param grant      the accepted grant.
         */
        void onGrantAccepted(String worldIdHex, WorldPermissionGrant grant);
    }

    /**
     * @param self      this node's id (never gossiped to).
     * @param transport the peer transport.
     * @param members   the current session members to relay to.
     */
    public WorldGrantGossipService(NodeId self, PeerTransport transport,
                                   Supplier<List<PeerEntry>> members) {
        this.mesh = new SignedGossipRelay(self, transport, members);
    }

    /** Register the listener notified on every accepted grant; replaces any previous one. */
    public void onGrantAccepted(GrantListener listener) {
        this.listener = listener;
    }

    /**
     * Start maintaining permissions for a world, anchored to its author key.
     *
     * <p>The author key is the trust root: without it a peer cannot tell an authorised grant from a
     * fabricated one, so a world this node has no identity for is deliberately NOT tracked and its
     * gossip is dropped rather than half-applied.
     *
     * @param worldIdHex      the world.
     * @param author          the world author's node id.
     * @param authorPublicKey the world author's public key.
     * @return the permission set for that world.
     */
    public WorldPermissions track(String worldIdHex, NodeId author, Bytes authorPublicKey) {
        Objects.requireNonNull(worldIdHex, "worldIdHex");
        return worlds.computeIfAbsent(worldIdHex, hex ->
                new WorldPermissions(Bytes.fromHex(hex), author, authorPublicKey));
    }

    /** @return the permission set for a tracked world, if any. */
    public Optional<WorldPermissions> permissions(String worldIdHex) {
        return Optional.ofNullable(worlds.get(worldIdHex));
    }

    /**
     * Apply a grant locally and relay it to every other session member.
     *
     * <p>The local apply comes first on purpose: a grant this node itself rejects is never relayed,
     * so an invalid grant does not consume the mesh's bandwidth on its way to being refused
     * everywhere else.
     *
     * @param worldIdHex the world.
     * @param grant      the signed grant.
     * @return {@code true} if the grant was accepted here (and therefore relayed).
     */
    public boolean publish(String worldIdHex, WorldPermissionGrant grant) {
        if (!applyLocally(worldIdHex, grant)) {
            return false;
        }
        relay(worldIdHex, grant, null);
        return true;
    }

    /**
     * Handle one application-lane message; ignores anything that is not grant gossip.
     *
     * @Thread-context runtime state thread.
     */
    public void onMessage(PeerAddress from, NoderaMessage message) {
        if (!(message instanceof WorldGrantGossip gossip)) {
            return;
        }
        WorldPermissionGrant grant;
        try {
            grant = WorldPermissionGrant.decode(new CanonicalReader(gossip.encodedGrant()));
        } catch (RuntimeException malformed) {
            LOG.debug("discarding malformed grant gossip from {}: {}", from, malformed.getMessage());
            return;
        }
        String worldIdHex = gossip.worldId().toHex();
        if (!applyLocally(worldIdHex, grant)) {
            return; // invalid, unknown world, or not newer — never relayed onward
        }
        // Accepted and new here, so it may still be new to someone else: flood on, minus the sender.
        relay(worldIdHex, grant, from == null ? null : from.nodeId());
    }

    private boolean applyLocally(String worldIdHex, WorldPermissionGrant grant) {
        WorldPermissions permissions = worlds.get(worldIdHex);
        if (permissions == null || grant == null) {
            return false;
        }
        if (!permissions.apply(grant)) {
            return false;
        }
        LOG.info("Applied {} grant for {} in world {} (v{})",
                grant.role(), grant.subject().value(), WorldIds.shortId(worldIdHex),
                grant.grantVersion());
        GrantListener l = listener;
        if (l != null) {
            l.onGrantAccepted(worldIdHex, grant);
        }
        return true;
    }

    private void relay(String worldIdHex, WorldPermissionGrant grant, NodeId excluding) {
        CanonicalWriter w = new CanonicalWriter();
        grant.encode(w);
        byte[] frame = WireCodec.encode(
                new WorldGrantGossip(Bytes.fromHex(worldIdHex), w.toBytes()));
        mesh.flood(frame, excluding, "grant");
    }
}
