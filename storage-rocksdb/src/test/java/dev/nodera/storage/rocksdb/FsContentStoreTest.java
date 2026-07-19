package dev.nodera.storage.rocksdb;

import dev.nodera.storage.ContentId;
import dev.nodera.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FsContentStore}: content-addressed round-trip, dedup, the Task 9 acceptance-#6 corrupt-
 * blob rejection (hash verification on read), and count recovery across reopen.
 */
class FsContentStoreTest {

    @TempDir
    Path dir;

    private FsContentStore store() {
        return new FsContentStore(dir, RocksFixtures.HASHES);
    }

    @Test
    void putGetRoundTripAndDedup() {
        FsContentStore store = store();
        byte[] blob = "hello archival tier".getBytes(StandardCharsets.UTF_8);
        ContentId id = store.put(blob);
        ContentId again = store.put(blob.clone());
        assertThat(again).isEqualTo(id);
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.has(id)).isTrue();
        assertThat(store.get(id)).contains(blob);
    }

    @Test
    void corruptBlobIsRejectedOnRead() throws Exception {
        FsContentStore store = store();
        ContentId id = store.put("payload".getBytes(StandardCharsets.UTF_8));
        Path blobFile;
        try (Stream<Path> files = Files.walk(dir.resolve("content"))) {
            blobFile = files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        Files.write(blobFile, "tampered".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> store.get(id))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("corrupt");
    }

    @Test
    void missingBlobReadsEmpty() {
        FsContentStore store = store();
        ContentId absent = ContentId.of(RocksFixtures.HASHES, "never stored".getBytes());
        assertThat(store.get(absent)).isEqualTo(Optional.empty());
        assertThat(store.has(absent)).isFalse();
    }

    @Test
    void sizeIsRecoveredOnReopen() {
        FsContentStore first = store();
        first.put("one".getBytes());
        first.put("two".getBytes());
        assertThat(store().size()).isEqualTo(2);
    }
}
