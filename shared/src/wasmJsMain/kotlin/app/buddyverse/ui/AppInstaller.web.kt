package app.buddyverse.ui

import androidx.compose.runtime.Composable

/** A PWA updates itself: the service worker fetches the new build from the gateway. */
@Composable
actual fun rememberAppInstaller(): AppInstaller? = null
