package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.QuorumCertificate;

import java.util.Optional;

/**
 * Content-addressed store of {@link QuorumCertificate}s (Plan §3.12 / Task 9). Every committed event
 * references its finalising certificate by {@link ContentId}; a peer verifying the log fetches the
 * certificate here and checks its {@code resultingRoot} matches the event (Invariant 3: no state is
 * canonical without a certificate).
 *
 * @Thread-context implementations document their own thread-safety.
 */
public interface CertificateStore {

    /** Store a certificate and return its content id (idempotent by content). */
    ContentId put(QuorumCertificate certificate);

    /** @return the certificate for {@code id}, or empty if absent. */
    Optional<QuorumCertificate> get(ContentId id);

    /**
     * @param hash the content-hash pointer an event carries in its {@code certificateRef}.
     * @return the certificate whose content id has this hash, or empty. Used during log replay to
     *         verify that every committed event is backed by a matching certificate (Invariant 3).
     */
    Optional<QuorumCertificate> getByHash(Bytes hash);

    /** @return {@code true} if {@code id} is present. */
    boolean has(ContentId id);
}
