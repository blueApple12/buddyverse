package app.buddyverse.ui

import androidx.compose.runtime.Composable
import app.buddyverse.design.Chip
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.i18n.S

/** Opens a fresh quick (temporary) chat with the brain. [compact]: icon only, for a crowded header. */
@Composable
fun QuickChatButton(compact: Boolean = false) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val label = S.current.quickChat
    val open = { nav.push(Screen.Chat(store.quickChatTarget())) }
    if (compact) IconButton(Icons.bolt, label, open) else Chip(label, false, open, icon = Icons.bolt)
}
