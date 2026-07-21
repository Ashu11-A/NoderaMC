package dev.nodera.protocol.rendezvous;

import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * The relay reporting a caller's reflexive address (Task 29, wire tag 43; STUN-ish, rendezvous.md
 * §2.5/§4.1). A peer learns the public {@code host:port} its NAT presents and can add it as a
 * {@link CandidateKind#SERVER_REFLEXIVE} candidate. The address is a hint, never proof of identity.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param peer          the peer whose address is reported.
 * @param observedRoute the address the relay observed the caller connecting from.
 */
public record ObservedAddress(NodeId peer, String observedRoute) implements NoderaMessage {

    /** @throws IllegalArgumentException if a reference argument is null. */
    public ObservedAddress {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(observedRoute, "observedRoute");
    }
}
