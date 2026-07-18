package dev.nodera.storage.event;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.storage.ContentId;
import dev.nodera.storage.ContentStore;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory content-addressed blob store (Task 9). Keyed by the blob's SHA-256, so storing the same
 * bytes twice is idempotent (deduplication) and a fetched blob is verified by re-hashing to its id
 * (integrity). Blobs are defensively copied in and out.
 *
 * @Thread-context confined to the owning thread; not thread-safe.
 */
public final class InMemoryContentStore implements ContentStore {

    private final HashService hashes;
    private final Map<Bytes, byte[]> blobs = new HashMap<>();

    public InMemoryContentStore(HashService hashes) {
        if (hashes == null) {
            throw new IllegalArgumentException("hashes must not be null");
        }
        this.hashes = hashes;
    }

    @Override
    public ContentId put(byte[] blob) {
        if (blob == null) {
            throw new IllegalArgumentException("blob must not be null");
        }
        ContentId id = ContentId.of(hashes, blob);
        blobs.putIfAbsent(id.hash(), blob.clone());
        return id;
    }

    @Override
    public Optional<byte[]> get(ContentId id) {
        byte[] b = blobs.get(id.hash());
        return b == null ? Optional.empty() : Optional.of(b.clone());
    }

    @Override
    public boolean has(ContentId id) {
        return blobs.containsKey(id.hash());
    }

    @Override
    public int size() {
        return blobs.size();
    }
}
