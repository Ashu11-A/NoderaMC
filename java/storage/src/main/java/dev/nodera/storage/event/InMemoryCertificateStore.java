package dev.nodera.storage.event;

import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.EntityTransferCertificate;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.storage.CertificateStore;
import dev.nodera.storage.ContentId;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory content-addressed {@link QuorumCertificate} store (Task 9). Certificates are keyed by
 * the SHA-256 of their canonical encoding, so an event's {@code certificateRef} hash resolves the
 * exact certificate that finalised it ({@link #getByHash}). Storing the same certificate twice is
 * idempotent.
 *
 * @Thread-context confined to the owning thread; not thread-safe.
 */
public final class InMemoryCertificateStore implements CertificateStore {

    private final HashService hashes;
    private final Map<Bytes, QuorumCertificate> byHash = new HashMap<>();
    private final Map<Bytes, EntityTransferCertificate> transfersByHash = new HashMap<>();

    public InMemoryCertificateStore(HashService hashes) {
        if (hashes == null) {
            throw new IllegalArgumentException("hashes must not be null");
        }
        this.hashes = hashes;
    }

    /** @return the content id (hash + size) of {@code cert}'s canonical encoding. */
    public ContentId contentId(QuorumCertificate cert) {
        return ContentId.of(hashes, encode(cert));
    }

    @Override
    public ContentId put(QuorumCertificate certificate) {
        if (certificate == null) {
            throw new IllegalArgumentException("certificate must not be null");
        }
        ContentId id = contentId(certificate);
        byHash.putIfAbsent(id.hash(), certificate);
        return id;
    }

    @Override
    public ContentId put(EntityTransferCertificate certificate) {
        if (certificate == null) {
            throw new IllegalArgumentException("certificate must not be null");
        }
        ContentId id = ContentId.of(hashes, encode(certificate));
        transfersByHash.putIfAbsent(id.hash(), certificate);
        return id;
    }

    @Override
    public Optional<QuorumCertificate> get(ContentId id) {
        return getByHash(id.hash());
    }

    @Override
    public Optional<QuorumCertificate> getByHash(Bytes hash) {
        return Optional.ofNullable(byHash.get(hash));
    }

    @Override
    public Optional<EntityTransferCertificate> getTransferByHash(Bytes hash) {
        return Optional.ofNullable(transfersByHash.get(hash));
    }

    @Override
    public boolean has(ContentId id) {
        return byHash.containsKey(id.hash()) || transfersByHash.containsKey(id.hash());
    }

    private static byte[] encode(dev.nodera.core.crypto.Encodable cert) {
        CanonicalWriter w = new CanonicalWriter();
        cert.encode(w);
        return w.toByteArray();
    }
}
