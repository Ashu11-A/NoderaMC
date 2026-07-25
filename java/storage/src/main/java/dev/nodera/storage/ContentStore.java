package dev.nodera.storage;

import java.util.Optional;

/**
 * Content-addressed blob store (Plan §3.12 / Task 9): snapshots, event-log segments and checkpoint
 * payloads are stored by their {@link ContentId} (a hash), never by mutable path. Storing the same
 * bytes twice is idempotent and yields the same id (free deduplication); a fetched blob is verified
 * by re-hashing to its id (integrity).
 *
 * @Thread-context implementations document their own thread-safety.
 */
public interface ContentStore {

    /**
     * Store {@code blob} and return its content id. Idempotent: the same bytes always map to the
     * same {@link ContentId}.
     */
    ContentId put(byte[] blob);

    /** @return the stored bytes for {@code id}, or empty if absent. */
    Optional<byte[]> get(ContentId id);

    /** @return {@code true} if {@code id} is present. */
    boolean has(ContentId id);

    /**
     * Drop {@code id} from this store.
     *
     * <p>Content-addressed storage is normally append-only, but some blobs must be able to *stop*
     * existing: a password re-key mints a new ciphertext for the same world, and the superseded one
     * is still decryptable with the OLD password, so continuing to seed it would keep a revoked
     * password usable forever (L-55). Removal is idempotent — removing an absent id is not an error.
     *
     * @param id the content id to drop.
     * @return {@code true} if the blob was present and is now gone.
     */
    boolean remove(ContentId id);

    /** @return the number of distinct blobs held. */
    int size();
}
