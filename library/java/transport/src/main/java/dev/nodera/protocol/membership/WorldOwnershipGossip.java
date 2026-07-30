package dev.nodera.protocol.membership;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * One world's ownership claim, gossiped to the peers that host or support it.
 *
 * <p>Answers the question a peer has no other way to settle: of everyone serving this world, which
 * one administers it? Until this message existed the answer lived only on the creating machine, so a
 * peer keeping somebody else's world alive knew the bytes and not the authority behind them — and a
 * peer could assert anything about a world it merely held a copy of.
 *
 * <p>The claim travels as <b>opaque canonical bytes</b>, exactly like the grants in
 * {@link WorldGrantGossip} and the manifests in {@code WorldManifestAnswer}. That is the point:
 * {@code WorldOwnership} is doubly self-authenticating — signed by the world's own key <i>and</i> by
 * the creating node's key, both over bytes that name the world, both keys and the owner — so a
 * relay has nothing to verify and no ability to weaken it. A forged, re-signed or lifted claim is
 * refused identically on every honest peer whoever passed it along.
 *
 * <p>Gossip termination: a receiver relays a claim only the first time it accepts one for a world,
 * and a second, different claim for the same world is refused rather than replacing it — so the
 * flood dies out and an owner cannot be overwritten by a later, louder peer.
 *
 * @param worldId          the world the claim belongs to; not null.
 * @param encodedOwnership the canonical encoding of a {@code WorldOwnership}; not null.
 */
public record WorldOwnershipGossip(Bytes worldId, Bytes encodedOwnership) implements NoderaMessage {

    public WorldOwnershipGossip {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(encodedOwnership, "encodedOwnership");
    }
}
