package dev.nodera.protocol.tunnel;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * A guest asking a host to connect one TCP stream to a published session.
 *
 * <h2>Why this names a session and not an address</h2>
 *
 * <p>The obvious design — "connect me to {@code host:port}" — would make every Nodera peer an open
 * TCP proxy for anyone who can reach it, into its own loopback and its own LAN. That is not a
 * feature with a bug in it; it is a hole with a use case. So the guest cannot name a destination at
 * all: it names a <b>session</b>, and the host resolves that to the single port <i>it</i> chose to
 * publish. A session the host has not published is refused, and there is no request shape that can
 * express any other target.
 *
 * <p>{@code streamId} is chosen by the guest and is unique only within that guest's connection, so
 * a host keys its streams by {@code (peer, streamId)} — two guests picking 1 are two streams, not a
 * collision.
 *
 * @param sessionId the published session to attach to; not null.
 * @param streamId  the guest's identifier for this stream.
 */
public record TunnelOpen(Bytes sessionId, long streamId) implements NoderaMessage {

    public TunnelOpen {
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
