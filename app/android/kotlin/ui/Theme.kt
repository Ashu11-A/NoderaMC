package dev.nodera.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Material You, actually.
 *
 * The webview build asked the user to pick an accent from five swatches, and said so honestly: a
 * WebView cannot read the system wallpaper, and claiming to have read it would have been a lie.
 * Compose can, so on Android 12 and above the palette comes from the wallpaper and the picker is
 * gone — there is nothing left to ask.
 *
 * Below 12 there is no wallpaper palette to read, so the scheme is seeded from the wordmark's own
 * magenta. Same colour the desktop uses; the phone is not a different product.
 */
private val SEED = Color(0xFFFF4D8D)

private val FallbackDark = darkColorScheme(primary = SEED)
private val FallbackLight = lightColorScheme(primary = SEED)

/** Whether this device can derive a palette from the wallpaper. Read by the Appearance screen. */
val DYNAMIC_COLOUR_AVAILABLE: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun NoderaTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        DYNAMIC_COLOUR_AVAILABLE && dark -> dynamicDarkColorScheme(context)
        DYNAMIC_COLOUR_AVAILABLE -> dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
