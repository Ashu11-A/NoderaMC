package dev.nodera.storage.rocksdb;

import dev.nodera.core.crypto.HashService;
import dev.nodera.storage.ContentId;
import dev.nodera.storage.client.QuotaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L-62 — the store the worker actually uses must honour a byte budget.
 *
 * <p>L-37 retired on {@code BoundedClientWorldStore}, which nothing constructs: the running node
 * puts blobs in {@link FsContentStore}. The policy was written and tested; only the wiring was
 * missing. These tests exercise it against a real directory, because the interesting parts of a
 * disk store — surviving a restart, an out-of-band delete, LRU order from mtime — do not exist in
 * an in-memory fake.
 */
final class FsContentStoreQuotaTest {

    private final HashService hashes = new HashService();

    private static byte[] blob(int seed, int size) {
        byte[] raw = new byte[size];
        new java.util.Random(seed).nextBytes(raw);
        return raw;
    }

    /** Age a blob so LRU order is deterministic rather than dependent on write speed. */
    private static void ageBy(FsContentStore store, ContentId id, long millis) throws IOException {
        Path file = fileOf(store, id);
        Files.setLastModifiedTime(file,
                FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() - millis));
    }

    private static Path fileOf(FsContentStore store, ContentId id) throws IOException {
        String name = id.hash().toHex() + ".bin";
        try (Stream<Path> files = Files.walk(store.contentRoot())) {
            return files.filter(p -> p.getFileName().toString().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("no file for " + id));
        }
    }

    @Test
    void aPutOverTheBudgetEvictsTheOldestColdBlob(@TempDir Path dir) throws IOException {
        FsContentStore store = new FsContentStore(dir, hashes);
        store.setBudgetBytes(3_000);

        ContentId oldest = store.put(blob(1, 1_000));
        ContentId middle = store.put(blob(2, 1_000));
        ContentId newest = store.put(blob(3, 1_000));
        ageBy(store, oldest, 30_000);
        ageBy(store, middle, 20_000);
        ageBy(store, newest, 10_000);
        assertThat(store.usedBytes()).isEqualTo(3_000);

        ContentId arriving = store.put(blob(4, 1_000));

        assertThat(store.has(oldest)).as("the coldest blob made room").isFalse();
        assertThat(store.has(middle)).isTrue();
        assertThat(store.has(newest)).isTrue();
        assertThat(store.has(arriving)).isTrue();
        assertThat(store.usedBytes()).isEqualTo(3_000);
        assertThat(store.size()).isEqualTo(3);
    }

    @Test
    void pinnedContentIsNeverEvictedAndAFullStoreOfItRefusesThePut(@TempDir Path dir) {
        FsContentStore store = new FsContentStore(dir, hashes);
        store.setBudgetBytes(2_000);

        ContentId hosted = store.put(blob(5, 1_000));
        ContentId assigned = store.put(blob(6, 1_000));
        store.pin(hosted);
        store.pin(assigned);

        assertThatThrownBy(() -> store.put(blob(7, 1_000)))
                .as("dropping an assigned region's current state would lose the node its "
                        + "committee duties — refuse the put instead")
                .isInstanceOf(QuotaException.class)
                .hasMessageContaining("pinned");

        assertThat(store.has(hosted)).isTrue();
        assertThat(store.has(assigned)).isTrue();
        assertThat(store.isPinned(hosted)).isTrue();

        // Unpinning makes it ordinary cold content again.
        store.unpin(assigned);
        store.put(blob(7, 1_000));
        assertThat(store.has(assigned)).isFalse();
        assertThat(store.has(hosted)).as("still pinned, still here").isTrue();
    }

    @Test
    void aBlobLargerThanTheWholeBudgetIsRefusedWithoutEvictingAnything(@TempDir Path dir) {
        FsContentStore store = new FsContentStore(dir, hashes);
        store.setBudgetBytes(2_000);
        ContentId held = store.put(blob(8, 500));

        assertThatThrownBy(() -> store.put(blob(9, 5_000)))
                .isInstanceOf(QuotaException.class)
                .hasMessageContaining("exceeds the budget");

        assertThat(store.has(held))
                .as("no eviction chasing an impossible put")
                .isTrue();
        assertThat(store.usedBytes()).isEqualTo(500);
    }

    @Test
    void everyEvictionIsSignalledWithItsBytesSoRepairCanReplaceIt(@TempDir Path dir)
            throws IOException {
        FsContentStore store = new FsContentStore(dir, hashes);
        store.setBudgetBytes(2_000);
        List<ContentId> evicted = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        store.setEvictionListener((id, bytes) -> {
            evicted.add(id);
            sizes.add(bytes.length);
        });

        ContentId cold = store.put(blob(10, 1_000));
        store.put(blob(11, 1_000));
        ageBy(store, cold, 60_000);
        store.put(blob(12, 1_000));

        assertThat(evicted).extracting(ContentId::hash).containsExactly(cold.hash());
        assertThat(sizes)
                .as("repair is handed the bytes, not just the id — the replica must be re-created "
                        + "before it is gone everywhere")
                .containsExactly(1_000);
    }

    @Test
    void aReadKeepsContentWarm(@TempDir Path dir) throws IOException {
        FsContentStore store = new FsContentStore(dir, hashes);
        store.setBudgetBytes(2_000);

        ContentId readOften = store.put(blob(13, 1_000));
        ContentId writtenLater = store.put(blob(14, 1_000));
        ageBy(store, readOften, 60_000);
        ageBy(store, writtenLater, 30_000);

        assertThat(store.get(readOften)).isPresent();   // the touch that saves it
        store.put(blob(15, 1_000));

        assertThat(store.has(readOften)).as("recently read, so not the coldest").isTrue();
        assertThat(store.has(writtenLater)).isFalse();
    }

    @Test
    void theBudgetSurvivesAReopenBecauseUsageIsReadOffTheDisk(@TempDir Path dir) {
        FsContentStore first = new FsContentStore(dir, hashes);
        first.setBudgetBytes(3_000);
        first.put(blob(16, 1_000));
        first.put(blob(17, 1_000));
        assertThat(first.usedBytes()).isEqualTo(2_000);

        // A restart: nothing is carried over in memory, so the ceiling has to be recomputed from
        // what is actually on disk or the first put after a restart would blow straight past it.
        FsContentStore reopened = new FsContentStore(dir, hashes);
        assertThat(reopened.usedBytes()).isEqualTo(2_000);
        assertThat(reopened.size()).isEqualTo(2);

        reopened.setBudgetBytes(3_000);
        reopened.put(blob(18, 1_000));
        assertThat(reopened.usedBytes()).isEqualTo(3_000);
        reopened.put(blob(19, 1_000));
        assertThat(reopened.usedBytes())
                .as("still bounded after the reopen")
                .isEqualTo(3_000);
    }

    @Test
    void anUnboundedStoreEvictsNothing(@TempDir Path dir) {
        // The default, and it must stay the default: this store also holds a node's OWN hosted
        // world, where "evict to fit" is the wrong answer.
        FsContentStore store = new FsContentStore(dir, hashes);
        assertThat(store.budgetBytes()).isZero();

        List<ContentId> all = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            all.add(store.put(blob(100 + i, 10_000)));
        }
        assertThat(all).allMatch(store::has);
        assertThat(store.usedBytes()).isEqualTo(200_000);
    }

    @Test
    void removingContentGivesTheBytesBack(@TempDir Path dir) {
        FsContentStore store = new FsContentStore(dir, hashes);
        store.setBudgetBytes(2_000);
        ContentId id = store.put(blob(20, 1_500));
        store.pin(id);

        assertThat(store.remove(id)).isTrue();
        assertThat(store.usedBytes()).isZero();
        assertThat(store.isPinned(id)).as("a removed blob does not keep a pin alive").isFalse();

        // And the freed budget is genuinely usable again.
        store.put(blob(21, 1_900));
        assertThat(store.usedBytes()).isEqualTo(1_900);
    }
}
