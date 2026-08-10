package dev.nodera.app

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.FileOutputStream

/**
 * Reads and writes the peer's world-archive blobs inside a folder the user picked, through the
 * Storage Access Framework.
 *
 * # Why this exists
 *
 * [NoderaStorage] can open the system folder picker and persist the grant, but a grant is not
 * access: on Android 11+ there is no `java.io.File` behind a shared folder, and the Nodera worker
 * is a Java program that writes with `java.nio.file`. So until now a folder outside app-specific
 * storage could be chosen and then honestly reported as unusable — which is better than silently
 * storing nothing, but it is still a folder the user chose and cannot use (frontend M-1).
 *
 * This class is the other half. The worker's content store now writes through a *blob directory*
 * seam rather than straight at the filesystem, and this is that seam's Android implementation: the
 * same store, the same byte budget, the same hash checks, over `ContentResolver` instead of
 * `Files`.
 *
 * # How the worker reaches it
 *
 * By name, from `dev.nodera.headless.SafBlobDirectory`. It has to be by name, and the direction is
 * not arbitrary: the worker ships as a dexed asset loaded by a child `DexClassLoader` whose parent
 * is the app's, so worker code can see `dev.nodera.app` and app code can never see
 * `dev.nodera.headless`. A shared interface is therefore impossible, and every method here takes
 * and returns nothing but `String`, `ByteArray`, `Boolean` and `Long`.
 *
 * R8 cannot see a call made by name. `scripts/android-apk.sh` adds a `-keep` rule for this class
 * and then asserts it landed, because a missing keep is invisible in a green build and appears only
 * as a `NoSuchMethodError` the first time the peer stores a blob.
 *
 * # Failure is a return value, not an exception
 *
 * An exception crossing a reflective call arrives wrapped and loses the sentence the platform used.
 * Every method here catches its own failures, returns a miss, and leaves the platform's words in
 * [lastError] — which is what the worker puts in its `IOException` and what the app shows beside
 * the folder the user chose.
 *
 * # Atomicity, said plainly
 *
 * The filesystem back end replaces a blob with a same-directory temp file and `ATOMIC_MOVE`: a
 * reader sees the old bytes or the new bytes, never a prefix. **SAF has no atomic move.** A new
 * blob is written to `<name>.tmp`, the descriptor is synced, and the document is renamed — and a
 * rename through a content provider is ordered, not atomic. A process killed between the sync and
 * the rename leaves a stray `.tmp`, which [list] ignores because its name is not a 64-character
 * hash; one killed mid-rename leaves an outcome the platform does not define.
 *
 * That is a weaker durability guarantee than the filesystem one and it is stated rather than
 * papered over. What keeps it from being a correctness problem is content addressing: a blob's name
 * is its hash and the store re-hashes every read, so a truncated blob is reported as corruption and
 * refetched from the swarm — never returned as content.
 */
object NoderaSafBlobs {

    private const val TAG = "NoderaMC"

    /** Blob names are the hex of a SHA-256. */
    private const val NAME_LENGTH = 64

    /** Written first, renamed into place. Never reported by [list]. */
    private const val PENDING_SUFFIX = ".tmp"

    /**
     * Some document providers append an extension derived from the MIME type. Blobs are therefore
     * created under their bare hash and matched with or without the suffix a provider chose to add.
     */
    private const val PROVIDER_SUFFIX = ".bin"

    private const val MIME = "application/octet-stream"

    /** How long a listing may be reused before a miss re-reads the folder. */
    private const val INDEX_TTL_MILLIS = 5_000L

    @Volatile
    private var context: Context? = null

    @Volatile
    private var lastError: String = ""

    /** One child document of the picked folder. */
    private data class Child(val documentId: String, val size: Long, val modified: Long)

    /** The folder's contents, as last read. Rebuilt on a miss older than [INDEX_TTL_MILLIS]. */
    private class Index(val children: MutableMap<String, Child>, var readAtMillis: Long)

    private val indexes = HashMap<String, Index>()

    /**
     * Called from [NoderaWorker] before the worker starts, and from `MainActivity`.
     *
     * The *application* context deliberately: this outlives every activity, and the worker keeps
     * writing while no screen is on.
     */
    @JvmStatic
    fun attach(context: Context) {
        this.context = context.applicationContext
    }

    /** @return the platform's own words for the most recent failure, or "" if there was none. */
    @JvmStatic
    fun lastError(): String = lastError

    /**
     * Prove the folder can actually be written through SAF.
     *
     * Called by [NoderaStorage] the moment the user picks a folder, so "picked" and "usable" are
     * never confused. Writes a probe blob and removes it again.
     *
     * @return null when the folder is usable, or the reason it is not.
     */
    @JvmStatic
    fun probe(treeUri: String): String? {
        lastError = ""
        val name = "0".repeat(NAME_LENGTH)
        if (!write(treeUri, name, "nodera".toByteArray())) {
            return lastError.ifEmpty { "The folder could not be written to." }
        }
        delete(treeUri, name)
        return null
    }

    /** @return true if a blob is stored under [name]. */
    @JvmStatic
    fun exists(treeUri: String, name: String): Boolean = child(treeUri, name) != null

    /** @return the stored bytes, or null when absent or unreadable. */
    @JvmStatic
    fun read(treeUri: String, name: String): ByteArray? {
        lastError = ""
        val resolver = resolver() ?: return null
        val child = child(treeUri, name) ?: return null
        return runCatching {
            resolver.openInputStream(documentUri(treeUri, child.documentId))
                ?.use { it.readBytes() }
        }.getOrElse { failed("read $name", it); null }
    }

    /**
     * Store [bytes] under [name].
     *
     * A name already present is overwritten in place — content addressing means the bytes are
     * identical, so there is nothing to lose and a rename to an occupied name is refused by the
     * platform anyway. A new name goes through the pending-then-rename dance described in this
     * class's own documentation.
     *
     * @return true when the bytes are stored.
     */
    @JvmStatic
    fun write(treeUri: String, name: String, bytes: ByteArray): Boolean {
        lastError = ""
        val resolver = resolver() ?: return false
        val existing = child(treeUri, name)
        if (existing != null) {
            if (!writeInto(resolver, documentUri(treeUri, existing.documentId), bytes)) {
                return false
            }
            record(treeUri, name, existing.documentId, bytes.size.toLong())
            return true
        }
        val pending = create(resolver, treeUri, name + PENDING_SUFFIX) ?: return false
        if (!writeInto(resolver, pending, bytes)) {
            runCatching { DocumentsContract.deleteDocument(resolver, pending) }
            return false
        }
        val renamed = runCatching { DocumentsContract.renameDocument(resolver, pending, name) }
            .getOrElse { failed("rename $name into place", it); null }
        if (renamed == null) {
            // Some providers return null having renamed the document anyway, so the folder is
            // re-read rather than trusted: an invented success here would leave the store counting
            // a blob that is not there.
            invalidate(treeUri)
            if (child(treeUri, name) != null) {
                return true
            }
            runCatching { DocumentsContract.deleteDocument(resolver, pending) }
            if (lastError.isEmpty()) {
                lastError = "Android refused to rename the file into place in this folder."
            }
            return false
        }
        record(treeUri, name, DocumentsContract.getDocumentId(renamed), bytes.size.toLong())
        return true
    }

    /** @return the bytes freed, or -1 when nothing was stored under [name]. */
    @JvmStatic
    fun delete(treeUri: String, name: String): Long {
        lastError = ""
        val resolver = resolver() ?: return -1L
        val child = child(treeUri, name) ?: return -1L
        val removed = runCatching {
            DocumentsContract.deleteDocument(resolver, documentUri(treeUri, child.documentId))
        }.getOrElse { failed("delete $name", it); false }
        if (!removed) return -1L
        synchronized(indexes) { indexes[treeUri]?.children?.remove(name) }
        return child.size
    }

    /**
     * @return one line per blob, `name \t sizeBytes \t lastModifiedMillis`, or null when the folder
     *         cannot be read. Anything in the folder that is not one of our blobs — a photo, a
     *         stray `.tmp`, a subdirectory — is left out rather than counted.
     */
    @JvmStatic
    fun list(treeUri: String): String? {
        lastError = ""
        invalidate(treeUri)
        val index = index(treeUri) ?: return null
        return synchronized(indexes) {
            index.children.entries.joinToString("\n") { (name, child) ->
                "$name\t${child.size}\t${child.modified}"
            }
        }
    }

    /**
     * Record an access, so eviction order survives a restart.
     *
     * SAF exposes `COLUMN_LAST_MODIFIED` as read-only — a document provider owns its own metadata
     * and there is no supported way to backdate it. So this is a no-op, and the consequence is
     * stated rather than hidden: on a SAF-backed archive the store's LRU order is write order, not
     * access order. Eviction still never touches a pinned blob, which is what protects the worlds
     * this node hosts; what it loses is the preference for keeping a frequently-read replica.
     */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun touch(treeUri: String, name: String, epochMillis: Long) {
        // Deliberately empty; see the documentation above.
    }

    private fun resolver(): ContentResolver? {
        val resolver = context?.contentResolver
        if (resolver == null) {
            lastError = "Nodera is not attached to Android yet."
        }
        return resolver
    }

    private fun writeInto(resolver: ContentResolver, target: Uri, bytes: ByteArray): Boolean =
        runCatching {
            resolver.openOutputStream(target, "wt").use { stream ->
                if (stream == null) {
                    lastError = "Android would not open this folder for writing."
                    return false
                }
                stream.write(bytes)
                stream.flush()
                // The bytes are in the page cache until this returns; without it a power loss
                // between here and the rename can leave a named blob with no content.
                if (stream is FileOutputStream) {
                    stream.fd.sync()
                }
            }
            true
        }.getOrElse { failed("write to the chosen folder", it); false }

    private fun create(resolver: ContentResolver, treeUri: String, displayName: String): Uri? =
        runCatching {
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                Uri.parse(treeUri),
                DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)),
            )
            DocumentsContract.createDocument(resolver, parent, MIME, displayName)
        }.getOrElse { failed("create a file in the chosen folder", it); null }
            ?: run {
                if (lastError.isEmpty()) {
                    lastError = "Android would not create a file in this folder."
                }
                null
            }

    private fun documentUri(treeUri: String, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), documentId)

    private fun child(treeUri: String, name: String): Child? {
        synchronized(indexes) { indexes[treeUri] }?.let { index ->
            index.children[name]?.let { return it }
            if (System.currentTimeMillis() - index.readAtMillis < INDEX_TTL_MILLIS) {
                return null
            }
        }
        invalidate(treeUri)
        return index(treeUri)?.let { synchronized(indexes) { it.children[name] } }
    }

    private fun invalidate(treeUri: String) {
        synchronized(indexes) { indexes.remove(treeUri) }
    }

    private fun record(treeUri: String, name: String, documentId: String, size: Long) {
        synchronized(indexes) {
            indexes[treeUri]?.children?.put(
                name,
                Child(documentId, size, System.currentTimeMillis()),
            )
        }
    }

    /** Read the folder once. Null means the folder could not be read at all. */
    @SuppressLint("Recycle")
    private fun index(treeUri: String): Index? {
        synchronized(indexes) { indexes[treeUri] }?.let { return it }
        val resolver = resolver() ?: return null
        val children = HashMap<String, Child>()
        val read = runCatching {
            val tree = Uri.parse(treeUri)
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            resolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val display = cursor.getString(1) ?: continue
                    val name = display.removeSuffix(PROVIDER_SUFFIX)
                    if (name.length != NAME_LENGTH) continue
                    children[name] = Child(
                        cursor.getString(0),
                        cursor.getLong(2),
                        cursor.getLong(3),
                    )
                }
                true
            } ?: false
        }.getOrElse { failed("read the chosen folder", it); false }
        if (!read) {
            if (lastError.isEmpty()) {
                lastError = "Android would not list this folder."
            }
            return null
        }
        val index = Index(children, System.currentTimeMillis())
        synchronized(indexes) { indexes[treeUri] = index }
        return index
    }

    /** Record the platform's own sentence, and log it once where a bug report can find it. */
    private fun failed(what: String, failure: Throwable) {
        lastError = failure.message ?: "Android refused to $what."
        Log.w(TAG, "saf: could not $what", failure)
    }
}
