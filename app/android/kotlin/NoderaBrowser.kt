package dev.nodera.app

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.browser.customtabs.CustomTabsIntent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

/**
 * Opens a web link outside the app's own interface.
 *
 * ## Why the whole ladder is here and not split with Rust
 *
 * It used to be split: Rust called this, and on failure Rust made a *second* JNI call to try
 * `ACTION_VIEW` itself. That second call happened with the Java exception from the first still
 * pending, and ART aborts a process that makes a JNI call in that state — so a link that could not
 * open did not fail, it killed the app.
 *
 * Every rung lives on this side now. A rung that fails throws into a `catch` here, which is an
 * ordinary caught exception and never becomes a pending JNI one. Rust makes exactly one call.
 *
 * ## The ladder
 *
 * 1. **`ACTION_VIEW`** — the user's default browser, as its own app. First because it is what
 *    "open in my browser" means, and because it is the rung with the fewest ways to go wrong.
 * 2. **A Custom Tab** — the same browser engine drawn inside this task, for devices where the
 *    intent found no handler but a Custom Tabs provider exists.
 * 3. **A WebView in a dialog** — no browser at all. Deliberately last and deliberately plain: no
 *    JavaScript, no storage, no file access. It exists so the page the user asked for is *readable*,
 *    not so this app becomes a browser.
 *
 * There is no fourth rung. Copying the address to the clipboard was one once, and it is not opening
 * a link.
 *
 * Kept in `android/kotlin/` and copied into the generated Gradle project by
 * `scripts/android-apk.sh`, because `gen/` is disposable — Tauri regenerates it, and an edit made
 * there is an edit that disappears. `NoderaBrowser` is also named in that script's R8 keep list:
 * R8 cannot see a reflective JNI call, and a renamed class would take the whole ladder with it.
 */
object NoderaBrowser {

    private const val TAG = "NoderaMC"

    /** How long the caller will wait for the UI thread to put a dialog up. */
    private const val UI_TIMEOUT_SECONDS = 5L

    /**
     * Open [url], and say which rung answered.
     *
     * Never throws. Rust is on the other side of this call and a thrown exception there is a pending
     * JNI exception, which is a crash rather than an error — so everything is caught, including the
     * `Throwable` cases that are normally worth rethrowing.
     *
     * @param context the application context Rust holds. The dialog rung needs an *activity*, which
     *   is why [MainActivity] publishes the live one — an application context cannot show a window.
     * @param url an `http`/`https` address, already validated by `browser::check_url` on the Rust
     *   side, which is where the scheme whitelist lives.
     * @return `browser`, `custom-tab` or `webview`, or `null` when every rung failed.
     */
    @JvmStatic
    fun open(context: Context, url: String): String? {
        return try {
            if (url.isBlank()) return null
            val uri = Uri.parse(url)
            // The second lock on a door Rust already locked, not a substitute for it.
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                Log.w(TAG, "refusing to open a $scheme link")
                return null
            }
            browser(context, uri) ?: customTab(context, uri) ?: webView(url)
        } catch (t: Throwable) {
            // Including Error: whatever went wrong, the caller is native code that must get a value
            // back rather than an exception it would carry into its next JNI call.
            Log.w(TAG, "could not open $url", t)
            null
        }
    }

    /** The user's default browser, as its own app. */
    private fun browser(context: Context, uri: Uri): String? = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        "browser"
    } catch (t: Throwable) {
        // ActivityNotFoundException when nothing handles http, SecurityException behind some work
        // profiles. Both mean "try the next rung", and neither should reach the caller.
        Log.i(TAG, "no browser for this link: ${t.message}")
        null
    }

    /** The same engine, drawn inside this task. */
    private fun customTab(context: Context, uri: Uri): String? = try {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            // Required to start from an application context, which is the only context Rust holds:
            // `NoderaBridge.initialise` is given `applicationContext`.
            .also { it.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            .launchUrl(context, uri)
        "custom-tab"
    } catch (t: Throwable) {
        Log.i(TAG, "no custom tab available: ${t.message}")
        null
    }

    /**
     * The last resort: a plain WebView in a dialog over the app.
     *
     * Everything optional is off. This renders a page on a device with nothing else that can, and
     * the smaller its surface the better — at the far end of the chain the URL came from a
     * third-party index.
     */
    private fun webView(url: String): String? {
        val activity = MainActivity.live() ?: run {
            Log.w(TAG, "no activity to host a webview")
            return null
        }
        val done = CountDownLatch(1)
        // Atomic rather than a plain local: it is written on the UI thread and read on this
        // one, and `@Volatile` is not something Kotlin allows on a local variable.
        val shown = AtomicBoolean(false)
        Handler(Looper.getMainLooper()).post {
            try {
                val view = WebView(activity).apply {
                    settings.javaScriptEnabled = false
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    // Keep navigation inside this view rather than firing more intents: the point of
                    // this rung is that nothing else on the device can open a page.
                    webViewClient = WebViewClient()
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    loadUrl(url)
                }
                Dialog(activity).apply {
                    setContentView(view)
                    setCancelable(true)
                    // Destroyed with the dialog: a WebView outliving its window leaks the activity.
                    setOnDismissListener {
                        view.stopLoading()
                        view.destroy()
                    }
                    show()
                }
                shown.set(true)
            } catch (t: Throwable) {
                Log.w(TAG, "could not show a webview: ${t.message}")
            } finally {
                // In `finally`, so a throw above cannot leave the caller waiting the full timeout.
                done.countDown()
            }
        }
        // Bounded, and never called from the UI thread — Rust reaches this from a blocking pool, so
        // the post above is always a hand-off to a different thread.
        done.await(UI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return if (shown.get()) "webview" else null
    }
}
