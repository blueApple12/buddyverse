package app.buddyverse.design

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Explicit light/dark styles (not `auto`), so the system adds no contrast scrim of its own (on Android 16's 3-button bar
 * that scrim came out white under white keys). The app paints what sits under the bars.
 */
@Composable
actual fun SystemBars(dark: Boolean, darkNavBar: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    LaunchedEffect(activity, dark, darkNavBar) {
        fun style(d: Boolean) = if (d) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        activity.enableEdgeToEdge(style(dark), style(darkNavBar))
    }
}
