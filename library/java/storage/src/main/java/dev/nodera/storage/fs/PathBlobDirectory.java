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

    private final Path contentRoot;

    /**
     * @param root the archive directory; this directory owns its {@code content/} subdirectory.
     *             It must be <b>absolute</b> — see below — and is normalised before use.
     * @throws IllegalArgumentException if {@code root} is null or relative.
     * @throws StorageException if the directory cannot be created.
     */
    public PathBlobDirectory(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        // Absolute, and refused rather than resolved if it is not. A relative archive root lands
        // wherever the worker happened to be launched from, which is not a location anybody chose:
        // handed the Android document tree `content://…` before this lane understood one, the
        // worker resolved it as a relative path and quietly stored a hosted world in a directory
        // called `content:` beside its working directory. Normalised in the same breath so the
        // stored root is the one the rest of this class compares blob paths against.
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException(
                    "the archive directory must be an absolute path, not " + root);
        }
        this.contentRoot = root.normalize().resolve("content");
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
                if (!BlobDirectory.isBlobName(name)) {
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

    /**
     * The file a blob name maps to, fanned out by its first two bytes.
     *
     * <p>Two guards, not one, because this is the method that turns a string into a filesystem
     * path. {@link BlobDirectory#isBlobName} constrains the character class, so a name carrying
     * {@code ../} or an absolute prefix never reaches {@code resolve} at all; the containment check
     * afterwards is the belt to that brace, comparing the <b>normalised</b> result against the
     * content root rather than comparing text. The store's own names always pass both — they are
     * {@code ContentId.hash().toHex()} — but a name can also come from a directory listing, and a
     * folder the user picked holds whatever the user put in it.
     */
    private Path pathFor(String name) {
        if (!BlobDirectory.isBlobName(name)) {
            throw new IllegalArgumentException(
                    "not a content-addressed blob name: " + name);
        }
        Path candidate = contentRoot.resolve(name.substring(0, 2)).resolve(name.substring(2, 4))
                .resolve(name + SUFFIX).normalize();
        if (!candidate.startsWith(contentRoot)) {
            throw new IllegalArgumentException("blob name escapes the content store: " + name);
        }
        return candidate;
    }
}
