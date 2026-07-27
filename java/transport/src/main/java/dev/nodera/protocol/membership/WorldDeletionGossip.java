package dev.nodera.protocol.membership;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * A world's owner asking the network to forget it, on its way between peers.
 *
 * <p>The tombstone travels as <b>opaque canonical bytes</b>, exactly like the ownership claims and
 * permission grants beside it, and for a stronger version of the same reason. A
 * {@code WorldTombstone} carries its own ownership claim and is signed by both the world key and the
 * owner's node key, so it is checkable from first principles by a peer that has never heard of the
 * world. The transport therefore has nothing to verify and no ability to weaken it: a forged
 * deletion is refused identically on every honest peer, whoever relayed it.
 *
 * <p>That property is what makes flooding a destructive instruction safe at all. Nobody has to trust
 * the messenger, so anybody may be one.
 *
 * @param worldId         the world being deleted; not null.
 * @param encodedTombstone the canonical encoding of a {@code WorldTombstone}; not null.
 */
public record WorldDeletionGossip(Bytes worldId, Bytes encodedTombstone) implements NoderaMessage {

    public WorldDeletionGossip {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(encodedTombstone, "encodedTombstone");
    }
}
