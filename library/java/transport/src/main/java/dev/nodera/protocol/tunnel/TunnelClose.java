package dev.nodera.protocol.tunnel;

import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * The end of one tunnelled stream, from either side.
 *
 * <p>Carries a reason because this is the only thing a refused guest ever receives: a session that
 * was never published, a host whose game has closed, a local socket that would not dial. Without a
 * reason the guest's Minecraft client would simply fail to connect and the player would have
 * nothing to look at — which is the state this whole lane exists to get people out of.
 *
 * @param streamId the stream ending.
 * @param reason   why, in words a person can act on; never null, may be empty for a clean EOF.
 */
public record TunnelClose(long streamId, String reason) implements NoderaMessage {

    public TunnelClose {
        Objects.requireNonNull(reason, "reason");
    }
}
