package app.buddyverse.ui.connect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** The PC app pastes the link instead of scanning. */
@Composable
actual fun QrScanner(onResult: (String) -> Unit, onClose: () -> Unit) { LaunchedEffect(Unit) { onClose() } }
