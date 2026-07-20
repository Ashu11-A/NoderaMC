package dev.nodera.protocol.rendezvous;

import dev.nodera.core.Bytes;

import java.util.Objects;

/**
 * A {@link SignedPeerRecord} paired with the Ed25519 signature over its
 * {@link SignedPeerRecord#signedBytes()} (Task 29).
 *
 * <p>Travels inside a {@code RendezvousRegister} (one record) and a {@code RendezvousPeers} page
 * (many). Not itself a {@code NoderaMessage}: it is a body component encoded inline by the codec,
 * like {@code PeerEntry} inside a membership message. Because the signature is over the record's own
 * canonical bytes, the identical signature validates on both the relay and every discovering peer.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param record    the signed record.
 * @param signature Ed25519 over {@code record.signedBytes()}.
 */
public record SignedRecord(SignedPeerRecord record, Bytes signature) {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if an argument is null.
     */
    public SignedRecord {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(signature, "signature");
    }
}
