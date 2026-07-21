package dev.nodera.protocol.handshake;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * Configuration-phase handshake: a client worker's first message to the server (Task 4).
 *
 * <p>Carries the client's protocol version, stable {@link NodeId}, Ed25519 public key, declared
 * {@link NodeCapabilities}, and the rules/registry fingerprints the server matches against its
 * own. The {@code signature} is an Ed25519 signature over the server's challenge (delivered in
 * {@link ServerHello}), proving possession of the matching private key.
 *
 * <p>Wire framing is owned by {@code dev.nodera.protocol.codec.MessageCodec}; this record holds
 * only the message payload.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param protocolVersion    wire-protocol version the client speaks.
 * @param nodeId             stable client identifier.
 * @param publicKey          X.509-encoded Ed25519 public key bytes.
 * @param capabilities       self-declared resource/capability profile.
 * @param rulesVersion       rules pack version the client is running.
 * @param registryFingerprint fingerprint of the block/item registry the client loaded.
 * @param signature          Ed25519 signature over the server challenge.
 */
public record ClientHello(
        int protocolVersion,
        NodeId nodeId,
        Bytes publicKey,
        NodeCapabilities capabilities,
        int rulesVersion,
        long registryFingerprint,
        Bytes signature
) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any argument is null.
     */
    public ClientHello {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(publicKey, "publicKey");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(signature, "signature");
    }
}
