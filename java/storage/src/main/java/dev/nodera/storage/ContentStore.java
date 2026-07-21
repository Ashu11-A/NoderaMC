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

    /** @return the number of distinct blobs held. */
    int size();
}
