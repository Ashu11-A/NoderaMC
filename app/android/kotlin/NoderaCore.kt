package dev.nodera.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The node, from Kotlin.
 *
 * One `invoke` rather than one JNI function per command. Fifty-two `external fun` declarations would
 * be fifty-two chances to write a signature that does not match the Rust side — and a wrong JNI
 * signature is not a compile error, it is a `NoSuchMethodError` on a device at the moment the user
 * taps something.
 *
 * Every call is `withContext(Dispatchers.IO)`. Some verbs cross the network (refreshing a store,
 * asking the collector what it accepts) and the caller is a composable running on the main thread;
 * blocking there is a frozen frame and, past five seconds, an ANR.
 */
object NoderaCore {
    private const val TAG = "NoderaMC"

    init {
        System.loadLibrary("nodera_app_lib")
    }

    @Volatile
    private var started = false

    /**
     * Start the node. Safe to call again — a configuration change recreates the Activity, and it
     * must not recreate the node.
     */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        migrateLegacyConfig(app)
        started = true
        NoderaBridge.initialise(app)
        nativeStart(app.dataDir.absolutePath)
    }

    /** Preserve state written before Android's config root was corrected from filesDir to dataDir. */
    private fun migrateLegacyConfig(context: Context) {
        val legacy = java.io.File(context.filesDir, "nodera")
        val current = java.io.File(context.dataDir, "nodera")
        if (!legacy.isDirectory) return
        legacy.walkTopDown().filter(java.io.File::isFile).forEach { source ->
            val target = java.io.File(current, source.relativeTo(legacy).path)
            if (target.exists()) return@forEach
            runCatching {
                target.parentFile?.mkdirs()
                if (!source.renameTo(target)) {
                    source.copyTo(target)
                    source.delete()
                }
            }.onFailure { android.util.Log.w(TAG, "could not migrate ${source.name}", it) }
        }
    }

    /** One verb, JSON in, JSON out. Errors arrive as `{"error": "..."}`, never as an exception. */
    suspend fun call(verb: String, args: JSONObject = JSONObject()): String =
        withContext(Dispatchers.IO) { nativeInvoke(verb, args.toString()) }

    /** A verb whose answer is a JSON object, or null when the core reported an error. */
    suspend fun obj(verb: String, args: JSONObject = JSONObject()): JSONObject? {
        val raw = call(verb, args)
        return runCatching { JSONObject(raw) }.getOrNull()
            ?.takeIf { !(it.length() == 1 && it.has("error")) }
    }

    /** The sentence behind a failed verb, or null when it worked. */
    fun errorOf(raw: String): String? = runCatching {
        val value = JSONObject(raw)
        // Bridge failures are error-only envelopes. Successful domain payloads such as SelfTest
        // also carry an `error` field, and that sentence must stay inside the result it describes.
        if (value.length() == 1) value.optString("error").ifEmpty { null } else null
    }.getOrNull()

    private external fun nativeStart(dataDir: String)
    private external fun nativeInvoke(name: String, args: String): String
}
