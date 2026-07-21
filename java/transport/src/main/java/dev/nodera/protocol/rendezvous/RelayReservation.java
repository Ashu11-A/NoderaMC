package dev.nodera.protocol.rendezvous;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * The relay's answer to a {@link RelayReserve} (Task 29, wire tag 39; rendezvous.md §4.2). Carries
 * the relay route the peer advertises, the reservation's expiry and limits, and an HMAC
 * {@link #proof()} the relay validates statelessly on {@code CONNECT}/{@code INCOMING} so the open-
 * relay abuse hole stays closed (rendezvous.md §8.4).
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param accepted             whether a slot was granted.
 * @param relayRoute           the relay route a peer advertises as its relay candidate.
 * @param expiresAtEpochMillis when the reservation expires, or {@code 0} when refused.
 * @param maxBytes             byte ceiling for a single circuit to this reservation.
 * @param maxDurationMillis    wall-clock ceiling for a single circuit.
 * @param proof                HMAC proof the relay validates (opaque to peers).
 * @param reason               empty when accepted; otherwise a short stable rejection code.
 */
public record RelayReservation(
        boolean accepted,
        String relayRoute,
        long expiresAtEpochMillis,
        long maxBytes,
        long maxDurationMillis,
        Bytes proof,
        String reason) implements NoderaMessage {

    /** @throws IllegalArgumentException if a reference argument is null. */
    public RelayReservation {
        Objects.requireNonNull(relayRoute, "relayRoute");
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(reason, "reason");
    }
}
