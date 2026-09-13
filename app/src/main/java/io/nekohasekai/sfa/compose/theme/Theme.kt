package io.nekohasekai.sfa.compose.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

data class ThemeSeed(
    val id: String,
    val swatch: Color,
    val secondary: Color,
    val tertiary: Color,
)

val ThemeSeeds = listOf(
    ThemeSeed("default", SingBoxPrimary, SingBoxPrimaryLight, Color(0xFFBAE6FD)),
    ThemeSeed("teal", Color(0xFF0D9488), Color(0xFF5EEAD4), Color(0xFFCCFBF1)),
    ThemeSeed("green", Color(0xFF2E9E7A), Color(0xFF86EFAC), Color(0xFFDCFCE7)),
    ThemeSeed("orange", Color(0xFFF59E0B), Color(0xFFFDE68A), Color(0xFFFEF3C7)),
    ThemeSeed("rose", Color(0xFFF43F5E), Color(0xFFFDA4AF), Color(0xFFFFE4E6)),
    ThemeSeed("violet", Color(0xFF8B5CF6), Color(0xFFC4B5FD), Color(0xFFEDE9FE)),
    ThemeSeed("dynamic", Color(0xFF38BDF8), Color(0xFFF472B6), Color(0xFFA3E635)),
)

@Composable
fun Theme(
    content: @Composable () -> Unit,
) {
    Appearance.ensureLoaded()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (Appearance.mode) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    val seed = Appearance.seed
    val pureBlack = Appearance.pureBlack
    val context = LocalContext.current
    val colorScheme = when {
        seed == "dynamic" && Build.VERSION.SDK_INT >= 31 -> {
            val dynamic = if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
            if (darkTheme && pureBlack) dynamic.pureBlack() else dynamic
        }
        else -> {
            val (light, dark) = schemePair(seed)
            val base = if (darkTheme) dark else light
            if (darkTheme && pureBlack) base.pureBlack() else base
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
}

private fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF101010),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF222222),
    onSurface = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFFD6D6D6),
    outline = Color(0xFF9A9A9A),
    outlineVariant = Color(0xFF5C5C5C),
)

private fun schemePair(seed: String): Pair<ColorScheme, ColorScheme> {
    val picked = ThemeSeeds.find { it.id == seed }
    val primary = picked?.swatch ?: SingBoxPrimary
    val secondary = picked?.secondary ?: primary
    val tertiary = picked?.tertiary ?: primary
    return lightColorScheme(
        primary = primary,
        secondary = secondary,
        tertiary = tertiary,
    ) to darkColorScheme(
        primary = primary,
        secondary = secondary,
        tertiary = tertiary,
        onSurface = Color(0xFFF2F2F2),
        onSurfaceVariant = Color(0xFFD6D6D6),
        outline = Color(0xFF9A9A9A),
    )
}
