package app.buddyverse.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.buddyverse.core.ChatTarget
import app.buddyverse.core.SessionRow
import app.buddyverse.core.nowMillis
import app.buddyverse.design.Dot
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.ago

/**
 * The bot's sessions (design §3.5, "can't close a session"): Main chat first, every other session with its own 48dp ×,
 * then New session. Place it last in a full-size Box.
 */
@Composable
fun SessionSheet(
    visible: Boolean,
    profile: String,
    botName: String,
    botChatId: String?,
    live: Set<String>,
    currentId: String?,
    onMain: Boolean,
    onDismiss: () -> Unit,
    onOpen: (ChatTarget) -> Unit,
    onClose: (SessionRow) -> Unit,
    onNew: () -> Unit,
) {
    val store = LocalStore.current
    val s = S.current
    val sessions by store.sessions.collectAsState()
    LaunchedEffect(visible) { if (visible) store.refreshSessions() }
    val rows = sessions.rows.filter { it.profile == profile && it.id != botChatId }
    val now = nowMillis()
    Sheet(visible, onDismiss, title = s.sessionsOf(botName)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
            item(key = "main") {
                SessionLine(Icons.chat, s.mainChat, null, onMain, botChatId != null && botChatId in live, { onOpen(ChatTarget(profile)) }, null, s.closeSession)
            }
            items(rows, key = { it.id }) { r ->
                SessionLine(null, r.title ?: s.untitled, ago(r.lastActive, now, s), r.id == currentId, r.id in live,
                    { onOpen(ChatTarget(profile, r.id, botChat = false)) }, { onClose(r) }, s.closeSession)
            }
            if (rows.isEmpty() && sessions.loaded) item(key = "none") {
                Txt(s.nothingHere, Theme.type.sub, Theme.colors.tertiary, Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
        PillButton(s.newSession, onNew, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), icon = Icons.addComment)
    }
}

@Composable
private fun SessionLine(icon: ImageVector?, title: String, sub: String?, current: Boolean, live: Boolean, onClick: () -> Unit, onClose: (() -> Unit)?, closeLabel: String) {
    val c = Theme.colors
    PressRow(onClick, minHeight = 64.dp, label = title, modifier = Modifier.padding(end = 0.dp)) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when {
                current -> Dot(c.accent, 10.dp)
                icon != null -> Icon(icon, c.secondary, 22.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, Theme.type.callout, if (current) c.accent else c.text, maxLines = 1)
            if (sub != null) Txt(sub, Theme.type.caption, c.tertiary, maxLines = 1)
        }
        if (live) Spinner(16.dp, c.working, Modifier.padding(horizontal = 8.dp))
        if (onClose != null) IconButton(Icons.close, closeLabel, onClose)
    }
}
