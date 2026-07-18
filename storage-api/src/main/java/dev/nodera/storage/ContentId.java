package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;

/**
 * A content-addressed blob identifier (Plan §3.12): {@code hash + size + compression}. Content
 * addressing gives free deduplication (identical blobs share an id) and integrity (the id IS the
 * hash, so a fetched blob is verified by re-hashing). Snapshots, event-log segments and checkpoints
 * are all referenced by {@code ContentId} rather than by mutable path.
 *
 * @param hash        SHA-256 of the stored bytes.
 * @param size        the stored byte length.
 * @param compression how the bytes are encoded.
 * @Thread-context immutable, any thread.
 */
public record ContentId(Bytes hash, long size, Compression compression) {

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

    /** @return a short hex form for logs. */
    @Override
    public String toString() {
        return "content:" + hash.toHex().substring(0, Math.min(12, hash.toHex().length()))
                + "/" + size + "/" + compression;
    }
}
