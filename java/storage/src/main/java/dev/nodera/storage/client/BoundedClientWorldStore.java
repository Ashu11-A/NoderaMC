package dev.nodera.storage.client;

import dev.nodera.core.crypto.HashService;
import dev.nodera.storage.ContentId;
import dev.nodera.storage.ContentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The bounded, quota'd client content store (Task 22; L-37).
 *
 * <p>Wraps an in-memory content map with a {@link StorageQuotaManager} byte budget. A put that would
 * exceed the budget triggers {@link ArchiveEvictionPolicy}: cold (unpinned) shards are evicted
 * oldest-first until there is room. Pinned content — an assigned region's current state — is never
 * evicted; if it cannot fit alongside the pinned set, the put throws {@link QuotaException} rather
 * than silently dropping load-bearing state.
 *
 * <h2>Repair signalling</h2>
 *
 * <p>Evicting a replica reduces the world's replication factor. An optional
 * {@link EvictionListener} is notified per eviction so Task 21's repair re-creates the replica
 * elsewhere <i>before</i> it is gone everywhere.
 *
 * <h2>No clocks</h2>
 *
 * <p>"Last access" is a caller-supplied tick ({@code long}), advanced by the runtime — never a wall
 * clock — so eviction order is deterministic and testable without sleeping.
 *
 * <p>Thread-context: thread-safe; mutable state is guarded by the store monitor. Eviction callbacks
 * run after the atomic mutation and outside that monitor.
 */
public final class BoundedClientWorldStore implements ContentStore {

    /**
     * Notified when a blob is evicted, so repair can re-replicate it elsewhere.
     *
     * @Thread-context invoked while the store's lock is NOT held.
     */
    @FunctionalInterface
    public interface EvictionListener {
        /**
         * @param id     the evicted content id.
         * @param blob   the evicted bytes (so repair can move them before they are gone).
         */
        void onEvicted(ContentId id, byte[] blob);
    }

    private final HashService hashes = new HashService();
    private final StorageQuotaManager quota;
    private final Map<ContentId, byte[]> blobs = new LinkedHashMap<>();
    /** id → metadata; the access tick drives LRU eviction. */
    private final Map<ContentId, Meta> meta = new LinkedHashMap<>();
    private final EvictionListener listener;
    private long usedBytes;
    private long accessTick;

    /**
     * @param quota    the byte budget.
     * @param listener notified on eviction, or {@code null} for none.
     * @throws IllegalArgumentException if {@code quota} is null.
     * @Thread-context any thread (construction only).
     */
    public BoundedClientWorldStore(StorageQuotaManager quota, EvictionListener listener) {
        this.quota = Objects.requireNonNull(quota, "quota");
        this.listener = listener;
        this.usedBytes = 0L;
    }

    /** @return the quota manager reflecting current usage. */
    public synchronized StorageQuotaManager quota() {
        return new StorageQuotaManager(quota.budgetBytes(), usedBytes);
    }

    /**
     * Pin a blob: it becomes assigned-region current state and will never be evicted.
     *
     * @param id the blob to pin.
     * @return {@code true} if the blob is present and is now pinned.
     * @Thread-context any thread.
     */
    public synchronized boolean pin(ContentId id) {
        Meta m = meta.get(id);
        if (m == null) {
            return false;
        }
        meta.put(id, m.asPinned());
        return true;
    }

    /** @param id the blob to unpin. */
    public synchronized void unpin(ContentId id) {
        Meta m = meta.get(id);
        if (m != null) {
            meta.put(id, m.asUnpinned());
        }
    }

    /** @return {@code true} if {@code id} is currently pinned. */
    public synchronized boolean isPinned(ContentId id) {
        Meta m = meta.get(id);
        return m != null && m.pinned();
    }

    /**
     * Mark a blob accessed at the current tick, refreshing its LRU position.
     *
     * @param id  the blob.
     * @param now the access tick.
     * @Thread-context any thread.
     */
    public synchronized void touch(ContentId id, long now) {
        Meta m = meta.get(id);
        if (m != null) {
            meta.put(id, m.withAccess(now));
        }
    }

    @Override
    public ContentId put(byte[] blob) {
        Objects.requireNonNull(blob, "blob");
        ContentId id = ContentId.of(hashes, blob);
        List<Map.Entry<ContentId, byte[]>> evicted;
        synchronized (this) {
            if (blobs.containsKey(id)) {
                // Idempotent: storing the same bytes is a no-op, but refreshes access.
                meta.put(id, meta.get(id).withAccess(accessTick++));
                return id;
            }
            long needed = blob.length;
            if (needed > quota.budgetBytes()) {
                // A single blob larger than the whole budget can never be stored; do not evict pinned
                // content chasing the impossible.
                throw new QuotaException(
                        "blob " + needed + "B exceeds the budget " + quota.budgetBytes());
            }
            long over = usedBytes + needed - quota.budgetBytes();
            evicted = over > 0 ? evictToFree(over, id) : List.of();
            blobs.put(id, blob.clone());
            meta.put(id, new Meta(blob.length, false, accessTick++));
            usedBytes += needed;
        }
        notifyEvictions(evicted);
        return id;
    }

    @Override
    public synchronized Optional<byte[]> get(ContentId id) {
        byte[] blob = blobs.get(id);
        if (blob == null) {
            return Optional.empty();
        }
        // A read is an access: frequently-read content stays warm.
        meta.put(id, meta.get(id).withAccess(accessTick++));
        return Optional.of(blob.clone());
    }

    @Override
    public synchronized boolean has(ContentId id) {
        return blobs.containsKey(id);
    }

    @Override
    public synchronized int size() {
        return blobs.size();
    }

    /** @return the byte budget. */
    public long budgetBytes() {
        return quota.budgetBytes();
    }

    /** @return bytes currently stored. */
    public synchronized long usedBytes() {
        return usedBytes;
    }

    /**
     * Evict cold content until at least {@code bytesNeeded} are free, never touching pinned blobs or
     * {@code protect} (the blob about to be stored).
     */
    private List<Map.Entry<ContentId, byte[]>> evictToFree(
            long bytesNeeded, ContentId protect) {
        List<ArchiveEvictionPolicy.Entry> entries = new ArrayList<>(blobs.size());
        for (Map.Entry<ContentId, Meta> e : meta.entrySet()) {
            if (e.getKey().equals(protect)) {
                continue;
            }
            Meta m = e.getValue();
            entries.add(new ArchiveEvictionPolicy.Entry(e.getKey(), m.sizeBytes, m.pinned, m.lastAccess));
        }
        List<ContentId> victims = ArchiveEvictionPolicy.evictToFree(entries, bytesNeeded);
        List<Map.Entry<ContentId, byte[]>> evicted = new ArrayList<>(victims.size());
        for (ContentId id : victims) {
            byte[] blob = blobs.remove(id);
            meta.remove(id);
            usedBytes -= blob.length;
            evicted.add(Map.entry(id, blob));
        }
        return evicted;
    }

    /** Notify repair only after the atomic put/eviction mutation has released the store monitor. */
    private void notifyEvictions(List<Map.Entry<ContentId, byte[]>> evicted) {
        if (listener == null) {
            return;
        }
        for (Map.Entry<ContentId, byte[]> e : evicted) {
            listener.onEvicted(e.getKey(), e.getValue());
        }
    }

    /** Per-blob metadata. */
    private record Meta(long sizeBytes, boolean pinned, long lastAccess) {
        Meta asPinned() {
            return new Meta(sizeBytes, true, lastAccess);
        }
        Meta asUnpinned() {
            return new Meta(sizeBytes, false, lastAccess);
        }
        Meta withAccess(long now) {
            return new Meta(sizeBytes, pinned, now);
        }
    }
}
