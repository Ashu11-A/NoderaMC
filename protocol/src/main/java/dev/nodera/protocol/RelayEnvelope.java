package dev.nodera.protocol;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;

import java.util.Objects;

/**
 * Server-relayed wrapper around a client↔client frame (Task 4 §"NeoForgeRelayTransport").
 *
 * <p>The NeoForge transport has no native client↔client channel: every client speaks only to
 * the integrated server. To deliver a message to another client, the sender wraps its encoded
 * frame in a {@code RelayEnvelope} addressed to the target {@link NodeId}; the server forwards
 * the {@code innerFrame} to the named target (after validating the sender is authenticated and
 * the target is connected).
 *
 * <p>{@code innerFrame} is opaque bytes — it is itself a {@code MessageCodec}-encoded
 * {@link NoderaMessage}; the relay layer never decodes it.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param target     the final recipient's stable node id.
 * @param innerFrame the opaque, already-encoded frame to deliver.
 */
public record RelayEnvelope(NodeId target, Bytes innerFrame) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code target} or {@code innerFrame} is null.
     */
    public RelayEnvelope {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(innerFrame, "innerFrame");
    }
}
