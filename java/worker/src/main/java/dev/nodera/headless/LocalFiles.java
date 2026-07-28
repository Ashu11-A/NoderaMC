package dev.nodera.headless;

import dev.nodera.storage.io.AtomicFileWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * Worker-state wrapper over the shared owner-only atomic file writer.
 *
 * <p>The node identity, each world's private key, tombstones, and the world registry all need the
 * same permissions, replacement, and failure-cleanup policy. That policy lives once in
 * {@link AtomicFileWriter}; this class only translates checked IO failures for worker stores.
 *
 * <p>A truncated world key is a world whose administrator can no longer prove anything; a truncated
 * registry is a peer that forgets what it was keeping alive.
 *
 * @Thread-context blocking IO on the caller's thread; one writer per file at a time.
 */
final class LocalFiles {

    private LocalFiles() {
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
            AtomicFileWriter.writeOwnerOnly(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write " + file, e);
        }
    }
}
