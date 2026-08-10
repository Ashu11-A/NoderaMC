package dev.nodera.headless;

import dev.nodera.core.crypto.HashService;
import dev.nodera.storage.ContentId;
import dev.nodera.storage.StorageException;
import dev.nodera.storage.fs.BlobDirectory;
import dev.nodera.storage.fs.FsContentStore;
import dev.nodera.storage.fs.PathBlobDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * frontend M-1 — <b>the worker's half of a folder the user picked with Android's file manager.</b>
 *
 * <p>The app could already open the system picker and persist the grant; what it could not do was
 * make the folder usable, because the peer writes with {@code java.nio.file} and Android 11+ has no
 * file behind a shared folder. {@link SafBlobDirectory} is the missing half: the same content store,
 * writing through the Storage Access Framework.
 *
 * <p>Everything here runs on the JVM gate. The bridge is resolved by name exactly as it is on a
 * handset — {@link FakeDocumentTree} stands in for {@code dev.nodera.app.NoderaSafBlobs} — so a
 * drifted method signature fails here rather than as a {@code NoSuchMethodError} on somebody's
 * phone. What it cannot prove is that a real {@code DocumentsContract} behaves like the stand-in;
 * that is the physical exit test named in {@code docs/frontend/LIMITATIONS.md}.
 */
final class SafBlobDirectoryTest {

    private static final String TREE =
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments";

    private final HashService hashes = new HashService();

    @BeforeEach
    void installTheFakeTree() {
        FakeDocumentTree.reset();
        System.setProperty(SafBlobDirectory.BRIDGE_CLASS_PROPERTY,
                FakeDocumentTree.class.getName());
    }

    @AfterEach
    void uninstallTheFakeTree() {
        System.clearProperty(SafBlobDirectory.BRIDGE_CLASS_PROPERTY);
        FakeDocumentTree.reset();
    }

    @Test
    void theBridgeIsFoundByNameAndEveryMethodSignatureStillResolves() {
        // The R8 trap in one assertion. The worker reaches the app's class by name across the
        // DexClassLoader boundary, so nothing at compile time checks that the methods exist; a
        // renamed parameter type is invisible until the peer stores its first blob on a device.
        Optional<SafBlobDirectory.Bridge> bridge = SafBlobDirectory.Reflective.installed();

        assertTrue(bridge.isPresent(), "the named bridge class must resolve");
        assertTrue(bridge.get().write(TREE, name(1), "hello".getBytes()));
        assertTrue(bridge.get().exists(TREE, name(1)));
        assertEquals("hello", new String(bridge.get().read(TREE, name(1))));
        assertNotNull(bridge.get().list(TREE));
        assertEquals(5L, bridge.get().delete(TREE, name(1)));
        assertEquals(-1L, bridge.get().delete(TREE, name(1)));
        bridge.get().touch(TREE, name(1), 1L);
        assertNotNull(bridge.get().lastError());
    }

    @Test
    void aDesktopWorkerHasNoBridgeAndIsNotSupposedTo() {
        System.clearProperty(SafBlobDirectory.BRIDGE_CLASS_PROPERTY);

        assertFalse(SafBlobDirectory.Reflective.isInstalled(),
                "dev.nodera.app.NoderaSafBlobs exists only inside the Android app");
    }

    @Test
    void aContentStoreOverAPickedFolderRoundTripsAndCountsWhatIsThere() throws IOException {
        BlobDirectory directory = ArchiveDirectories.open(TREE);
        FsContentStore store = new FsContentStore(directory, hashes);

        byte[] world = new byte[64_000];
        new java.util.Random(3L).nextBytes(world);
        ContentId id = store.put(world);

        assertEquals(1, FakeDocumentTree.blobCount(), "the bytes are in the picked folder");
        assertNotNull(FakeDocumentTree.stored(TREE + "/" + id.hash().toHex()));
        assertTrue(store.get(id).isPresent());
        assertEquals(1, directory.list().size());
        assertNull(store.contentRoot(), "a document tree has no filesystem path");
        assertEquals(TREE, store.contentLocation());
    }

    @Test
    void aRefusedWriteSurfacesThePlatformsOwnSentence() {
        FakeDocumentTree.refuseWrites(
                "Android does not allow apps direct file access to this folder.");
        FsContentStore store = new FsContentStore(ArchiveDirectories.open(TREE), hashes);

        StorageException refused = assertThrows(StorageException.class,
                () -> store.put("archive".getBytes()));

        // Not "cannot store blob". The person holding the phone gets the platform's words, which
        // are the only ones that tell them what to do next.
        assertTrue(refused.getCause().getMessage()
                        .contains("Android does not allow apps direct file access"),
                refused.getCause().getMessage());
    }

    @Test
    void whateverElseIsInThePickedFolderIsNotCountedAsContent() throws IOException {
        // A folder the user chose holds the user's things. A holiday photo is not a world archive,
        // and counting it against the byte budget — or offering it to the swarm — would be worse
        // than not supporting the folder at all.
        SafBlobDirectory directory = new SafBlobDirectory(TREE, new SafBlobDirectory.Bridge() {
            @Override
            public boolean exists(String treeUri, String name) {
                return false;
            }

            @Override
            public byte[] read(String treeUri, String name) {
                return null;
            }

            @Override
            public boolean write(String treeUri, String name, byte[] bytes) {
                return true;
            }

            @Override
            public long delete(String treeUri, String name) {
                return -1L;
            }

            @Override
            public String list(String treeUri) {
                return "IMG_0421.jpg\t3145728\t17\n"
                        + name(4) + "\t2048\t42\n"
                        + name(5) + ".tmp\t99\t43\n"
                        + "\n"
                        + name(6) + "\tnot-a-number\t44\n";
            }

            @Override
            public void touch(String treeUri, String name, long epochMillis) {
            }

            @Override
            public String lastError() {
                return "";
            }
        });

        List<BlobDirectory.Entry> entries = directory.list();

        assertEquals(1, entries.size(), entries.toString());
        assertEquals(name(4), entries.get(0).name());
        assertEquals(2048L, entries.get(0).sizeBytes());
    }

    @Test
    void aFolderThatCannotBeListedIsAnErrorRatherThanAnEmptyFolder() {
        // "Empty" and "unreadable" are different answers, and confusing them would make a store
        // that holds a hosted world report zero and then evict nothing while the budget says it is
        // over — or worse, announce that it holds nothing.
        SafBlobDirectory.Bridge silent = new SafBlobDirectory.Bridge() {
            @Override
            public boolean exists(String treeUri, String name) {
                return false;
            }

            @Override
            public byte[] read(String treeUri, String name) {
                return null;
            }

            @Override
            public boolean write(String treeUri, String name, byte[] bytes) {
                return false;
            }

            @Override
            public long delete(String treeUri, String name) {
                return -1L;
            }

            @Override
            public String list(String treeUri) {
                return null;
            }

            @Override
            public void touch(String treeUri, String name, long epochMillis) {
            }

            @Override
            public String lastError() {
                return "The folder is no longer available.";
            }
        };

        StorageException failed = assertThrows(StorageException.class,
                () -> new FsContentStore(new SafBlobDirectory(TREE, silent), hashes));

        assertTrue(failed.getCause().getMessage().contains("The folder is no longer available."),
                failed.getCause().getMessage());
    }

    @Test
    void aDirectoryPathIsStillJustADirectoryAndNothingAboutItChanged(@TempDir Path tmp)
            throws IOException {
        // The regression guard that matters most: every desktop peer takes this branch, and it must
        // be byte-for-byte the store it has always been — same layout on disk, same atomic write.
        System.clearProperty(SafBlobDirectory.BRIDGE_CLASS_PROPERTY);
        BlobDirectory directory = ArchiveDirectories.open(tmp.toString());

        assertInstanceOf(PathBlobDirectory.class, directory);
        FsContentStore store = new FsContentStore(directory, hashes);
        ContentId id = store.put("desktop".getBytes());

        Path expected = tmp.resolve("content")
                .resolve(id.hash().toHex().substring(0, 2))
                .resolve(id.hash().toHex().substring(2, 4))
                .resolve(id.hash().toHex() + ".bin");
        assertTrue(Files.exists(expected), expected.toString());
        assertEquals(tmp.resolve("content"), store.contentRoot());
        assertEquals(0, FakeDocumentTree.blobCount(), "nothing went near the bridge");
    }

    @Test
    void aDocumentTreeWithNoBridgeIsRefusedRatherThanSilentlyStoredSomewhereElse() {
        // The failure this refuses to become: a worker that quietly kept using its private
        // directory would show the picked folder as in use while storing nothing in it, which is
        // the exact defect M-1 describes.
        System.clearProperty(SafBlobDirectory.BRIDGE_CLASS_PROPERTY);

        StorageException refused = assertThrows(StorageException.class,
                () -> ArchiveDirectories.open(TREE));

        assertTrue(refused.getMessage().contains("no storage bridge"), refused.getMessage());
    }

    @Test
    void onlyAContentUriIsTreatedAsADocumentTree() {
        assertTrue(ArchiveDirectories.isDocumentTree(TREE));
        assertFalse(ArchiveDirectories.isDocumentTree("/home/player/.nodera/archive"));
        assertFalse(ArchiveDirectories.isDocumentTree("/storage/emulated/0/Documents"));
        assertFalse(ArchiveDirectories.isDocumentTree(null));
    }

    private static String name(int seed) {
        return new HashService().sha256(("blob-" + seed).getBytes()).toHex();
    }
}
