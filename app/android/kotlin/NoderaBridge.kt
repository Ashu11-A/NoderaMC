package dev.nodera.app

import android.content.Context

/**
 * Hands the Android `Context` to the Rust side.
 *
 * Rust needs a `JavaVM` and a `Context` to call anything in the Android framework — reading whether
 * this app is battery-optimised, opening the settings screen. Those normally come from `ndk-glue`,
 * which Tauri does not use, so the global that every `jni` call reads is empty and touching it
 * aborts the process.
 *
 * One call fixes that, and it has to come from Java because only Java has the objects.
 */
object NoderaBridge {

    /**
     * Bind the context. Called once, after the native library is loaded.
     *
     * @param context the application context — not the activity, which does not outlive rotation.
     */
    @JvmStatic
    external fun initialise(context: Context)

    /**
     * Called by Rust after context binding and setup's property handoff have both completed.
     *
     * Starts the node through [NoderaWorkerService] rather than directly. Same worker, different
     * owner: started in-process it lived exactly as long as Android felt like keeping the app, and
     * a phone holding committee seats was killed 25 minutes into a session with nobody the wiser
     * (M-2). The service is what tells the system this process is working for the user.
     */
    @JvmStatic
    fun startWorker(context: Context) {
        NoderaWorkerService.start(context)
    }
}
