package dev.nodera.storage.rocksdb;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.storage.ContentId;
import dev.nodera.storage.PinnableContentStore;
import dev.nodera.storage.StorageException;
import dev.nodera.storage.client.ArchiveEvictionPolicy;
import dev.nodera.storage.client.BoundedClientWorldStore;
import dev.nodera.storage.client.QuotaException;
import dev.nodera.storage.io.AtomicFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Filesystem content-addressed blob store (Task 9 archival tier):
 * {@code <root>/content/ab/cd/<hash>.bin}, fanned out by the first two hash bytes so no directory
 * grows unbounded. Writes are atomic (same-directory temp file + {@code ATOMIC_MOVE}) so a crashed
 * write can never leave a half-blob under a valid name; reads re-hash and reject corruption —
 * the id IS the hash, so a blob that does not hash to its name is a {@link StorageException},
 * never silently returned.
 *
 * <h2>The byte budget (L-62)</h2>
 *
 * <p>Optionally bounded. {@link #setBudgetBytes} applies the same policy
 * {@link BoundedClientWorldStore} applies in memory — {@link ArchiveEvictionPolicy}: evict the
 * oldest COLD blob first, never a {@link #pin pinned} one, and signal every eviction so repair
 * re-creates the replica before it is gone everywhere. Unbounded by default, because the store also
 * backs a node's own hosted world, where "evict to fit" is the wrong answer.
 *
 * <p>Last-access is the file's modification time, so LRU order survives a restart — the disk
 * already records it, and a second bookkeeping file could only drift from what is actually there.
 * A read therefore touches the mtime, but only when a budget is set: an unbounded store does no
 * write on the read path.
 *
 * @Thread-context confined to the owning thread; not thread-safe.
 */
public final class FsContentStore implements PinnableContentStore {

    private final HashService hashes;
    /**
     * Not final: the archive directory is a user-facing setting, and a store that cannot move
     * makes it a restart-required one that also strands whatever the node was already seeding
     * (L-58). {@link #relocateTo} moves the blobs and re-points this field in one step.
     */
    private Path contentRoot;
    private int count;
    private long usedBytes;

    /** 0 = unbounded (the default); otherwise the ceiling {@link #put} enforces. */
    private long budgetBytes;

    /** Content that must never be evicted: an assigned region's current state, a hosted world. */
    private final Set<Bytes> pinned = new HashSet<>();

    /** Notified per eviction so repair re-creates the replica; null = no repair lane wired. */
    private BoundedClientWorldStore.EvictionListener listener;

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
            List<Path> present = blobFiles();
            this.count = present.size();
            for (Path blob : present) {
                this.usedBytes += Files.size(blob);
            }
        } catch (IOException e) {
            throw new StorageException("cannot initialise content store at " + contentRoot, e);
        }
    }

    /**
     * Bound this store to {@code bytes} (L-62).
     *
     * @param bytes the ceiling; {@code 0} or negative removes the bound.
     */
    public void setBudgetBytes(long bytes) {
        this.budgetBytes = Math.max(0, bytes);
    }

    /** @return the byte ceiling, or 0 when unbounded. */
    public long budgetBytes() {
        return budgetBytes;
    }

    /** @return how many bytes of blob this store currently holds. */
    public long usedBytes() {
        return usedBytes;
    }

    /**
     * Notified with the bytes of every evicted blob, so Task 21's repair can re-create the replica
     * elsewhere <i>before</i> it is gone everywhere. Called after the file is deleted.
     */
    public void setEvictionListener(BoundedClientWorldStore.EvictionListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean pin(ContentId id) {
        pinned.add(requireId(id).hash());
        return has(id);
    }

    @Override
    public void unpin(ContentId id) {
        pinned.remove(requireId(id).hash());
    }

    @Override
    public boolean isPinned(ContentId id) {
        return pinned.contains(requireId(id).hash());
    }

    private static ContentId requireId(ContentId id) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        return id;
    }

    /** @return every {@code .bin} under the content root, deterministically ordered. */
    private List<Path> blobFiles() throws IOException {
        try (Stream<Path> files = Files.walk(contentRoot)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".bin"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Free at least {@code bytesNeeded} by evicting the oldest cold blobs (L-62).
     *
     * <p>The entry list is built by walking the store rather than from an in-memory index: eviction
     * happens only when a put would cross the budget, and a walk cannot disagree with the disk the
     * way a cached index can after a crash or an out-of-band delete.
     *
     * @throws QuotaException if only pinned content remains — the caller must refuse the put rather
     *                        than drop load-bearing state.
     */
    private void evictToFree(long bytesNeeded) {
        List<ArchiveEvictionPolicy.Entry> entries = new ArrayList<>();
        List<Path> files;
        try {
            files = blobFiles();
            for (Path blob : files) {
                long size = Files.size(blob);
                ContentId id = idOf(blob, size);
                if (id == null) {
                    continue;
                }
                entries.add(new ArchiveEvictionPolicy.Entry(id, size,
                        pinned.contains(id.hash()),
                        Files.getLastModifiedTime(blob).toMillis()));
            }
        } catch (IOException e) {
            throw new StorageException("cannot survey the content store for eviction", e);
        }
        for (ContentId victim : ArchiveEvictionPolicy.evictToFree(entries, bytesNeeded)) {
            Path target = pathFor(victim.hash());
            byte[] bytes = null;
            try {
                if (listener != null && Files.exists(target)) {
                    // Read before deleting: repair needs the bytes to place the replica elsewhere.
                    bytes = Files.readAllBytes(target);
                }
                long size = Files.exists(target) ? Files.size(target) : 0L;
                if (Files.deleteIfExists(target)) {
                    count--;
                    usedBytes -= size;
                }
            } catch (IOException e) {
                throw new StorageException("cannot evict content blob " + victim, e);
            }
            if (listener != null && bytes != null) {
                listener.onEvicted(victim, bytes);
            }
        }
    }

    /**
     * @return the content id a blob file's name encodes, or null if the name is not one of ours.
     *         The hash is the identity — {@code compression} is reported as {@code NONE} because
     *         disk records only the stored bytes, which is what eviction and repair both act on.
     */
    private static ContentId idOf(Path blobFile, long size) {
        String name = blobFile.getFileName().toString();
        String hex = name.substring(0, name.length() - ".bin".length());
        if (hex.length() != 64) {
            return null;
        }
        try {
            return new ContentId(Bytes.fromHex(hex), size, dev.nodera.storage.Compression.NONE);
        } catch (RuntimeException e) {
            return null;
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
            touch(target);
            return id; // content-addressed: same bytes, same file
        }
        if (budgetBytes > 0) {
            if (blob.length > budgetBytes) {
                // A blob larger than the whole budget can never be stored; do not evict pinned
                // content chasing the impossible.
                throw new QuotaException(
                        "blob " + blob.length + "B exceeds the budget " + budgetBytes);
            }
            long over = usedBytes + blob.length - budgetBytes;
            if (over > 0) {
                evictToFree(over);
            }
        }
        try {
            AtomicFileWriter.write(target, blob);
            count++;
            usedBytes += blob.length;
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
        // A read is an access: frequently-read content stays warm and outlives colder blobs.
        touch(target);
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
            long size = Files.exists(target) ? Files.size(target) : 0L;
            if (!Files.deleteIfExists(target)) {
                return false;
            }
            usedBytes -= size;
        } catch (java.io.IOException e) {
            throw new StorageException("cannot remove content blob " + id, e);
        }
        pinned.remove(id.hash());
        count--;
        return true;
    }

    @Override
    public int size() {
        return count;
    }

    /** Record an access as the file's mtime — only when bounded; unbounded stores never LRU. */
    private void touch(Path blobFile) {
        if (budgetBytes <= 0) {
            return;
        }
        try {
            Files.setLastModifiedTime(blobFile, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException e) {
            // A store that cannot record an access still serves content correctly; the only cost is
            // a slightly staler eviction order. Never fail a read for it.
        }
    }

    private Path pathFor(Bytes hash) {
        String hex = hash.toHex();
        return contentRoot.resolve(hex.substring(0, 2)).resolve(hex.substring(2, 4))
                .resolve(hex + ".bin");
    }
}
