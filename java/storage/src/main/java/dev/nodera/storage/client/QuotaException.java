package dev.nodera.storage.client;

/**
 * Raised when content cannot be stored because the byte budget is exhausted and there is no cold
 * (unpinned) content left to evict (Task 22).
 *
 * <p>The store never evicts a pinned blob (an assigned region's current state) to make room — that
 * would break the replication factor — so a put that needs more room than the cold content can free
 * fails loudly rather than silently dropping load-bearing state.
 */
public final class QuotaException extends RuntimeException {

    /**
     * @param message what went wrong.
     */
    public QuotaException(String message) {
        super(message);
    }
}
