package app.buddyverse.ui

import androidx.compose.runtime.Composable

/** The PC app updates through its own setup (bin\update-pc-app.ps1): no in-app updater here. */
@Composable
actual fun rememberAppInstaller(): AppInstaller? = null
