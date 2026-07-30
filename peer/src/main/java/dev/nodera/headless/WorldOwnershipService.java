package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.membership.WorldOwnershipGossip;
import dev.nodera.storage.WorldOwnership;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import dev.nodera.transport.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Carries world ownership across the mesh: who administers each world every peer here serves.
 *
 * <h2>What it makes possible</h2>
 *
 * <p>A peer supporting somebody else's world holds that world's bytes and nothing else. It has no
 * way to tell an administrative decision about that world from an assertion by whoever spoke last —
 * and no way to show a player who actually runs the world they are about to join. This lane moves
 * the doubly-signed {@link WorldOwnership} claim to every peer serving the world, so all of them can
 * verify it independently and none of them has to be trusted.
 *
 * <h2>First claim wins, and only if it verifies</h2>
 *
 * <p>A world has exactly one administrative key, minted once by its author. So the rule here is
 * blunt: the first claim that verifies for a world is kept, and a later, different claim for that
 * same world is <b>refused and logged</b> — never merged, never preferred for being newer. Any other
 * rule would let a peer take a world over by shouting later than its owner. A byte-identical repeat
 * is accepted silently, because that is just the same claim arriving twice.
 *
 * <p>Nothing here is trusted. Verification is {@link WorldOwnership#verify()} — both the world key's
 * signature and the owner node key's signature over bytes that name the world, both public keys and
 * the owner — so a relayed claim is exactly as strong as one received directly, and a forged one is
 * refused identically everywhere.
 *
 * <p>Gossip termination: a claim is relayed only on the exchange that first accepts it, so each peer
 * forwards a given world's claim at most once and the flood dies out.
 *
 * @Thread-context {@link #onMessage} runs on the runtime's state thread and must not block —
 *                 verification is local and sends are single frames. Everything else is safe from
 *                 any thread.
 */
public final class WorldOwnershipService {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaWorker");

    private final PeerTransport transport;
    private final NodeId self;
    private final Supplier<List<PeerEntry>> members;

    /** worldIdHex → the accepted ownership claim for that world. */
    private final Map<String, WorldOwnership> owners = new ConcurrentHashMap<>();

    /**
     * @param self      this node's id (never gossiped to).
     * @param transport the peer transport.
     * @param members   the current session members to relay to.
     */
    public WorldOwnershipService(NodeId self, PeerTransport transport,
                                 Supplier<List<PeerEntry>> members) {
        this.self = Objects.requireNonNull(self, "self");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.members = Objects.requireNonNull(members, "members");
    }

    /**
     * @param worldIdHex the world.
     * @return the accepted ownership claim for it, if this node has one.
     */
    public Optional<WorldOwnership> ownerOf(String worldIdHex) {
        return worldIdHex == null ? Optional.empty()
                : Optional.ofNullable(owners.get(worldIdHex.trim().toLowerCase(Locale.ROOT)));
    }

    /** @return how many worlds this node knows the administrator of. */
    public int knownWorlds() {
        return owners.size();
    }

    /**
     * Accept this node's own claim and publish it to every session member.
     *
     * <p>The local accept comes first for the same reason it does in the grant lane: a claim this
     * node would itself refuse is never relayed, so an invalid one never costs the mesh anything.
     *
     * @param claim the doubly-signed ownership record.
     * @return whether the claim was accepted here (and therefore published).
     */
    public boolean publish(WorldOwnership claim) {
        if (!accept(claim)) {
            return false;
        }
        relay(claim, null);
        return true;
    }

    /**
     * Handle one application-lane message; ignores anything that is not ownership gossip.
     *
     * @param from    the sender.
     * @param message the message.
     * @Thread-context runtime state thread.
     */
    public void onMessage(PeerAddress from, NoderaMessage message) {
        if (!(message instanceof WorldOwnershipGossip gossip)) {
            return;
        }
        WorldOwnership claim;
        try {
            claim = WorldOwnership.decode(new CanonicalReader(gossip.encodedOwnership()));
        } catch (RuntimeException malformed) {
            LOG.debug("discarding malformed ownership gossip from {}: {}", from,
                    malformed.getMessage());
            return;
        }
        // The envelope's world id must be the one inside the signed claim. They cannot disagree in
        // an honest message, and honouring the envelope would let a relay file a valid claim under
        // a different world.
        if (!claim.worldId().equals(gossip.worldId())) {
            LOG.debug("discarding ownership gossip whose envelope names a different world than the "
                    + "claim it carries (from {})", from);
            return;
        }
        if (!accept(claim)) {
            return; // unverifiable, or a conflicting claim — never relayed onward
        }
        relay(claim, from == null ? null : from.nodeId());
    }

    /**
     * Verify and file a claim.
     *
     * @return whether this call is what filed it — {@code false} for an invalid claim, a conflicting
     *         one, and a repeat of one already held (so none of those is relayed again).
     */
    private boolean accept(WorldOwnership claim) {
        if (claim == null || !claim.verify()) {
            return false;
        }
        String key = claim.worldId().toHex().toLowerCase(Locale.ROOT);
        WorldOwnership existing = owners.putIfAbsent(key, claim);
        if (existing == null) {
            LOG.info("World {} is administered by {}", shortId(key), claim.ownerNodeId().value());
            return true;
        }
        if (!existing.ownerNodeId().equals(claim.ownerNodeId())
                || !existing.worldPublicKey().equals(claim.worldPublicKey())) {
            // Two verifying claims for one world means one of them was minted by a peer that is not
            // the author — or the author minted a second key, which is the same thing to everyone
            // else. Keeping the first is what stops the world changing hands by assertion.
            LOG.warn("Refusing a second ownership claim for world {} from {} — it is already "
                            + "administered by {}", shortId(key), claim.ownerNodeId().value(),
                    existing.ownerNodeId().value());
        }
        return false;
    }

    private void relay(WorldOwnership claim, NodeId excluding) {
        CanonicalWriter w = new CanonicalWriter();
        claim.encode(w);
        byte[] frame = WireCodec.encode(new WorldOwnershipGossip(claim.worldId(), w.toBytes()));
        for (PeerEntry member : members.get()) {
            NodeId id = member.nodeId();
            if (id.equals(self) || id.equals(excluding)) {
                continue;
            }
            try {
                transport.send(PeerAddress.of(id, member.route()), frame);
            } catch (TransportException unreachable) {
                // One unreachable peer must not stop the claim reaching the others; it learns the
                // owner from any peer that has it the next time it is reachable.
                LOG.debug("ownership relay to {} failed: {}", id.value(), unreachable.getMessage());
            }
        }
    }

    /** Re-publish every claim this node holds — used when the session membership changes. */
    public void republish() {
        for (WorldOwnership claim : owners.values()) {
            relay(claim, null);
        }
    }

    private static String shortId(String hex) {
        return hex.length() <= 12 ? hex : hex.substring(0, 12);
    }

    /** @param bytes canonical claim bytes. @return the claim, or empty when it does not decode. */
    static Optional<WorldOwnership> decodeQuietly(Bytes bytes) {
        if (bytes == null || bytes.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(WorldOwnership.decode(new CanonicalReader(bytes)));
        } catch (RuntimeException undecodable) {
            return Optional.empty();
        }
    }
}
