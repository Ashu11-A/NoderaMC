package dev.nodera.storage.io;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * The one atomic file-write primitive (same-directory temp file + {@code ATOMIC_MOVE}, falling
 * back to {@code REPLACE_EXISTING} on filesystems without atomic moves). Content is complete in
 * the temporary file before any replacement begins; atomic-capable providers expose either the old
 * bytes or the new bytes. Extracted from the independent copies that grew in FsContentStore,
 * PersistentIdentityStore, CachedPeerStore, LocalFiles, and NoderaWorldStore.
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
        write(target, bytes, false);
    }

    /**
     * Atomically replace {@code target}, creating it owner-readable and owner-writable only on
     * POSIX filesystems. A provider that advertises POSIX but rejects creation-time permissions
     * fails closed before any content is written.
     *
     * @throws IOException when permissions cannot be secured, or the write or move fails
     */
    public static void writeOwnerOnly(Path target, byte[] bytes) throws IOException {
        write(target, bytes, true);
    }

    private static void write(Path target, byte[] bytes, boolean ownerOnly) throws IOException {
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp = createTempFile(dir, target.getFileName().toString(), ownerOnly);
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException failure) {
            deleteTempAfterFailure(temp, failure);
            throw failure;
        }
    }

    private static Path createTempFile(Path dir, String prefix, boolean ownerOnly) throws IOException {
        return createTempFile(dir, prefix, ownerOnly, Files::getFileStore);
    }

    static Path createTempFile(Path dir, String prefix, boolean ownerOnly,
            FileStoreLookup fileStores) throws IOException {
        if (!ownerOnly) {
            return Files.createTempFile(dir, prefix, ".tmp");
        }

        FileStore store;
        try {
            store = fileStores.get(dir);
        } catch (SecurityException cannotInspect) {
            // Android's UnixFileSystemProvider denies getFileStore even inside app-private storage.
            // Try secure creation directly; only providers that reject POSIX attributes fall back.
            return createOwnerOnlyWithoutStoreInspection(dir, prefix);
        }
        boolean supportsPosix;
        try {
            supportsPosix = store.supportsFileAttributeView(PosixFileAttributeView.class)
                    || store.supportsFileAttributeView("posix");
        } catch (UnsupportedOperationException cannotInspect) {
            throw new IOException("cannot determine file permissions for " + dir, cannotInspect);
        }
        if (!supportsPosix) {
            return Files.createTempFile(dir, prefix, ".tmp");
        }

        Set<PosixFilePermission> permissions = Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        try {
            return Files.createTempFile(dir, prefix, ".tmp",
                    PosixFilePermissions.asFileAttribute(permissions));
        } catch (UnsupportedOperationException permissionsRejected) {
            throw new IOException("POSIX file store rejected owner-only permissions for " + dir,
                    permissionsRejected);
        }
    }

    static Path createOwnerOnlyWithoutStoreInspection(Path dir, String prefix) throws IOException {
        return createOwnerOnlyWithoutStoreInspection(dir, prefix,
                (parent, stem) -> Files.createTempFile(parent, stem, ".tmp",
                        PosixFilePermissions.asFileAttribute(Set.of(
                                PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE))));
    }

    static Path createOwnerOnlyWithoutStoreInspection(Path dir, String prefix,
            OwnerOnlyTempCreator creator) throws IOException {
        try {
            return creator.create(dir, prefix);
        } catch (UnsupportedOperationException permissionsUnsupported) {
            throw new IOException("cannot secure owner-only permissions for " + dir,
                    permissionsUnsupported);
        }
    }

    @FunctionalInterface
    interface FileStoreLookup {
        FileStore get(Path path) throws IOException;
    }

    @FunctionalInterface
    interface OwnerOnlyTempCreator {
        Path create(Path dir, String prefix) throws IOException;
    }

    static void deleteTempAfterFailure(Path temp, Throwable failure) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException | RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
