package app.buddyverse.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** Esc is the desktop's Back: the window's key handler calls [back]. The most recently enabled handler wins. */
object DesktopBack {
    internal class Handler(var enabled: Boolean, var onBack: () -> Unit)
    internal val handlers = mutableListOf<Handler>()

    /** The one that just turned on (a sheet that just opened) goes on top, whatever order the screens were composed in. */
    internal fun setEnabled(h: Handler, on: Boolean) {
        if (on && !h.enabled && handlers.remove(h)) handlers += h
        h.enabled = on
    }

    /** Runs the top enabled handler; false when none is enabled. */
    fun back(): Boolean {
        val h = handlers.lastOrNull { it.enabled } ?: return false
        h.onBack()
        return true
    }
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    val current by rememberUpdatedState(onBack)
    val h = remember { DesktopBack.Handler(enabled) { current() } }
    SideEffect { DesktopBack.setEnabled(h, enabled) }
    DisposableEffect(h) {
        DesktopBack.handlers += h
        onDispose { DesktopBack.handlers -= h }
    }
}

@Composable
actual fun rememberSystemBack(): () -> Unit = { DesktopBack.back() }
