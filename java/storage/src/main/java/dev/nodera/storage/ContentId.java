package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.crypto.TypeTags;

/**
 * A content-addressed blob identifier (Plan §3.12): {@code hash + size + compression}. Content
 * addressing gives free deduplication (identical blobs share an id) and integrity (the id IS the
 * hash, so a fetched blob is verified by re-hashing). Snapshots, event-log segments and checkpoints
 * are all referenced by {@code ContentId} rather than by mutable path.
 *
 * <p>Wire form: {@code [u16 CONTENT_ID][u16 ENCODING_VERSION][bytes hash][u64 size]
 * [u8 compression ordinal]} — the persistence form used by the archival tier (Task 9) and the
 * checkpoint-sync messages that carry ids over the wire.
 *
 * @param hash        SHA-256 of the stored bytes.
 * @param size        the stored byte length.
 * @param compression how the bytes are encoded.
 * @Thread-context immutable, any thread.
 */
public record ContentId(Bytes hash, long size, Compression compression) implements Encodable {

    public ContentId {
        if (hash == null) {
            throw new IllegalArgumentException("hash must not be null");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }
        if (compression == null) {
            throw new IllegalArgumentException("compression must not be null");
        }
    }

    /**
     * Compute the content id of {@code blob} stored uncompressed.
     *
     * @param hashes the SHA-256 service.
     * @param blob   the bytes to address.
     * @return the {@code (sha256(blob), blob.length, NONE)} id.
     */
    public static ContentId of(HashService hashes, byte[] blob) {
        return new ContentId(hashes.sha256(blob), blob.length, Compression.NONE);
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.CONTENT_ID).writeU16(ENCODING_VERSION);
        w.writeBytes(hash);
        w.writeU64(size);
        w.writeU8(compression.ordinal());
    }

    /**
     * Full-frame decode.
     *
     * @throws IllegalStateException if the next tag is not {@code CONTENT_ID} or the compression
     *         ordinal is out of range.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static ContentId decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.CONTENT_ID) {
            throw new IllegalStateException("expected CONTENT_ID tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        Bytes hash = r.readBytesValue();
        long size = r.readU64();
        int ord = r.readU8();
        Compression[] values = Compression.values();
        if (ord >= values.length) {
            throw new IllegalStateException("Compression ordinal out of range: " + ord);
        }
        return new ContentId(hash, size, values[ord]);
    }

    /** @return a short hex form for logs. */
    @Override
    public String toString() {
        return "content:" + hash.toHex().substring(0, Math.min(12, hash.toHex().length()))
                + "/" + size + "/" + compression;
    }
}
