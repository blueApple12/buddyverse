package app.buddyverse.ui.connect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** The PWA is opened from the link itself (its own origin is prefilled): nothing to scan. */
@Composable
actual fun QrScanner(onResult: (String) -> Unit, onClose: () -> Unit) { LaunchedEffect(Unit) { onClose() } }
