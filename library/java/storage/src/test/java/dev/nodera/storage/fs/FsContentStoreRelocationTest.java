package dev.nodera.storage.fs;

import dev.nodera.core.crypto.HashService;
import dev.nodera.storage.ContentId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-58 — <b>changing the archive directory relocates existing content without losing seeded
 * state.</b>
 *
 * <p>The setting used to be {@code restart_required}, and that framing hid the real problem: the
 * blobs a node holds <i>are</i> its seeding obligations. Re-pointing the path without moving them
 * would have left the world still listed on the tracker while every piece request missed — worse
 * than refusing the change. So the content moves first and the path is re-pointed only after.
 *
 * <p>Content addressing is what makes the move safe: a blob's filename is its hash, so a file
 * already present at the destination is byte-identical to the one being moved.
 */
final class FsContentStoreRelocationTest {

    private final HashService hashes = new HashService();

    private static byte[] blob(int seed, int size) {
        byte[] raw = new byte[size];
        new java.util.Random(seed).nextBytes(raw);
        return raw;
    }

    @Test
    void everyBlobSurvivesTheMoveAndIsServedFromTheNewLocation(@TempDir Path tmp) {
        Path oldRoot = tmp.resolve("old");
        Path newRoot = tmp.resolve("new");
        FsContentStore store = new FsContentStore(oldRoot, hashes);

        List<ContentId> ids = new ArrayList<>();
        List<byte[]> blobs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            byte[] b = blob(i, 4096 + i);
            blobs.add(b);
            ids.add(store.put(b));
        }
        assertThat(store.size()).isEqualTo(12);

        assertThat(store.relocateTo(newRoot))
                .as("every blob moved, not merely the path re-pointed")
                .isEqualTo(12);

        assertThat(store.contentRoot()).startsWith(newRoot);
        for (int i = 0; i < ids.size(); i++) {
            assertThat(store.has(ids.get(i))).isTrue();
            assertThat(store.get(ids.get(i)))
                    .as("the bytes are intact — get() re-hashes, so a corrupt move would throw")
                    .contains(blobs.get(i));
        }
        assertThat(store.size()).isEqualTo(12);
    }

    @Test
    void nothingIsLeftBehindAtTheOldLocation(@TempDir Path tmp) {
        Path oldRoot = tmp.resolve("old");
        FsContentStore store = new FsContentStore(oldRoot, hashes);
        store.put(blob(1, 2048));
        store.put(blob(2, 2048));

        store.relocateTo(tmp.resolve("new"));

        assertThat(remainingBlobs(oldRoot.resolve("content")))
                .as("a copy left behind is a stale duplicate of seeding obligations")
                .isZero();
    }

    @Test
    void aStoreOpenedOnTheNewDirectoryFindsTheRelocatedContent(@TempDir Path tmp) {
        // The durability half: relocation is not just an in-memory re-point. A fresh process
        // pointed at the new directory must see exactly what the node had promised the swarm.
        Path oldRoot = tmp.resolve("old");
        Path newRoot = tmp.resolve("new");
        FsContentStore store = new FsContentStore(oldRoot, hashes);
        ContentId id = store.put(blob(7, 8192));
        store.relocateTo(newRoot);

        FsContentStore reopened = new FsContentStore(newRoot, hashes);
        assertThat(reopened.size()).isEqualTo(1);
        assertThat(reopened.has(id)).isTrue();
    }

    @Test
    void relocatingOntoTheSameDirectoryIsANoOp(@TempDir Path tmp) {
        FsContentStore store = new FsContentStore(tmp, hashes);
        ContentId id = store.put(blob(3, 1024));

        assertThat(store.relocateTo(tmp)).isZero();
        assertThat(store.has(id)).isTrue();
    }

    @Test
    void anExistingIdenticalBlobAtTheDestinationIsNotAConflict(@TempDir Path tmp) {
        // Content addressing: the same name means the same bytes, so a destination that already
        // holds a blob (a previous partial relocation, a shared directory) is merged, not refused.
        Path oldRoot = tmp.resolve("old");
        Path newRoot = tmp.resolve("new");
        byte[] shared = blob(9, 3000);

        FsContentStore destination = new FsContentStore(newRoot, hashes);
        ContentId id = destination.put(shared);

        FsContentStore source = new FsContentStore(oldRoot, hashes);
        source.put(shared);
        source.put(blob(10, 3000));

        assertThat(source.relocateTo(newRoot)).isEqualTo(2);
        assertThat(source.has(id)).isTrue();
    }

    private static long remainingBlobs(Path dir) {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (var files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".bin"))
                    .count();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
