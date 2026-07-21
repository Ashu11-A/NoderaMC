package dev.nodera.storage.rocksdb;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.storage.ContentId;
import dev.nodera.storage.ContentStore;
import dev.nodera.storage.StorageException;
import dev.nodera.storage.io.AtomicFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Filesystem content-addressed blob store (Task 9 archival tier):
 * {@code <root>/content/ab/cd/<hash>.bin}, fanned out by the first two hash bytes so no directory
 * grows unbounded. Writes are atomic (same-directory temp file + {@code ATOMIC_MOVE}) so a crashed
 * write can never leave a half-blob under a valid name; reads re-hash and reject corruption —
 * the id IS the hash, so a blob that does not hash to its name is a {@link StorageException},
 * never silently returned.
 *
 * @Thread-context confined to the owning thread; not thread-safe.
 */
public final class FsContentStore implements ContentStore {

    private final HashService hashes;
    private final Path contentRoot;
    private int count;

    public FsContentStore(Path root, HashService hashes) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        if (hashes == null) {
            throw new IllegalArgumentException("hashes must not be null");
        }
        this.hashes = hashes;
        this.contentRoot = root.resolve("content");
        try {
            Files.createDirectories(contentRoot);
            try (Stream<Path> files = Files.walk(contentRoot)) {
                this.count = (int) files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".bin"))
                        .count();
            }
        } catch (IOException e) {
            throw new StorageException("cannot initialise content store at " + contentRoot, e);
        }
    }

    @Override
    public ContentId put(byte[] blob) {
        if (blob == null) {
            throw new IllegalArgumentException("blob must not be null");
        }
        ContentId id = ContentId.of(hashes, blob);
        Path target = pathFor(id.hash());
        if (Files.exists(target)) {
            return id; // content-addressed: same bytes, same file
        }
        try {
            AtomicFileWriter.write(target, blob);
            count++;
        } catch (IOException e) {
            throw new StorageException("cannot store blob " + id, e);
        }
        return id;
    }

    @Override
    public Optional<byte[]> get(ContentId id) {
        Path target = pathFor(id.hash());
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        byte[] blob;
        try {
            blob = Files.readAllBytes(target);
        } catch (IOException e) {
            throw new StorageException("cannot read blob " + id, e);
        }
        if (!hashes.sha256(blob).equals(id.hash())) {
            throw new StorageException("content blob corrupt on disk (hash mismatch): " + id);
        }
        return Optional.of(blob);
    }

    @Override
    public boolean has(ContentId id) {
        return Files.exists(pathFor(id.hash()));
    }

    @Override
    public int size() {
        return count;
    }

    private Path pathFor(Bytes hash) {
        String hex = hash.toHex();
        return contentRoot.resolve(hex.substring(0, 2)).resolve(hex.substring(2, 4))
                .resolve(hex + ".bin");
    }
}
