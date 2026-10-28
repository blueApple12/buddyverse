package app.buddyverse.ui.play

import androidx.compose.ui.Modifier

/** Phone: swipes on a game field are the game's, not the system Back gesture (Android's edge swipe). No-op elsewhere. */
expect fun Modifier.keepSwipes(): Modifier
