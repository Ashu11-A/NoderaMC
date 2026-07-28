package dev.nodera.protocol.service;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

import java.util.Objects;

/**
 * One directory row (type tag {@value TypeTags#SERVICE_DIRECTORY_ENTRY}): a service's signed record
 * plus the answering tracker's score for it.
 *
 * <p>The signature travels with the record so the row is verifiable <b>on its own</b>. A peer that
 * trusted a row because it came from a tracker would have made that tracker authority over which
 * rendezvous its traffic flows through — exactly what the trust model forbids.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param record    the service's canonical record.
 * @param signature Ed25519 over {@link ServiceRecord#signedBytes()}.
 * @param score     the answering tracker's aggregate score.
 */
public record ServiceDirectoryEntry(ServiceRecord record, Bytes signature, ServiceScore score)
        implements Encodable {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any argument is null.
     */
    public ServiceDirectoryEntry {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(score, "score");
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.SERVICE_DIRECTORY_ENTRY).writeU16(Encodable.ENCODING_VERSION);
        record.encode(w);
        w.writeBytes(signature);
        score.encode(w);
    }

    /**
     * Decode the inverse of {@link #encode(CanonicalWriter)}.
     *
     * @param r the canonical source.
     * @return the entry.
     * @throws IllegalStateException if the tag/version is wrong.
     * @Thread-context one reader per decode call.
     */
    public static ServiceDirectoryEntry decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.SERVICE_DIRECTORY_ENTRY) {
            throw new IllegalStateException("expected ServiceDirectoryEntry tag, got " + tag);
        }
        int version = r.readU16();
        if (version != Encodable.ENCODING_VERSION) {
            throw new IllegalStateException("unsupported ServiceDirectoryEntry version " + version);
        }
        return new ServiceDirectoryEntry(ServiceRecord.decode(r), r.readBytesValue(),
                ServiceScore.decode(r));
    }
}
