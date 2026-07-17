package dev.nodera.protocol.handshake;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * Client's signed response to the server's {@link ServerHello#getChallenge()} challenge
 * (Task 4).
 *
 * <p>The signature covers {@code challenge ‖ networkId}; the server verifies it against the
 * public key supplied in {@link ClientHello}. A valid signature completes authentication and
 * registers the node in the in-memory {@code NodeRegistry} (Task 6 makes it durable).
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param signature Ed25519 signature over {@code challenge ‖ networkId}.
 */
public record ChallengeResponse(Bytes signature) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code signature} is null.
     */
    public ChallengeResponse {
        Objects.requireNonNull(signature, "signature");
    }
}
