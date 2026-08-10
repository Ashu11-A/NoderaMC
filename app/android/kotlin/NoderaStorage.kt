package dev.nodera.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import org.json.JSONObject

/**
 * The system folder picker, and the honest answer about whether the peer can use what was picked.
 *
 * # Why this is not just a path field
 *
 * On Android a folder is not a path an app may write to because the user typed it. Access is granted
 * per folder, by the user, through the Storage Access Framework — `ACTION_OPEN_DOCUMENT_TREE` opens
 * the system's own file manager, and what comes back is a `content://` tree URI plus a grant this
 * app must explicitly persist if it wants the folder after a reboot.
 *
 * # Two ways the peer can reach a folder, and which one wins
 *
 * A filesystem path is the better answer where one exists: writes are atomic, and nothing goes
 * through a content provider. So this class still maps a tree URI back to a real path
 * (`primary:Music` → `/storage/emulated/0/Music`) and **probes it with a real write**, because on
 * Android 11+ raw access to arbitrary shared folders is refused regardless of the SAF grant.
 *
 * When that refusal comes — which is every folder outside app-specific storage on a modern
 * handset — the folder is no longer reported as unusable. [NoderaSafBlobs] writes the peer's
 * archive blobs through the Storage Access Framework instead, and the worker's content store
 * accepts a `content://` tree as its archive location (frontend M-1). So the second probe is a SAF
 * probe, and what is handed on as the peer's storage location is the tree URI itself.
 *
 * This class therefore does four things and reports the outcome of all four:
 *
 *  1. opens the system picker and **persists** the grant;
 *  2. maps the tree URI back to a real filesystem path where such a mapping exists;
 *  3. probes that path with a real write, and uses it when it works;
 *  4. otherwise probes the tree through SAF, and hands on the `content://` URI when *that* works.
 *
 * Only when both fail is the folder reported as picked-but-unusable, with the platform's own
 * reason. That is still the honest answer — it is simply now the rare one.
 */
object NoderaStorage {

    private const val TAG = "NoderaMC"
    private const val RESULT_FILE = "storage-pick.json"

    /** The request code the activity routes back to [onResult]. */
    const val REQUEST_CODE = 0x4E44 // "ND"

    /**
     * The activity that will host the picker.
     *
     * Held here rather than passed in from Rust. The native side has the *application* context —
     * that is what a long-lived process should hold — but only an **activity** can start something
     * for a result, and handing a `Context` to a method declared to take a `MainActivity` is a JNI
     * type error, which is exactly how this failed the first time:
     *
     *   Error invoking postMessage: Java exception was raised during method invocation
     */
    @Volatile
    private var activity: MainActivity? = null

    /** Called from `MainActivity.onCreate`. */
    @JvmStatic
    fun attach(activity: MainActivity) {
        this.activity = activity
    }

    /** Called from `MainActivity.onDestroy`, so a finished activity is never used. */
    @JvmStatic
    fun detach(activity: MainActivity) {
        if (this.activity === activity) this.activity = null
    }

    /** Open the system folder picker on the attached activity. */
    @JvmStatic
    fun pick() {
        val activity = this.activity
        if (activity == null) {
            Log.e(TAG, "storage: no activity is attached; cannot open the picker")
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
        // Cleared first so the UI can tell "still choosing" from "chose the same thing again".
        File(activity.filesDir, RESULT_FILE).delete()
        Log.i(TAG, "storage: opening the folder picker")
        runCatching { activity.startActivityForResult(intent, REQUEST_CODE) }
            .onFailure {
                Log.e(TAG, "storage: no folder picker on this device", it)
                write(activity, error = "This device has no folder picker.")
            }
    }

    /**
     * Handle the picker's answer: persist the grant, map it to a path, and prove the path works.
     */
    @JvmStatic
    fun onResult(activity: MainActivity, resultCode: Int, data: Intent?) {
        this.activity = activity
        val uri = data?.data
        Log.i(TAG, "storage: picker returned code=$resultCode uri=$uri")
        if (resultCode != android.app.Activity.RESULT_OK || uri == null) {
            write(activity, error = "") // cancelled: not an error, just nothing chosen
            return
        }
        // Without this the grant dies with the activity, and the folder the user chose today is
        // inaccessible tomorrow.
        runCatching {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Log.w(TAG, "storage: the grant could not be persisted", it) }

        val display = DocumentFile.fromTreeUri(activity, uri)?.name ?: uri.lastPathSegment ?: "Folder"
        val direct = directPath(activity, uri)
        if (direct != null) {
            write(
                activity,
                uri = uri.toString(),
                label = display,
                path = direct.absolutePath,
                writable = true,
            )
            return
        }
        // No usable file path — the ordinary case on Android 11+, and until frontend M-1 the end
        // of the road. The peer now speaks SAF, so the question becomes whether it can write
        // through the grant rather than around it. The bridge is attached here as well as from the
        // worker, because a folder can be picked before the worker has ever started.
        NoderaSafBlobs.attach(activity)
        val safFailure = NoderaSafBlobs.probe(uri.toString())
        if (safFailure != null) {
            write(activity, uri = uri.toString(), label = display, error = safFailure)
            return
        }
        // The `content://` URI IS the peer's storage location from here on: it travels over
        // NODERA-CONFIG as `storage.peer_worlds_dir` exactly like a path would, and the worker's
        // `ArchiveDirectories` decides which of the two it has been handed.
        write(
            activity,
            uri = uri.toString(),
            label = display,
            path = uri.toString(),
            writable = true,
        )
    }

    /**
     * The picked folder as a real filesystem path the worker may use, or null when there is none.
     *
     * Null covers three different disappointments — no path behind the provider at all, a path
     * outside the roots the worker will accept, and a path Android refuses to let this app write —
     * and they collapse into one answer on purpose: the caller's next move is the same for all
     * three, and it is no longer "give up".
     */
    private fun directPath(context: android.content.Context, uri: Uri): File? {
        val canonical = runCatching { filesystemPath(uri)?.canonicalFile }.getOrNull() ?: return null
        val permitted = workerRoots(context).any { root ->
            canonical.toPath().startsWith(root.toPath())
        }
        if (!permitted) return null
        return if (probeWritable(canonical) == null) canonical else null
    }

    /**
     * Map a document-tree URI to a real path, when one exists.
     *
     * Tree ids look like `primary:Music` or `1A2B-3C4D:Worlds`: a volume, then a path inside it.
     * `primary` is the emulated internal volume; anything else is a physical volume id that appears
     * under `/storage`. A tree on a provider that is not a volume at all — a cloud drive, say — has
     * no path, and this returns null rather than inventing one.
     */
    private fun filesystemPath(uri: Uri): File? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        if (!DocumentsContract.isTreeUri(uri)) return null
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val parts = id.split(':', limit = 2)
        if (parts.size != 2) return null
        val (volume, relative) = parts
        val root = if (volume.equals("primary", ignoreCase = true)) {
            @Suppress("DEPRECATION")
            android.os.Environment.getExternalStorageDirectory()
        } else {
            File("/storage/$volume")
        }
        if (!root.exists()) return null
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching {
            (if (relative.isEmpty()) canonicalRoot else File(canonicalRoot, relative)).canonicalFile
        }.getOrNull() ?: return null
        return candidate.takeIf { it.toPath().startsWith(canonicalRoot.toPath()) }
    }

    /** Roots the worker may accept from its loopback configuration endpoint. */
    fun workerRoots(context: android.content.Context): List<File> =
        (listOf(context.dataDir) + context.getExternalFilesDirs(null).filterNotNull())
            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .distinctBy(File::getAbsolutePath)

    /**
     * Try to actually write there.
     *
     * @return null when the folder is usable, or the reason it is not.
     */
    private fun probeWritable(dir: File): String? {
        return runCatching {
            if (!dir.exists() && !dir.mkdirs()) {
                return "The folder could not be created."
            }
            val probe = File(dir, ".nodera-write-probe")
            probe.writeText("nodera")
            probe.delete()
            null
        }.getOrElse {
            // The common case on Android 11+: SAF granted the folder to the app, but direct file
            // access to shared storage is still refused. Said in those words, because "permission
            // denied" alone would look like a bug in Nodera.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "Android does not allow apps direct file access to this folder. " +
                    "Use app storage or an SD card folder."
            } else {
                it.message ?: "The folder is not writable."
            }
        }
    }

    /** Write the outcome where the Rust side can read it. */
    private fun write(
        activity: MainActivity,
        uri: String = "",
        label: String = "",
        path: String = "",
        writable: Boolean = false,
        error: String = "",
    ) {
        val json = JSONObject()
            .put("uri", uri)
            .put("label", label)
            .put("path", path)
            .put("writable", writable)
            .put("error", error)
        val target = File(activity.filesDir, RESULT_FILE)
        runCatching { target.writeText(json.toString()) }
            .onSuccess { Log.i(TAG, "storage: recorded $json") }
            .onFailure { Log.e(TAG, "storage: could not record the choice", it) }
    }
}
