package app.buddyverse.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)

@Composable
actual fun rememberSystemBack(): () -> Unit {
    val owner = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
    return remember(owner) { { owner?.onBackPressedDispatcher?.onBackPressed() } }
}
