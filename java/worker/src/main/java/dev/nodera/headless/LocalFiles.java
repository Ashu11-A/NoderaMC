package dev.nodera.headless;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Writing a worker-owned file the way the identity store already does: through a temporary file with
 * owner-only permissions, moved into place atomically.
 *
 * <p>Extracted because three things on this node now hold state a crash must not corrupt or a
 * neighbouring account must not read — the node identity, each world's private key, and the world
 * registry — and each of them getting its own copy of the temp-then-move dance is how one of them
 * ends up subtly different from the others.
 *
 * <p>Atomic move matters for the same reason everywhere: a crash midway through must leave the
 * <b>previous</b> file intact. A truncated world key is a world whose administrator can no longer
 * prove anything; a truncated registry is a peer that forgets what it was keeping alive.
 *
 * @Thread-context blocking IO on the caller's thread; one writer per file at a time.
 */
final class LocalFiles {

    private LocalFiles() {
    }

    /** POSIX {@code rw-------} — owner-only. */
    private static Set<PosixFilePermission> ownerOnly() {
        return Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    /**
     * Write {@code content} to {@code file}, creating parents, atomically replacing any existing
     * file, with owner-only permissions where the filesystem supports them.
     *
     * @param file    the destination.
     * @param content the bytes.
     * @throws UncheckedIOException if the write fails.
     */
    static void writeAtomically(Path file, byte[] content) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, file.getFileName().toString() + ".", ".tmp",
                    PosixFilePermissions.asFileAttribute(ownerOnly()));
            Files.write(temp, content);
            restrict(temp);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrict(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write " + file, e);
        }
    }

    /** Apply owner-only permissions where supported; tolerate filesystems that reject it. */
    private static void restrict(Path path) {
        try {
            Files.setPosixFilePermissions(path, ownerOnly());
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem (Windows, some network mounts): the file is still written and
            // access control is the OS's. Not worth failing a save over.
        }
    }
}
