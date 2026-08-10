package dev.nodera.storage.fs;

import java.io.IOException;
import java.util.List;

/**
 * Where a {@link FsContentStore} actually puts its bytes.
 *
 * <h2>Why a seam exists here at all</h2>
 *
 * <p>The content store's job is content addressing and the byte budget: hash a blob, refuse one
 * that does not hash to its own name, never evict a pinned one. None of that is filesystem
 * knowledge. The filesystem knowledge — a two-level fan-out under {@code content/}, a
 * same-directory temp file, {@code ATOMIC_MOVE} — is what {@link PathBlobDirectory} holds, and it
 * is the only part of the store that a platform can refuse.
 *
 * <p>Android 11+ refuses it for every folder outside app-specific storage. A folder the user picks
 * with the system file manager comes back as a {@code content://} tree URI and there is no
 * {@code java.io.File} behind it, so a worker that writes with {@code java.nio.file} can be granted
 * a folder it then cannot use (frontend M-1). Splitting this interface out is what lets the same
 * store — the same budget, the same pins, the same hash checks — write through Android's Storage
 * Access Framework instead. There is exactly one content store; only its back end varies.
 *
 * <h2>The namespace</h2>
 *
 * <p>A blob is named by the 64-character lowercase hex of its content hash and nothing else. No
 * path separators, no extension, no directory semantics: an implementation is free to fan the names
 * out into subdirectories (as {@link PathBlobDirectory} does, so no directory grows unbounded) or
 * to keep them flat (which is what a SAF-backed directory wants, because creating a subdirectory
 * there is a round trip through a content provider). The store never assumes either.
 *
 * <h2>What an implementation must guarantee</h2>
 *
 * <p>{@link #write} must be all-or-nothing as far as {@link #read} is concerned: a reader must
 * never observe a partially written blob under a valid name, because the name is the hash and a
 * short read would be reported as corruption. Implementations that cannot offer a true atomic
 * replace must say so in their own documentation rather than let the caller assume it — see
 * the honesty note on the SAF back end in {@code dev.nodera.headless.SafBlobDirectory}.
 *
 * @Thread-context per implementation; {@link FsContentStore} is thread-confined and serialises its
 *                 own calls, so an implementation need not be thread-safe.
 */
public interface BlobDirectory {

    /** A blob name is the hex of a SHA-256, so exactly 64 characters. */
    int NAME_LENGTH = 64;

    /**
     * Is {@code name} one of ours?
     *
     * <p>Asked at every boundary where a name arrives from somewhere other than a
     * {@code ContentId}. Two of them exist and both are real: a folder the user picked holds
     * whatever the user put there, so a listing can contain anything at all; and a name that
     * reaches {@link PathBlobDirectory} becomes a filesystem path, where {@code ../} would be a
     * traversal out of the store. Constraining the character class rather than filtering separators
     * is what makes the second one impossible instead of merely unlikely.
     *
     * @param name the candidate.
     * @return true if it is exactly 64 lowercase hex characters — what {@code Bytes.toHex} emits.
     */
    static boolean isBlobName(String name) {
        if (name == null || name.length() != NAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < NAME_LENGTH; i++) {
            char c = name.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    /**
     * One stored blob, as the directory can describe it without reading its bytes.
     *
     * @param name               the 64-character lowercase hex content hash.
     * @param sizeBytes          how many bytes it holds.
     * @param lastModifiedMillis last write or access, epoch milliseconds; the LRU key.
     */
    record Entry(String name, long sizeBytes, long lastModifiedMillis) {
    }

    /**
     * @param name the blob name.
     * @return true if a blob is stored under {@code name}.
     */
    boolean exists(String name);

    /**
     * @param name the blob name.
     * @return the stored bytes, or {@code null} when nothing is stored under {@code name}.
     * @throws IOException if the blob is present but cannot be read.
     */
    byte[] read(String name) throws IOException;

    /**
     * Store {@code bytes} under {@code name}, replacing anything already there.
     *
     * @param name  the blob name.
     * @param bytes the content.
     * @throws IOException if the write fails; nothing may be left readable under {@code name}.
     */
    void write(String name, byte[] bytes) throws IOException;

    /**
     * Remove the blob stored under {@code name}.
     *
     * <p>Returns the size rather than a boolean because every caller that deletes is also
     * maintaining a byte total, and a separate size lookup would be a second round trip that can
     * disagree with the delete it precedes.
     *
     * @param name the blob name.
     * @return the number of bytes freed, or {@code -1} when nothing was stored under {@code name}.
     * @throws IOException if the blob is present but cannot be removed.
     */
    long delete(String name) throws IOException;

    /**
     * @return every stored blob. Order is not significant; the store sorts what it needs to.
     * @throws IOException if the directory cannot be surveyed.
     */
    List<Entry> list() throws IOException;

    /**
     * Record an access, so LRU order survives a restart.
     *
     * <p>Best effort by contract: a directory that cannot record an access still serves content
     * correctly, and the only cost is a staler eviction order. Implementations swallow their own
     * failures rather than failing a read.
     *
     * @param name        the blob name.
     * @param epochMillis the access time.
     */
    void touch(String name, long epochMillis);

    /**
     * @return where this directory keeps its blobs, in words a log line or an error message can
     *         carry. Two directories with equal locations are the same place.
     */
    String location();
}
