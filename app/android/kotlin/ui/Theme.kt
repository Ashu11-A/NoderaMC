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
 * Below 12 there is no wallpaper palette to read, so the scheme is seeded from the desktop
 * launcher's own accent — `--brand-1` in `app/ui/src/styles.css`, `#7d67cb` dark and `#4b3d8f`
 * light, with `--brand-3` as the tertiary. Same colours the desktop uses; the phone is not a
 * different product, and a hard-coded hex here is the one place that can drift from them.
 *
 * It used to be a magenta, `#D82B6A`, which appeared in no other file in the repository and matched
 * nothing on screen. The comment above it claimed it was the wordmark's colour. There was no
 * wordmark. It was describing an intention, not a fact.
 *
 * The light scheme cannot use the dark theme's `--brand-1` directly: `#7d67cb` on white is 3.6:1
 * and fails body text. It uses the darker value `styles.css` itself switches to in light mode.
 */
private val SEED = Color(0xFF7D67CB)

private val FallbackDark = darkColorScheme(
    primary = SEED,
    onPrimary = Color(0xFF120C24),
    primaryContainer = Color(0xFF4B3D8F),
    onPrimaryContainer = Color(0xFFE5DEFF),
    secondary = Color(0xFF9182D6),
    tertiary = Color(0xFF8FC8FF),
)
private val FallbackLight = lightColorScheme(
    primary = Color(0xFF4B3D8F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5DEFF),
    onPrimaryContainer = Color(0xFF17103A),
    secondary = Color(0xFF5F4FA8),
    tertiary = Color(0xFF145F8A),
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
