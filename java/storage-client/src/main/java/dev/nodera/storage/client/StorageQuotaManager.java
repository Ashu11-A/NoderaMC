package dev.nodera.storage.client;

/**
 * Tracks bytes-used against a fixed budget (Task 22; L-37).
 *
 * <p>Pure accounting: the budget is a {@code long} set at construction, and the manager records
 * reservations and releases as content is stored and evicted. {@link BoundedClientWorldStore} asks
 * it "do I have room?" before a put and tells it how much was freed on eviction. Keeping the
 * accounting here, separate from the store, makes the budget testable in isolation.
 *
 * <p>Thread-context: NOT thread-safe; the owning store serialises access.
 *
 * @param budgetBytes the byte budget; the default client quota is {@link #DEFAULT_BUDGET_BYTES}.
 * @param usedBytes   the bytes currently in use.
 */
public record StorageQuotaManager(long budgetBytes, long usedBytes) {

    /** Default client quota: 2 GiB (Plan §3.13). */
    public static final long DEFAULT_BUDGET_BYTES = 2L * 1024 * 1024 * 1024;

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code budgetBytes} is not positive or {@code usedBytes}
     *                                  is negative or exceeds the budget.
     */
    public StorageQuotaManager {
        if (budgetBytes <= 0) {
            throw new IllegalArgumentException("budgetBytes must be positive: " + budgetBytes);
        }
        if (usedBytes < 0) {
            throw new IllegalArgumentException("usedBytes must be non-negative: " + usedBytes);
        }
        if (usedBytes > budgetBytes) {
            throw new IllegalArgumentException(
                    "usedBytes " + usedBytes + " exceeds budget " + budgetBytes);
        }
        budgetBytes = budgetBytes;
        usedBytes = usedBytes;
    }

    /** @return a fresh manager at the default budget, empty. */
    public static StorageQuotaManager defaultEmpty() {
        return new StorageQuotaManager(DEFAULT_BUDGET_BYTES, 0L);
    }

    /** @return a fresh manager at the default budget, empty. */
    public StorageQuotaManager empty() {
        return new StorageQuotaManager(budgetBytes, 0L);
    }

    /** @return how many bytes remain under the budget. */
    public long remaining() {
        return budgetBytes - usedBytes;
    }

    /** @return {@code true} if {@code bytes} more would still fit. */
    public boolean canFit(long bytes) {
        return bytes >= 0 && bytes <= remaining();
    }

    /**
     * @param bytes the additional reservation.
     * @return a manager with the reservation applied.
     * @throws IllegalArgumentException if the result would exceed the budget.
     */
    public StorageQuotaManager reserve(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must be non-negative: " + bytes);
        }
        if (!canFit(bytes)) {
            throw new QuotaException(
                    "cannot reserve " + bytes + "B: " + remaining() + "B remaining of " + budgetBytes);
        }
        return new StorageQuotaManager(budgetBytes, usedBytes + bytes);
    }

    /**
     * @param bytes the bytes freed (must not exceed current usage).
     * @return a manager with the release applied.
     * @throws IllegalArgumentException if {@code bytes} is negative or exceeds usage.
     */
    public StorageQuotaManager release(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must be non-negative: " + bytes);
        }
        if (bytes > usedBytes) {
            throw new IllegalArgumentException(
                    "cannot release " + bytes + "B; only " + usedBytes + "B in use");
        }
        return new StorageQuotaManager(budgetBytes, usedBytes - bytes);
    }
}
