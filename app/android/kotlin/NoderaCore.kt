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
    init {
        System.loadLibrary("nodera_app_lib")
    }

    @Volatile
    private var started = false

    /**
     * Start the node. Safe to call again — a configuration change recreates the Activity, and it
     * must not recreate the node.
     */
    fun start(context: Context) {
        if (started) return
        started = true
        NoderaBridge.initialise(context.applicationContext)
        nativeStart(context.applicationContext.filesDir.absolutePath)
    }

    /** One verb, JSON in, JSON out. Errors arrive as `{"error": "..."}`, never as an exception. */
    suspend fun call(verb: String, args: JSONObject = JSONObject()): String =
        withContext(Dispatchers.IO) { nativeInvoke(verb, args.toString()) }

    /** A verb whose answer is a JSON object, or null when the core reported an error. */
    suspend fun obj(verb: String, args: JSONObject = JSONObject()): JSONObject? {
        val raw = call(verb, args)
        return runCatching { JSONObject(raw) }.getOrNull()?.takeIf { !it.has("error") }
    }

    /** The sentence behind a failed verb, or null when it worked. */
    fun errorOf(raw: String): String? =
        runCatching { JSONObject(raw).optString("error").ifEmpty { null } }.getOrNull()

    private external fun nativeStart(dataDir: String)
    private external fun nativeInvoke(name: String, args: String): String
}
