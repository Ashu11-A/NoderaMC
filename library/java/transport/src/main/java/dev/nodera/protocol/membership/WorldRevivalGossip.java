package dev.nodera.protocol.membership;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * A world's owner putting it back, on its way between peers — the counterpart of
 * {@link WorldDeletionGossip}.
 *
 * <p>Carried the same way and for the same reason: the revival is <b>opaque canonical bytes</b>
 * signed by both the world key and the owner's node key, so the transport has nothing to verify and
 * no ability to weaken it. A forged revival is refused identically on every honest peer, whoever
 * relayed it — which is what makes flooding it safe.
 *
 * <p>Restoration has to travel as far as deletion did, and to the same places: a peer that kept a
 * tombstone and never hears the revival goes on refusing the world forever, and the owner's own
 * re-share looks broken to everybody but the owner.
 *
 * @param worldId        the world being restored; not null.
 * @param encodedRevival the canonical encoding of a {@code WorldRevival}; not null.
 */
public record WorldRevivalGossip(Bytes worldId, Bytes encodedRevival) implements NoderaMessage {

    public WorldRevivalGossip {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(encodedRevival, "encodedRevival");
    }
}
