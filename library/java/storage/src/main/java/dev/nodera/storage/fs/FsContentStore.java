package dev.nodera.storage.fs;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.storage.ContentId;
import dev.nodera.storage.PinnableContentStore;
import dev.nodera.storage.StorageException;
import dev.nodera.storage.client.ArchiveEvictionPolicy;
import dev.nodera.storage.client.BoundedClientWorldStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Content-addressed blob store (Task 9 archival tier). Writes are atomic where the back end can be
 * atomic (the filesystem one uses a same-directory temp file + {@code ATOMIC_MOVE}) so a crashed
 * write can never leave a half-blob under a valid name; reads re-hash and reject corruption —
 * the id IS the hash, so a blob that does not hash to its name is a {@link StorageException},
 * never silently returned.
 *
 * <h2>Where the bytes land</h2>
 *
 * <p>This class owns content addressing, pinning and the byte budget; a {@link BlobDirectory} owns
 * the bytes. The default one is {@link PathBlobDirectory} —
 * {@code <root>/content/ab/cd/<hash>.bin}, fanned out by the first two hash bytes so no directory
 * grows unbounded — and {@link #FsContentStore(Path, HashService)} is unchanged from when that was
 * the only possibility. The seam exists because Android 11+ refuses raw file access to every folder
 * outside app-specific storage, so a folder the user picks with the system file manager has to be
 * written through the Storage Access Framework instead (frontend M-1). It is the same store either
 * way: one budget, one pin set, one hash check.
 *
 * <h2>The byte budget (L-62)</h2>
 *
 * <p>Optionally bounded. {@link #setBudgetBytes} applies the same policy
 * {@link BoundedClientWorldStore} applies in memory — {@link ArchiveEvictionPolicy}: evict the
 * oldest COLD blob first, never a {@link #pin pinned} one, and signal every eviction so repair
 * re-creates the replica before it is gone everywhere. Unbounded by default, because the store also
 * backs a node's own hosted world, where "evict to fit" is the wrong answer.
 *
 * <p>Last-access is the blob's modification time, so LRU order survives a restart — the back end
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
    private BlobDirectory blobs;
    private int count;
    private long usedBytes;

    /** 0 = unbounded (the default); otherwise the ceiling {@link #put} enforces. */
    private long budgetBytes;

    /** Content that must never be evicted: an assigned region's current state, a hosted world. */
    private final Set<Bytes> pinned = new HashSet<>();

    /** Notified per eviction so repair re-creates the replica; null = no repair lane wired. */
    private BoundedClientWorldStore.EvictionListener listener;

    /**
     * The filesystem store: blobs under {@code <root>/content}.
     *
     * @param root   the archive directory.
     * @param hashes the hash service the content ids are minted with.
     */
    public FsContentStore(Path root, HashService hashes) {
        this(new PathBlobDirectory(requireRoot(root)), hashes);
    }

    /**
     * The store over an arbitrary back end — a {@code content://} tree on Android, say.
     *
     * @param blobs  where the bytes go.
     * @param hashes the hash service the content ids are minted with.
     */
    public FsContentStore(BlobDirectory blobs, HashService hashes) {
        if (blobs == null) {
            throw new IllegalArgumentException("blobs must not be null");
        }
        if (hashes == null) {
            throw new IllegalArgumentException("hashes must not be null");
        }
        this.hashes = hashes;
        this.blobs = blobs;
        try {
            for (BlobDirectory.Entry entry : blobs.list()) {
                this.count++;
                this.usedBytes += entry.sizeBytes();
            }
        } catch (IOException e) {
            throw new StorageException("cannot initialise content store at " + blobs.location(), e);
        }
    }

    private static Path requireRoot(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        return root;
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

    /**
     * Free at least {@code bytesNeeded} by evicting the oldest cold blobs (L-62).
     *
     * <p>The entry list is built by surveying the back end rather than from an in-memory index:
     * eviction happens only when a put would cross the budget, and a survey cannot disagree with
     * the disk the way a cached index can after a crash or an out-of-band delete.
     *
     * @throws dev.nodera.storage.client.QuotaException if only pinned content remains — the caller
     *         must refuse the put rather than drop load-bearing state.
     */
    private void evictToFree(long bytesNeeded) {
        List<ArchiveEvictionPolicy.Entry> entries = new ArrayList<>();
        try {
            for (BlobDirectory.Entry blob : blobs.list()) {
                ContentId id = idOf(blob);
                if (id == null) {
                    continue;
                }
                entries.add(new ArchiveEvictionPolicy.Entry(id, blob.sizeBytes(),
                        pinned.contains(id.hash()), blob.lastModifiedMillis()));
            }
        } catch (IOException e) {
            throw new StorageException("cannot survey the content store for eviction", e);
        }
        for (ContentId victim : ArchiveEvictionPolicy.evictToFree(entries, bytesNeeded)) {
            String name = victim.hash().toHex();
            byte[] bytes = null;
            try {
                if (listener != null) {
                    // Read before deleting: repair needs the bytes to place the replica elsewhere.
                    bytes = blobs.read(name);
                }
                long freed = blobs.delete(name);
                if (freed >= 0) {
                    count--;
                    usedBytes -= freed;
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
     * @return the content id a stored blob's name encodes, or null if the name is not one of ours.
     *         The hash is the identity — {@code compression} is reported as {@code NONE} because
     *         the back end records only the stored bytes, which is what eviction and repair both
     *         act on.
     */
    private static ContentId idOf(BlobDirectory.Entry blob) {
        String hex = blob.name();
        if (hex.length() != 64) {
            return null;
        }
        try {
            return new ContentId(Bytes.fromHex(hex), blob.sizeBytes(),
                    dev.nodera.storage.Compression.NONE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * @return the directory this store currently keeps its blobs in, or {@code null} when the back
     *         end is not a filesystem — a {@code content://} tree on Android has no path, which is
     *         the whole reason it needs a back end of its own. Callers that write <i>beside</i> the
     *         blobs (the manifest index) must treat null as "this node keeps no sidecar".
     */
    public Path contentRoot() {
        return blobs instanceof PathBlobDirectory path ? path.root() : null;
    }

    /** @return where this store keeps its blobs, in words — always answerable, unlike a path. */
    public String contentLocation() {
        return blobs.location();
    }

    /**
     * Move every stored blob to {@code newRoot} and continue serving from there (L-58).
     *
     * @param newRoot the new archive directory (the store owns its {@code content/} subdirectory).
     * @return how many blobs were relocated.
     * @throws StorageException if the move fails.
     */
    public int relocateTo(Path newRoot) {
        if (newRoot == null) {
            throw new IllegalArgumentException("newRoot must not be null");
        }
        return relocateTo(new PathBlobDirectory(newRoot));
    }

    /**
     * Move every stored blob into {@code destination} and continue serving from there (L-58).
     *
     * <p>The reason this exists rather than "restart the worker with a new path": the blobs a node
     * holds ARE its seeding obligations. Re-pointing the location without moving them would
     * silently orphan everything the node had promised the swarm — the world would still be listed,
     * and every piece request would miss. So the content moves first and the store is re-pointed
     * only after, and a failure part-way leaves the store serving from wherever the blob actually
     * is.
     *
     * <p>Content-addressed storage makes this safe: a blob's name is its hash, so a blob already
     * present at the destination is byte-identical to the one being moved and is simply overwritten
     * with the same bytes.
     *
     * @param destination where the blobs should live from now on.
     * @return how many blobs were relocated.
     * @throws StorageException if the move fails.
     */
    public int relocateTo(BlobDirectory destination) {
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        if (destination.location().equals(blobs.location())) {
            return 0;
        }
        int moved = 0;
        try {
            for (BlobDirectory.Entry entry : blobs.list()) {
                byte[] bytes = blobs.read(entry.name());
                if (bytes == null) {
                    continue;
                }
                destination.write(entry.name(), bytes);
                blobs.delete(entry.name());
                moved++;
            }
        } catch (IOException e) {
            throw new StorageException(
                    "cannot relocate content store to " + destination.location(), e);
        }
        blobs = destination;
        return moved;
    }

    @Override
    public ContentId put(byte[] blob) {
        if (blob == null) {
            throw new IllegalArgumentException("blob must not be null");
        }
        ContentId id = ContentId.of(hashes, blob);
        String name = id.hash().toHex();
        if (blobs.exists(name)) {
            touch(name);
            return id; // content-addressed: same bytes, same blob
        }
        if (budgetBytes > 0) {
            if (blob.length > budgetBytes) {
                // A blob larger than the whole budget can never be stored; do not evict pinned
                // content chasing the impossible.
                throw new dev.nodera.storage.client.QuotaException(
                        "blob " + blob.length + "B exceeds the budget " + budgetBytes);
            }
            long over = usedBytes + blob.length - budgetBytes;
            if (over > 0) {
                evictToFree(over);
            }
        }
        try {
            blobs.write(name, blob);
            count++;
            usedBytes += blob.length;
        } catch (IOException e) {
            throw new StorageException("cannot store blob " + id, e);
        }
        return id;
    }

    @Override
    public Optional<byte[]> get(ContentId id) {
        String name = id.hash().toHex();
        byte[] blob;
        try {
            blob = blobs.read(name);
        } catch (IOException e) {
            throw new StorageException("cannot read blob " + id, e);
        }
        if (blob == null) {
            return Optional.empty();
        }
        if (!hashes.sha256(blob).equals(id.hash())) {
            throw new StorageException("content blob corrupt on disk (hash mismatch): " + id);
        }
        // A read is an access: frequently-read content stays warm and outlives colder blobs.
        touch(name);
        return Optional.of(blob);
    }

    @Override
    public boolean has(ContentId id) {
        return blobs.exists(id.hash().toHex());
    }

    @Override
    public boolean remove(ContentId id) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        long freed;
        try {
            freed = blobs.delete(id.hash().toHex());
        } catch (IOException e) {
            throw new StorageException("cannot remove content blob " + id, e);
        }
        if (freed < 0) {
            return false;
        }
        usedBytes -= freed;
        pinned.remove(id.hash());
        count--;
        return true;
    }

    @Override
    public int size() {
        return count;
    }

    /** Record an access as the blob's mtime — only when bounded; unbounded stores never LRU. */
    private void touch(String name) {
        if (budgetBytes <= 0) {
            return;
        }
        blobs.touch(name, System.currentTimeMillis());
    }
}
