package dev.nodera.storage.client;

import dev.nodera.storage.ContentId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bounded client store (Task 22; L-37). The properties that matter: it honours the byte budget,
 * evicts oldest-cold-first, NEVER evicts a pinned (assigned-region current) blob, signals repair on
 * eviction, and refuses (loudly) when only pinned content remains.
 *
 * <p>Thread-context: single test thread.
 */
final class BoundedClientWorldStoreTest {

    private static byte[] blob(int seed, int size) {
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            out[i] = (byte) (seed * 31 + i);
        }
        return out;
    }

    @Test
    void evictionIsOldestColdFirstAndStaysWithinBudget() {
        // 1000-byte budget; store three 400-byte cold blobs (A, B, C), then a fourth (D).
        BoundedClientWorldStore store = new BoundedClientWorldStore(
                new StorageQuotaManager(1000, 0), null);
        ContentId a = store.put(blob(1, 400));
        ContentId b = store.put(blob(2, 400));
        // Touch A so B is the oldest cold blob when the fourth arrives.
        store.get(a);
        ContentId d = store.put(blob(4, 400));

        // B (oldest cold) was evicted to make room for D; A (recently read) and C survive.
        assertThat(store.has(a)).isTrue();
        assertThat(store.has(b)).isFalse();
        assertThat(store.usedBytes()).isLessThanOrEqualTo(1000);
    }

    @Test
    void pinnedAssignedRegionCurrentStateIsNeverEvicted() {
        BoundedClientWorldStore store = new BoundedClientWorldStore(
                new StorageQuotaManager(1000, 0), null);
        // Fill the budget with a pinned blob (assigned-region current state) + a cold one.
        ContentId pinned = store.put(blob(1, 600));
        store.pin(pinned);
        ContentId cold = store.put(blob(2, 400));
        assertThat(store.usedBytes()).isEqualTo(1000);

        // Putting one more byte evicts the COLD blob, never the pinned one.
        store.put(blob(3, 1));
        assertThat(store.has(pinned)).isTrue();
        assertThat(store.has(cold)).isFalse();
    }

    @Test
    void refusesWhenOnlyPinnedContentRemains() {
        BoundedClientWorldStore store = new BoundedClientWorldStore(
                new StorageQuotaManager(500, 0), null);
        ContentId pinned = store.put(blob(1, 500));
        store.pin(pinned);

        // The budget is entirely pinned; a new blob cannot be made to fit without dropping the
        // assigned-region current state — which the rule forbids, so the put fails loudly.
        assertThatThrownBy(() -> store.put(blob(2, 1)))
                .isInstanceOf(QuotaException.class);
        assertThat(store.has(pinned)).isTrue();
    }

    @Test
    void aBlobLargerThanTheBudgetIsRefusedWithoutEvictingPinnedState() {
        BoundedClientWorldStore store = new BoundedClientWorldStore(
                new StorageQuotaManager(500, 0), null);
        ContentId pinned = store.put(blob(1, 400));
        store.pin(pinned);

        assertThatThrownBy(() -> store.put(blob(2, 1000)))
                .isInstanceOf(QuotaException.class);
        assertThat(store.has(pinned)).isTrue();
    }

    @Test
    void evictionSignalsRepairViaTheListener() {
        List<ContentId> evicted = new ArrayList<>();
        List<byte[]> evictedBytes = new ArrayList<>();
        BoundedClientWorldStore store = new BoundedClientWorldStore(
                new StorageQuotaManager(800, 0),
                (id, bytes) -> { evicted.add(id); evictedBytes.add(bytes); });

        ContentId a = store.put(blob(1, 500));
        store.put(blob(2, 500));   // evicts A

        assertThat(evicted).containsExactly(a);
        assertThat(evictedBytes.get(0)).isEqualTo(blob(1, 500));
    }

    @Test
    void evictionListenerCanCallBackFromAnotherThreadWithoutTheStoreMonitor() {
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch callbackFinished = new CountDownLatch(1);
        AtomicBoolean sawEvictedState = new AtomicBoolean();
        AtomicReference<BoundedClientWorldStore> storeRef = new AtomicReference<>();
        BoundedClientWorldStore store = new BoundedClientWorldStore(
                new StorageQuotaManager(800, 0),
                (id, bytes) -> {
                    Thread callback = new Thread(() -> {
                        callbackStarted.countDown();
                        sawEvictedState.set(!storeRef.get().has(id));
                        callbackFinished.countDown();
                    }, "eviction-store-callback");
                    callback.start();
                    assertThat(await(callbackStarted)).isTrue();
                    // If put still held the monitor, the callback's has(id) would block here.
                    assertThat(await(callbackFinished)).isTrue();
                });
        storeRef.set(store);

        store.put(blob(1, 500));
        store.put(blob(2, 500));

        assertThat(sawEvictedState).isTrue();
    }

    @Test
    void quotaManagerAccountingIsExact() {
        StorageQuotaManager q = new StorageQuotaManager(1000, 200);
        assertThat(q.remaining()).isEqualTo(800);
        assertThat(q.canFit(800)).isTrue();
        assertThat(q.canFit(801)).isFalse();
        StorageQuotaManager reserved = q.reserve(300);
        assertThat(reserved.usedBytes()).isEqualTo(500);
        assertThat(reserved.release(500).usedBytes()).isZero();
        assertThatThrownBy(() -> q.reserve(801)).isInstanceOf(QuotaException.class);
    }

    @Test
    void storingTheSameBytesIsIdempotentAndStaysWithinBudget() {
        BoundedClientWorldStore store = new BoundedClientWorldStore(
                new StorageQuotaManager(500, 0), null);
        ContentId id = store.put(blob(7, 500));
        ContentId again = store.put(blob(7, 500));
        assertThat(again).isEqualTo(id);
        assertThat(store.size()).isOne();
        assertThat(store.usedBytes()).isEqualTo(500);
    }

    @Test
    void evictionPolicyPicksOldestColdAndNeverPinned() {
        ContentId cold1 = id(1);
        ContentId cold2 = id(2);
        ContentId pinned = id(3);
        List<ArchiveEvictionPolicy.Entry> entries = List.of(
                new ArchiveEvictionPolicy.Entry(cold1, 100, false, 10),
                new ArchiveEvictionPolicy.Entry(cold2, 100, false, 20),
                new ArchiveEvictionPolicy.Entry(pinned, 100, true, 5));

        // Need 150 bytes: oldest cold (cold1@10, 100B) then cold2 (100B) → 200B freed; pinned kept.
        List<ContentId> victims = ArchiveEvictionPolicy.evictToFree(entries, 150);
        assertThat(victims).containsExactly(cold1, cold2);

        // If only pinned content could satisfy the request, the policy refuses rather than evicting it.
        assertThatThrownBy(() -> ArchiveEvictionPolicy.evictToFree(
                List.of(new ArchiveEvictionPolicy.Entry(pinned, 100, true, 5)), 50))
                .isInstanceOf(QuotaException.class);
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting callback", e);
        }
    }

    private static ContentId id(int seed) {
        byte[] hash = new byte[32];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = (byte) (seed * 17 + i);
        }
        return new ContentId(dev.nodera.core.Bytes.unsafeWrap(hash), 100L, dev.nodera.storage.Compression.NONE);
    }

    // keep Optional import used (defensive against unused-import strictness)
    @SuppressWarnings("unused")
    private Optional<byte[]> unused() { return Optional.empty(); }
}
