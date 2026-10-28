package app.buddyverse.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import app.buddyverse.res.Res
import app.buddyverse.res.jetbrains_mono
import app.buddyverse.res.rubik
import org.jetbrains.compose.resources.Font

/** design §8: Rubik (Hebrew + Latin, variable 300–900) for UI, JetBrains Mono for code. Sizes in sp. */
@Immutable
class AppType(val ui: FontFamily, val mono: FontFamily) {
    private fun s(size: Int, line: Int, w: FontWeight, tracking: Float = 0f) =
        TextStyle(fontFamily = ui, fontSize = size.sp, lineHeight = line.sp, fontWeight = w, letterSpacing = tracking.sp, textDirection = TextDirection.Content)
    val display = s(34, 40, FontWeight.Bold)
    val title = s(26, 32, FontWeight.Bold)
    val title2 = s(21, 26, FontWeight.SemiBold)
    val headline = s(18, 24, FontWeight.SemiBold)
    val body = s(17, 24, FontWeight.Normal)
    val callout = s(16, 22, FontWeight.Normal)
    val button = s(16, 22, FontWeight.SemiBold)
    val sub = s(15, 20, FontWeight.Normal)
    val caption = s(13, 18, FontWeight.Medium)
    val caps = s(13, 18, FontWeight.Medium, .4f)
    val micro = s(12, 16, FontWeight.Medium)
    val code = TextStyle(fontFamily = mono, fontSize = 14.sp, lineHeight = 20.sp, textDirection = TextDirection.Ltr)
}

@Composable
fun rememberAppType(): AppType {
    val ui = FontFamily(
        Font(Res.font.rubik, FontWeight.Light),
        Font(Res.font.rubik, FontWeight.Normal),
        Font(Res.font.rubik, FontWeight.Medium),
        Font(Res.font.rubik, FontWeight.SemiBold),
        Font(Res.font.rubik, FontWeight.Bold),
    )
    val mono = FontFamily(Font(Res.font.jetbrains_mono, FontWeight.Normal))
    return remember(ui, mono) { AppType(ui, mono) }
}
