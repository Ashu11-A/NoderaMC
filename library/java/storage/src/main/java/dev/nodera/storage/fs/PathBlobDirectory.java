package dev.nodera.storage.fs;

import dev.nodera.storage.StorageException;
import dev.nodera.storage.io.AtomicFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The filesystem back end for {@link FsContentStore}: {@code <root>/content/ab/cd/<hash>.bin}.
 *
 * <p>Names are fanned out by the first two hash bytes so no directory grows unbounded, and writes
 * go through {@link AtomicFileWriter} — a same-directory temporary file and {@code ATOMIC_MOVE} —
 * so a crashed write can never leave a half-blob under a valid name. This is the behaviour the
 * store has always had; it moved here unchanged when {@link BlobDirectory} was split out so the
 * same store could also write through Android's Storage Access Framework (frontend M-1).
 *
 * @Thread-context confined to the owning store; not thread-safe.
 */
public final class PathBlobDirectory implements BlobDirectory {

    private static final String SUFFIX = ".bin";

    /** Blob names are the hex of a SHA-256, so exactly 64 characters. */
    private static final int NAME_LENGTH = 64;

    private final Path contentRoot;

    /**
     * @param root the archive directory; this directory owns its {@code content/} subdirectory.
     * @throws StorageException if the directory cannot be created.
     */
    public PathBlobDirectory(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        this.contentRoot = root.resolve("content");
        try {
            Files.createDirectories(contentRoot);
        } catch (IOException e) {
            throw new StorageException("cannot initialise content store at " + contentRoot, e);
        }
    }

    /** @return the directory the blobs live in — {@code <root>/content}. */
    public Path root() {
        return contentRoot;
    }

    @Override
    public boolean exists(String name) {
        return Files.exists(pathFor(name));
    }

    @Override
    public byte[] read(String name) throws IOException {
        Path target = pathFor(name);
        return Files.exists(target) ? Files.readAllBytes(target) : null;
    }

    @Override
    public void write(String name, byte[] bytes) throws IOException {
        AtomicFileWriter.write(pathFor(name), bytes);
    }

    @Override
    public long delete(String name) throws IOException {
        Path target = pathFor(name);
        if (!Files.exists(target)) {
            return -1L;
        }
        long size = Files.size(target);
        return Files.deleteIfExists(target) ? size : -1L;
    }

    @Override
    public List<Entry> list() throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.walk(contentRoot)) {
            List<Path> blobs = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .sorted()
                    .toList();
            for (Path blob : blobs) {
                String fileName = blob.getFileName().toString();
                String name = fileName.substring(0, fileName.length() - SUFFIX.length());
                if (name.length() != NAME_LENGTH) {
                    continue;
                }
                entries.add(new Entry(name, Files.size(blob),
                        Files.getLastModifiedTime(blob).toMillis()));
            }
        }
        return entries;
    }

    @Override
    public void touch(String name, long epochMillis) {
        try {
            Files.setLastModifiedTime(pathFor(name), FileTime.fromMillis(epochMillis));
        } catch (IOException | RuntimeException ignored) {
            // Best effort by contract: a staler eviction order is not worth failing a read for.
        }
    }

    @Override
    public String location() {
        return contentRoot.toString();
    }

    private Path pathFor(String name) {
        return contentRoot.resolve(name.substring(0, 2)).resolve(name.substring(2, 4))
                .resolve(name + SUFFIX);
    }
}
