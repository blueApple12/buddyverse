package app.buddyverse.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** design §7. Color comes from the bots and one accent; amber = needs you, red = error, blue = working. */
@Immutable
data class Palette(
    val dark: Boolean,
    val ground: Color, val surface: Color, val surface2: Color, val hairline: Color,
    val text: Color, val secondary: Color, val tertiary: Color,
    val accent: Color, val accentFill: Color, val onAccent: Color,
    val needsYou: Color, val needsYouText: Color, val error: Color, val errorText: Color,
    val success: Color, val working: Color, val scrim: Color, val shadow: Color,
) {
    companion object {
        val Light = Palette(
            dark = false,
            ground = Color(0xFFFAF8F5), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF1EEE9), hairline = Color(0xFFE7E3DD),
            text = Color(0xFF18161B), secondary = Color(0xFF6B6672), tertiary = Color(0xFF8F8A96),
            accent = Color(0xFF6E56FF), accentFill = Color(0xFF6E56FF), onAccent = Color.White,
            needsYou = Color(0xFFF5A524), needsYouText = Color(0xFF8A5A00), error = Color(0xFFE5484D), errorText = Color(0xFFC62F35),
            success = Color(0xFF1FAF6B), working = Color(0xFF3B82F6), scrim = Color.Black.copy(alpha = .32f), shadow = Color.Black.copy(alpha = .08f),
        )
        val Dark = Palette(
            dark = true,
            ground = Color(0xFF0F0E12), surface = Color(0xFF1A191E), surface2 = Color(0xFF24232A), hairline = Color(0xFF2C2A31),
            text = Color(0xFFF3F1F6), secondary = Color(0xFFA7A2AE), tertiary = Color(0xFF77737E),
            accent = Color(0xFF9D8BFF), accentFill = Color(0xFF6A55F0), onAccent = Color.White,
            needsYou = Color(0xFFFFB224), needsYouText = Color(0xFFFFC766), error = Color(0xFFFF6B6F), errorText = Color(0xFFFF8A8D),
            success = Color(0xFF3DD68C), working = Color(0xFF5B9BFF), scrim = Color.Black.copy(alpha = .55f), shadow = Color.Black.copy(alpha = .40f),
        )
    }
}

val LocalPalette = staticCompositionLocalOf { Palette.Light }
val LocalType = staticCompositionLocalOf { AppType(FontFamily.Default, FontFamily.Monospace) }

object Theme {
    val colors: Palette @Composable get() = LocalPalette.current
    val type: AppType @Composable get() = LocalType.current
}

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    val type = rememberAppType()
    CompositionLocalProvider(LocalPalette provides if (dark) Palette.Dark else Palette.Light, LocalType provides type, content = content)
}

/** Radii (design §7). */
object Radius {
    const val card = 24
    const val stage = 28
    const val row = 20
    const val bubble = 22
    const val sheet = 28
}
