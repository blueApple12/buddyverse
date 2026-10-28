package app.buddyverse.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** Esc (and the page's own Back buttons) go back; the most recently enabled handler wins, as on the PC. */
object WebBack {
    internal class Handler(var enabled: Boolean, var onBack: () -> Unit)
    internal val handlers = mutableListOf<Handler>()

    internal fun setEnabled(h: Handler, on: Boolean) {
        if (on && !h.enabled && handlers.remove(h)) handlers += h
        h.enabled = on
    }

    fun back(): Boolean {
        val h = handlers.lastOrNull { it.enabled } ?: return false
        h.onBack()
        return true
    }
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    val current by rememberUpdatedState(onBack)
    val h = remember { WebBack.Handler(enabled) { current() } }
    SideEffect { WebBack.setEnabled(h, enabled) }
    DisposableEffect(h) {
        WebBack.handlers += h
        onDispose { WebBack.handlers -= h }
    }
}

@Composable
actual fun rememberSystemBack(): () -> Unit = { WebBack.back() }

/** The page's theme-color meta follows the app's light/dark choice (iOS status bar area). */
private fun setThemeColor(color: String): Unit = js("{ const m = document.querySelector('meta[name=theme-color]'); if (m) m.content = color; }")

@Composable
actual fun SystemBars(dark: Boolean, darkNavBar: Boolean) {
    SideEffect { setThemeColor(if (dark) "#0F0E12" else "#FAF8F5") }
}
