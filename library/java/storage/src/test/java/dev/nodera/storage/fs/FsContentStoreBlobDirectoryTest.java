package dev.nodera.storage.fs;

import dev.nodera.core.crypto.HashService;
import dev.nodera.storage.ContentId;
import dev.nodera.storage.StorageException;
import dev.nodera.storage.client.QuotaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * frontend M-1 — <b>the content store keeps its whole contract when the bytes are not on a
 * filesystem.</b>
 *
 * <p>Android 11+ refuses raw file access to every folder outside app-specific storage, so a folder
 * the user picks with the system file manager reaches the peer as a {@code content://} tree with no
 * path behind it. Rather than grow a second content store for that case — a fork of the byte
 * budget, the pin set and the hash checks, which is exactly the shape this repository keeps warning
 * about — {@link FsContentStore} writes through a {@link BlobDirectory} and Android supplies one.
 *
 * <p>This suite is that seam's proof, and it runs on the JVM gate against an in-memory directory:
 * no phone, no emulator, no {@code android.*}. What it pins is that the store's behaviour is a
 * property of the store and not of the filesystem — round trip, dedup, corruption refusal, the
 * L-62 budget, and L-58 relocation all hold with {@code java.nio.file} nowhere in the picture.
 */
final class FsContentStoreBlobDirectoryTest {

    private final HashService hashes = new HashService();

    /**
     * A blob directory that is not a filesystem — the JVM-side stand-in for an Android document
     * tree. Deliberately not a {@code Path} in disguise: it has no notion of a parent, a name
     * separator or an atomic move, which is the whole point.
     */
    private static final class MemoryBlobDirectory implements BlobDirectory {

        private final String where;
        private final Map<String, byte[]> blobs = new HashMap<>();
        private final Map<String, Long> modified = new HashMap<>();
        private String refuseWrites;
        private long clock = 1_000L;

        MemoryBlobDirectory(String where) {
            this.where = where;
        }

        /** Make every subsequent write fail with the platform's own words. */
        void refuseWritesWith(String reason) {
            this.refuseWrites = reason;
        }

        /** Corrupt a stored blob without changing its name — what a torn write leaves behind. */
        void corrupt(String name) {
            blobs.put(name, "not the bytes that hash to this name".getBytes());
        }

        @Override
        public boolean exists(String name) {
            return blobs.containsKey(name);
        }

        @Override
        public byte[] read(String name) {
            return blobs.get(name);
        }

        @Override
        public void write(String name, byte[] bytes) throws IOException {
            if (refuseWrites != null) {
                throw new IOException(refuseWrites);
            }
            blobs.put(name, bytes.clone());
            modified.put(name, clock++);
        }

        @Override
        public long delete(String name) {
            byte[] gone = blobs.remove(name);
            modified.remove(name);
            return gone == null ? -1L : gone.length;
        }

        @Override
        public List<Entry> list() {
            List<Entry> entries = new ArrayList<>();
            blobs.forEach((name, bytes) ->
                    entries.add(new Entry(name, bytes.length, modified.getOrDefault(name, 0L))));
            return entries;
        }

        @Override
        public void touch(String name, long epochMillis) {
            if (blobs.containsKey(name)) {
                modified.put(name, clock++);
            }
        }

        @Override
        public String location() {
            return where;
        }
    }

    private static byte[] blob(int seed, int size) {
        byte[] raw = new byte[size];
        new java.util.Random(seed).nextBytes(raw);
        return raw;
    }

    @Test
    void aStoreOnADirectoryThatIsNotAFilesystemStillRoundTripsAndDeduplicates() {
        FsContentStore store = new FsContentStore(
                new MemoryBlobDirectory("content://tree/primary%3ADocuments"), hashes);

        byte[] bytes = blob(1, 4096);
        ContentId id = store.put(bytes);

        assertThat(store.has(id)).isTrue();
        assertThat(store.get(id)).contains(bytes);
        assertThat(store.put(bytes))
                .as("content addressing does not depend on the back end")
                .isEqualTo(id);
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.remove(id)).isTrue();
        assertThat(store.size()).isZero();
    }

    @Test
    void aStoreWithNoFilesystemBehindItSaysSoRatherThanInventingAPath() {
        // The manifest index writes beside the blobs, so it must be able to ask "where are they?"
        // and get an answer it can act on. A document tree has no path; null is that answer, and
        // WorldArchiveService reads it to decide whether this node keeps a sidecar at all.
        FsContentStore store = new FsContentStore(
                new MemoryBlobDirectory("content://tree/primary%3ADocuments"), hashes);

        assertThat(store.contentRoot()).isNull();
        assertThat(store.contentLocation()).isEqualTo("content://tree/primary%3ADocuments");
    }

    @Test
    void aTornBlobIsReportedAsCorruptionRatherThanReturnedAsContent() {
        // This is what makes a back end without an atomic move acceptable: the name IS the hash, so
        // a half-written blob can never be served as if it were the world.
        MemoryBlobDirectory directory = new MemoryBlobDirectory("content://tree/x");
        FsContentStore store = new FsContentStore(directory, hashes);
        byte[] bytes = blob(2, 2048);
        ContentId id = store.put(bytes);

        directory.corrupt(id.hash().toHex());

        assertThatThrownBy(() -> store.get(id))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("corrupt");
    }

    @Test
    void aRefusedWriteCarriesTheBackEndsOwnWordsOutOfTheStore() {
        // "Android does not allow apps direct file access to this folder" is something the person
        // holding the phone can act on; "cannot store blob" is not. The store must not flatten it.
        MemoryBlobDirectory directory = new MemoryBlobDirectory("content://tree/x");
        FsContentStore store = new FsContentStore(directory, hashes);
        directory.refuseWritesWith("Android would not open this folder for writing.");

        assertThatThrownBy(() -> store.put(blob(3, 512)))
                .isInstanceOf(StorageException.class)
                .hasRootCauseMessage("Android would not open this folder for writing.");
        assertThat(store.size())
                .as("a refused write must not be counted as stored")
                .isZero();
    }

    @Test
    void theByteBudgetEvictsOldestColdFirstAndNeverAPinnedBlob() {
        // L-62 over a directory with no mtimes of its own. The policy lives in the store, so it
        // holds here exactly as it holds on disk.
        MemoryBlobDirectory directory = new MemoryBlobDirectory("content://tree/x");
        FsContentStore store = new FsContentStore(directory, hashes);
        store.setBudgetBytes(3_000);

        ContentId pinned = store.put(blob(1, 1_000));
        store.pin(pinned);
        ContentId cold = store.put(blob(2, 1_000));
        ContentId warm = store.put(blob(3, 1_000));

        store.put(blob(4, 1_000));

        assertThat(store.has(pinned)).as("a pinned blob is the world this node hosts").isTrue();
        assertThat(store.has(cold)).as("the oldest cold blob went first").isFalse();
        assertThat(store.has(warm)).isTrue();
    }

    @Test
    void onlyPinnedContentLeftMeansTheStoreRefusesRatherThanDropsIt() {
        MemoryBlobDirectory directory = new MemoryBlobDirectory("content://tree/x");
        FsContentStore store = new FsContentStore(directory, hashes);
        store.setBudgetBytes(2_000);
        store.pin(store.put(blob(1, 1_000)));
        store.pin(store.put(blob(2, 1_000)));

        assertThatThrownBy(() -> store.put(blob(3, 1_000)))
                .isInstanceOf(QuotaException.class);
    }

    @Test
    void contentRelocatesBetweenAFilesystemAndADocumentTreeInBothDirections(@TempDir Path tmp)
            throws IOException {
        // The migration this lane actually asks for: a phone that has been storing worlds in
        // app-private storage picks a folder in shared storage, and its seeding obligations move
        // with it (L-58) rather than being silently orphaned.
        FsContentStore store = new FsContentStore(tmp.resolve("private"), hashes);
        List<ContentId> ids = new ArrayList<>();
        List<byte[]> written = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            byte[] bytes = blob(i, 1024 + i);
            written.add(bytes);
            ids.add(store.put(bytes));
        }

        MemoryBlobDirectory tree = new MemoryBlobDirectory("content://tree/primary%3ADocuments");
        assertThat(store.relocateTo(tree)).isEqualTo(5);
        assertThat(store.contentRoot()).isNull();
        for (int i = 0; i < ids.size(); i++) {
            assertThat(store.get(ids.get(i))).contains(written.get(i));
        }
        try (var files = Files.walk(tmp.resolve("private"))) {
            assertThat(files.filter(Files::isRegularFile).count())
                    .as("a copy left behind is a stale duplicate of seeding obligations")
                    .isZero();
        }

        // …and back, because a user may change their mind or remove the SD card.
        Path back = tmp.resolve("private-again");
        assertThat(store.relocateTo(back)).isEqualTo(5);
        assertThat(store.contentRoot()).startsWith(back);
        FsContentStore reopened = new FsContentStore(back, hashes);
        assertThat(reopened.size()).isEqualTo(5);
        for (ContentId id : ids) {
            assertThat(reopened.has(id)).isTrue();
        }
    }

    @Test
    void aReopenedStoreCountsWhatTheDirectoryAlreadyHolds() {
        MemoryBlobDirectory tree = new MemoryBlobDirectory("content://tree/x");
        FsContentStore first = new FsContentStore(tree, hashes);
        ContentId id = first.put(blob(9, 777));

        FsContentStore second = new FsContentStore(tree, hashes);

        assertThat(second.size()).isEqualTo(1);
        assertThat(second.usedBytes()).isEqualTo(777);
        assertThat(second.has(id)).isTrue();
    }

    @Test
    void aNameThatIsNotAContentHashNeverBecomesAPath(@TempDir Path tmp) {
        // The filesystem back end is the one place a blob name becomes a path, and a name does not
        // always come from a ContentId: `list()` reads whatever is in the directory, and on Android
        // that directory is a folder the user picked and puts their own things in.
        PathBlobDirectory directory = new PathBlobDirectory(tmp);

        for (String hostile : new String[] {
            "../../../../etc/passwd",
            "/etc/passwd",
            "..".repeat(32),
            "ZZ" + "0".repeat(62),
            "0".repeat(63),
        }) {
            assertThatThrownBy(() -> directory.exists(hostile))
                    .as(hostile)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(BlobDirectory.isBlobName("0".repeat(64))).isTrue();
        assertThat(BlobDirectory.isBlobName(null)).isFalse();

        // …and the root itself must be somewhere somebody chose. A relative archive directory lands
        // wherever the worker was launched from — which is exactly how a `content://` tree, before
        // this lane understood one, became a folder called `content:` beside the working directory
        // with a hosted world inside it.
        assertThatThrownBy(() -> new PathBlobDirectory(Path.of("relative/archive")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void relocatingOntoTheSameLocationIsANoOp() {
        MemoryBlobDirectory tree = new MemoryBlobDirectory("content://tree/x");
        FsContentStore store = new FsContentStore(tree, hashes);
        ContentId id = store.put(blob(4, 300));

        assertThat(store.relocateTo(new MemoryBlobDirectory("content://tree/x"))).isZero();
        assertThat(store.has(id)).isTrue();
    }
}
