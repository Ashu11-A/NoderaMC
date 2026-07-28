package dev.nodera.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge

/**
 * The app's activity.
 *
 * Overridden to bind Android's application context after the native library loads. Rust combines
 * that signal with its own setup-complete signal and starts the Java worker only after persisted P2P
 * settings are materialised. Activity lifecycle timing is never used as proof that setup finished.
 *
 * Kept in `android/kotlin/` and copied into the generated Gradle project by
 * `scripts/android-apk.sh`, because `gen/` is disposable — Tauri regenerates it, and an edit made
 * there is an edit that disappears.
 */
class MainActivity : TauriActivity() {

  /**
   * Let the system back gesture walk the WebView's history.
   *
   * `TauriActivity` turns this off, so every back press finished the activity — pressing back
   * inside a settings sub-screen closed the whole app instead of going up one level. With it on,
   * wry routes back to `WebView.goBack()`, and the interface's own `history.pushState` entries
   * become the navigation stack a phone user expects. At the root there is nothing to go back to,
   * so the app closes, which is also what they expect.
   */
  override val handleBackNavigation: Boolean = true

  /**
   * Route the system folder picker's answer back to [NoderaStorage].
   *
   * The picker is an activity result, and only an activity can receive one — which is why the
   * storage flow lives partly in Kotlin rather than entirely in Rust.
   */
  @Deprecated("Activity result APIs; the Tauri activity does not use the newer contracts")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    @Suppress("DEPRECATION")
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == NoderaStorage.REQUEST_CODE) {
      NoderaStorage.onResult(this, resultCode, data)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    // After super.onCreate, because that is what loads the native library this call lives in.
    // Failure is logged and survivable: battery checks and worker startup stay unavailable, but the
    // app remains open and reports the worker offline.
    runCatching { NoderaBridge.initialise(applicationContext) }
      .onFailure { android.util.Log.e("NoderaMC", "could not bind the native bridge", it) }
    // Only an activity can start the folder picker for a result.
    NoderaStorage.attach(this)
  }

  override fun onDestroy() {
    NoderaStorage.detach(this)
    super.onDestroy()
  }
}
