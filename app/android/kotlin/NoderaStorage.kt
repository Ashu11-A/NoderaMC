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
 * # The part that has to be said plainly
 *
 * The Nodera worker is a Java program that writes with `java.io.File`. It cannot open a
 * `content://` URI. So a SAF grant alone does not make a folder usable by the peer — it makes it
 * usable by *this* app through `DocumentFile`.
 *
 * This class therefore does three things and reports all three:
 *
 *  1. opens the system picker and **persists** the grant;
 *  2. maps the tree URI back to a real filesystem path where such a mapping exists
 *     (`primary:Music` → `/storage/emulated/0/Music`, `1A2B-3C4D:Worlds` → `/storage/1A2B-3C4D/Worlds`);
 *  3. **probes it with a real write**, because on Android 11+ raw access to arbitrary shared folders
 *     is refused regardless of the SAF grant.
 *
 * When the probe fails the folder is reported as picked-but-unusable, with the reason. That is the
 * permission problem the user asked to be able to see, rather than a peer that silently stores
 * nothing.
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
        val path = filesystemPath(uri)
        if (path == null) {
            write(
                activity,
                uri = uri.toString(),
                label = display,
                error = "Android did not expose a file path for this folder, so the peer cannot " +
                    "write to it. Choose a folder on internal or SD storage.",
            )
            return
        }
        val canonical = runCatching { path.canonicalFile }.getOrNull()
        if (canonical == null || workerRoots(activity).none { root ->
                canonical.toPath().startsWith(root.toPath())
            }) {
            write(
                activity,
                uri = uri.toString(),
                label = display,
                error = "The peer cannot safely use this folder. Choose app storage, shared app storage, or an SD-card location offered by Nodera.",
            )
            return
        }
        val probe = probeWritable(canonical)
        write(
            activity,
            uri = uri.toString(),
            label = display,
            path = canonical.absolutePath,
            writable = probe == null,
            error = probe ?: "",
        )
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
