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
import java.util.List;
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
    /**
     * Not final: the archive directory is a user-facing setting, and a store that cannot move
     * makes it a restart-required one that also strands whatever the node was already seeding
     * (L-58). {@link #relocateTo} moves the blobs and re-points this field in one step.
     */
    private Path contentRoot;
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

    /** @return the directory this store currently keeps its blobs in. */
    public Path contentRoot() {
        return contentRoot;
    }

    /**
     * Move every stored blob to {@code newRoot} and continue serving from there (L-58).
     *
     * <p>The reason this exists rather than "restart the worker with a new path": the blobs a node
     * holds ARE its seeding obligations. Re-pointing the path without moving them would silently
     * orphan everything the node had promised the swarm — the world would still be listed, and
     * every piece request would miss. So the content moves first and the path is re-pointed only
     * after, and a failure part-way leaves the store serving from wherever the blob actually is.
     *
     * <p>Content-addressed storage makes this safe: a blob's name is its hash, so a file already
     * present at the destination is byte-identical to the one being moved and is simply kept.
     *
     * @param newRoot the new archive directory (the store owns its {@code content/} subdirectory).
     * @return how many blobs were relocated.
     * @throws StorageException if the move fails.
     */
    public int relocateTo(Path newRoot) {
        if (newRoot == null) {
            throw new IllegalArgumentException("newRoot must not be null");
        }
        Path target = newRoot.resolve("content");
        if (target.equals(contentRoot)) {
            return 0;
        }
        int moved = 0;
        try {
            Files.createDirectories(target);
            List<Path> blobs;
            try (Stream<Path> files = Files.walk(contentRoot)) {
                blobs = files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".bin"))
                        .sorted()
                        .toList();
            }
            for (Path blob : blobs) {
                Path relative = contentRoot.relativize(blob);
                Path destination = target.resolve(relative);
                Files.createDirectories(destination.getParent());
                // REPLACE_EXISTING is safe precisely because the name is the content hash: an
                // existing file at that name holds the same bytes.
                Files.move(blob, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                moved++;
            }
        } catch (IOException e) {
            throw new StorageException("cannot relocate content store to " + target, e);
        }
        contentRoot = target;
        return moved;
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
    public boolean remove(ContentId id) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        Path target = pathFor(id.hash());
        try {
            if (!Files.deleteIfExists(target)) {
                return false;
            }
        } catch (java.io.IOException e) {
            throw new StorageException("cannot remove content blob " + id, e);
        }
        count--;
        return true;
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
