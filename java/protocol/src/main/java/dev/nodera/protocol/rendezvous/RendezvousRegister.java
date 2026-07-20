package dev.nodera.protocol.rendezvous;

import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * A peer's signed self-registration with a rendezvous point (Task 29, wire tag 35; rendezvous.md
 * §4.1). Carries one {@link SignedRecord}; the service verifies the signature before touching its
 * registry, so only the key holder can register, refresh, or {@code UNREGISTER} its own record.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param signed the signed record and its signature.
 */
public record RendezvousRegister(SignedRecord signed) implements NoderaMessage {

    /** @throws IllegalArgumentException if {@code signed} is null. */
    public RendezvousRegister {
        Objects.requireNonNull(signed, "signed");
    }
}
