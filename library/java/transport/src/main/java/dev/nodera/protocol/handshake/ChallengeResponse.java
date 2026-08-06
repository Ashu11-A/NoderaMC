package dev.nodera.protocol.handshake;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * Client's signed response to the server's {@link ServerHello#getChallenge()} challenge
 * (Task 4).
 *
 * <p>The signature covers {@code challenge ‖ networkId}; the server verifies it against the
 * public key supplied in {@link ClientHello}.
 *
 * <p><b>Nothing sends or receives this today.</b> It belongs to the client↔dedicated-server
 * handshake, and Task 30 retired the dedicated server; the registry a valid signature used to
 * populate, {@code coordinator.NodeRegistry}, was deleted with the rest of the central-coordinator
 * design on 2026-08-06 (Plan 11 round 2, issue #210). The handshake that actually runs between
 * peers is {@code dev.nodera.protocol.session.Hello}/{@code HelloAck}, driven by
 * {@code dev.nodera.peer.PeerRuntime}, and the membership it establishes is the session roster —
 * not a server-side node table. Tag 3 stays frozen in {@code WireRegistry} and the codec still
 * round-trips this record; treat it as a reserved shape, not as live protocol.
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
