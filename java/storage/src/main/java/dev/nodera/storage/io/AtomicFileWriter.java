package dev.nodera.storage.io;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The one atomic file-write primitive (same-directory temp file + {@code ATOMIC_MOVE}, falling
 * back to {@code REPLACE_EXISTING} on filesystems without atomic moves). A crashed write can
 * never leave a torn file under a valid name — readers see the old bytes or the new bytes,
 * nothing in between. Extracted from the four independent copies that grew in FsContentStore,
 * PersistentIdentityStore, CachedPeerStore, and NoderaWorldStore.
 *
 * @Thread-context any thread; callers serialize writes to the same target themselves.
 */
public final class AtomicFileWriter {

    private AtomicFileWriter() {
    }

    /**
     * Atomically replace {@code target} with {@code bytes}, creating parent directories as
     * needed.
     *
     * @throws IOException when the write or move fails; the temp file is best-effort removed
     */
    public static void write(Path target, byte[] bytes) throws IOException {
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }
}
