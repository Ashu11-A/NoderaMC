package dev.nodera.storage;

/**
 * A {@link ContentStore} that can be told which content must never be evicted (L-62).
 *
 * <p>A byte budget without pinning is worse than no budget: the store would happily make room for
 * a replica of somebody else's world by deleting the world this node is hosting. Pinning is how a
 * bounded store is told the difference between content it is caching and content it is responsible
 * for — an assigned region's current state, or a world this node seeds.
 *
 * <p>Unbounded stores may implement this as a no-op; a store that never evicts has nothing to
 * protect content from. Callers must not assume a pin implies bounded behaviour, only that pinned
 * content will not be evicted to make room.
 *
 * @Thread-context per implementation; {@code FsContentStore} is thread-confined, the bounded client
 *                 store is not.
 */
public interface PinnableContentStore extends ContentStore {

    /**
     * Mark {@code id} un-evictable. Idempotent.
     *
     * @return {@code true} if the content is present and is now pinned. Pinning content the store
     *         does not hold is not an error — a caller pins what it intends to keep, and learns
     *         from the return value that there is nothing there to keep.
     */
    boolean pin(ContentId id);

    /** Release a pin, making the content ordinary evictable-by-age content. Idempotent. */
    void unpin(ContentId id);

    /** @return whether {@code id} is currently pinned. */
    boolean isPinned(ContentId id);
}
