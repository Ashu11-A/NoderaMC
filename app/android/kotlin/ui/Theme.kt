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
private val SEED = Color(0xFFD82B6A)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFFFB0C8),
    onPrimary = Color(0xFF65002E),
    primaryContainer = Color(0xFF8D1748),
    secondary = Color(0xFFE4BDC7),
    tertiary = Color(0xFFD4C3F1),
)
private val FallbackLight = lightColorScheme(
    primary = SEED,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E2),
    secondary = Color(0xFF765661),
    tertiary = Color(0xFF66587A),
)

/** Whether this device can derive a palette from the wallpaper. Read by the Appearance screen. */
val DYNAMIC_COLOUR_AVAILABLE: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun NoderaTheme(
    preference: String = "system",
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = when (preference) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val scheme = when {
        DYNAMIC_COLOUR_AVAILABLE && dark -> dynamicDarkColorScheme(context)
        DYNAMIC_COLOUR_AVAILABLE -> dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
