package app.buddyverse.ui.play

import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.Modifier

actual fun Modifier.keepSwipes(): Modifier = systemGestureExclusion()
