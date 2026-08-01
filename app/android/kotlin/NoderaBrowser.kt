package dev.nodera.app

import android.app.Dialog
import android.content.ActivityNotFoundException
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

/**
 * Opens a web link outside the app's own interface.
 *
 * ## Why this is not one `startActivity`
 *
 * A phone with a browser installed is the normal case, not the only case. Nodera runs on handsets
 * that have had their browser removed, on work profiles that block one, and on the odd device that
 * ships without one — and on all of those a bare `ACTION_VIEW` throws
 * `ActivityNotFoundException` and the link silently does nothing. A link that does nothing is worse
 * than a link that opens something imperfect, because the user cannot tell it apart from a frozen
 * app.
 *
 * So this is a ladder, best first:
 *
 * 1. **A Custom Tab.** The user's own browser engine, with their cookies and their logins, drawn
 *    inside this task so the back gesture returns to Nodera instead of leaving it. This is what
 *    "opens in the browser" should feel like on Android.
 * 2. **`ACTION_VIEW`.** The default browser as a separate app. Correct, just heavier: it leaves
 *    Nodera, and coming back is the user's problem.
 * 3. **A WebView in a dialog.** No browser at all. Deliberately last and deliberately plain — no
 *    JavaScript, no storage, no downloads. It exists so the address the user tapped is *readable*,
 *    not so this app becomes a browser.
 *
 * Rust calls this through JNI ([`dev.nodera.app.NoderaBrowser.open`]) and falls back to its own
 * `ACTION_VIEW` if the class is missing, so a build staged without this file still opens links.
 *
 * Kept in `android/kotlin/` and copied into the generated Gradle project by
 * `scripts/android-apk.sh`, because `gen/` is disposable — Tauri regenerates it, and an edit made
 * there is an edit that disappears.
 */
object NoderaBrowser {

    private const val TAG = "NoderaMC"

    /**
     * Open [url], and say which rung of the ladder answered.
     *
     * @param context the application context Rust holds. The dialog rung needs an *activity*, which
     *   is why [MainActivity] publishes the live one — an application context cannot show a window.
     * @param url an `http`/`https` address. **Already validated by the Rust side**
     *   (`browser::check_url`), which is where the scheme whitelist lives; this method does not
     *   re-derive that rule, it only refuses to act on something obviously empty.
     * @return `custom-tab`, `browser` or `webview`, or `null` when every rung failed.
     */
    @JvmStatic
    fun open(context: Context, url: String): String? {
        if (url.isBlank()) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        // Belt and braces against a caller that is not the Rust command — the scheme whitelist is
        // enforced there, and this is the second lock on the same door rather than a substitute.
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            Log.w(TAG, "refusing to open a $scheme link")
            return null
        }

        customTab(context, uri)?.let { return it }
        browser(context, uri)?.let { return it }
        return webView(url)
    }

    /** The user's browser engine, drawn inside this task. */
    private fun customTab(context: Context, uri: Uri): String? = runCatching {
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            // Without this the tab cannot be started from an application context, which is the only
            // context Rust holds (`NoderaBridge.initialise` is given `applicationContext`).
            .build()
            .also { it.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        intent.launchUrl(context, uri)
        "custom-tab"
    }.getOrElse {
        Log.i(TAG, "no custom tab available: ${it.message}")
        null
    }

    /** The default browser as its own app. */
    private fun browser(context: Context, uri: Uri): String? = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "browser"
    } catch (e: ActivityNotFoundException) {
        Log.i(TAG, "no browser installed: ${e.message}")
        null
    } catch (e: SecurityException) {
        Log.i(TAG, "not allowed to open a browser: ${e.message}")
        null
    }

    /**
     * The last resort: a plain WebView in a dialog over the app.
     *
     * Needs the live activity, which is why it does not take the context above. Everything optional
     * is off — no JavaScript, no DOM storage, no file access. This is here to render a page the user
     * asked to see on a device with nothing else that can, and the smaller its attack surface the
     * better: it is showing a URL that, at the far end of the chain, a third-party index supplied.
     */
    private fun webView(url: String): String? {
        val activity = MainActivity.live() ?: run {
            Log.w(TAG, "no activity to host a webview")
            return null
        }
        val done = java.util.concurrent.CountDownLatch(1)
        var shown = false
        Handler(Looper.getMainLooper()).post {
            shown = runCatching {
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
                    // Destroyed with the dialog: a WebView outliving its window is a leaked activity.
                    setOnDismissListener {
                        view.stopLoading()
                        view.destroy()
                    }
                    show()
                }
                true
            }.getOrElse {
                Log.w(TAG, "could not show a webview: ${it.message}")
                false
            }
            done.countDown()
        }
        // Bounded: the caller is a Tauri command thread and must not hang on a wedged UI thread.
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return if (shown) "webview" else null
    }
}
