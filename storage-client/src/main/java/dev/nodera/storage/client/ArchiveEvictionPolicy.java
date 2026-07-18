package dev.nodera.storage.client;

import dev.nodera.storage.ContentId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Decides what to evict when the client store needs room (Task 22; L-37).
 *
 * <h2>The rule, and why it is the rule</h2>
 *
 * <p>Evict the oldest <b>cold</b> shard first: content that is not {@link Entry#pinned() pinned}
 * (i.e. not an assigned region's current state), least-recently-used. <b>Never</b> evict a pinned
 * blob — dropping an assigned region's current snapshot or recent log would lose the peer its
 * committee duties and break the replication factor Task 21 enforces. When eviction removes a
 * replica, the store signals Task 21's repair so the factor is re-met elsewhere before the data is
 * gone everywhere.
 *
 * <p>Thread-context: stateless; safe from any thread.
 */
public final class ArchiveEvictionPolicy {

    private ArchiveEvictionPolicy() {}

    /**
     * One tracked content entry.
     *
     * @param id         the content id (its hash is the identity).
     * @param sizeBytes  the stored byte length.
     * @param pinned     {@code true} if this is an assigned region's current state (never evicted).
     * @param lastAccess the last-access tick (higher = more recent); caller-supplied, never a clock.
     * @Thread-context immutable record, safe for any thread.
     */
    public record Entry(ContentId id, long sizeBytes, boolean pinned, long lastAccess) {

        /**
         * Compact constructor.
         *
         * @throws IllegalArgumentException if an argument is null or {@code sizeBytes} is negative.
         */
        public Entry {
            Objects.requireNonNull(id, "id");
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must be non-negative: " + sizeBytes);
            }
        }
    }

    /**
     * The eviction list: content ids to drop, oldest cold first, until at least
     * {@code bytesNeeded} are freed.
     *
     * @param entries     everything currently stored.
     * @param bytesNeeded how many bytes must be freed.
     * @return the ids to evict (in eviction order); the list may free more than requested (the last
     *         entry that crosses the threshold is included whole — content is atomic).
     * @throws IllegalArgumentException if {@code entries} is null, or the cold content cannot free
     *                                  enough (only pinned bytes remain) — the caller must then
     *                                  refuse the put with a {@link QuotaException}.
     */
    public static List<ContentId> evictToFree(List<Entry> entries, long bytesNeeded) {
        Objects.requireNonNull(entries, "entries");
        if (bytesNeeded < 0) {
            throw new IllegalArgumentException("bytesNeeded must be non-negative: " + bytesNeeded);
        }
        if (bytesNeeded == 0) {
            return List.of();
        }
        // Cold only, oldest (lowest lastAccess) first. Ties break on hash hex for a total order.
        List<Entry> cold = new ArrayList<>();
        for (Entry e : entries) {
            if (!e.pinned()) {
                cold.add(e);
            }
        }
        cold.sort(Comparator
                .comparingLong(Entry::lastAccess)
                .thenComparing(e -> e.id().hash().toHex()));

        List<ContentId> out = new ArrayList<>();
        long freed = 0;
        for (Entry e : cold) {
            if (freed >= bytesNeeded) {
                break;
            }
            out.add(e.id());
            freed += e.sizeBytes();
        }
        if (freed < bytesNeeded) {
            throw new QuotaException("cannot free " + bytesNeeded + "B: only " + freed
                    + "B of cold content available (the rest is pinned assigned-region state)");
        }
        return List.copyOf(out);
    }
}
