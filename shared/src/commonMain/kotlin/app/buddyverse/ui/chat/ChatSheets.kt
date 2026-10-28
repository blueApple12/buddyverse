package app.buddyverse.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.core.chat.WorkItem
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.usage
import app.buddyverse.i18n.chat
import app.buddyverse.i18n.home

enum class ChatAction { NewSession, Model, Usage, Rename, Branch, Compress, Stop, Close, Delete, Play }

/** The header `..` menu: everything about this conversation. Close and Delete are only for side sessions (never the Main chat). */
@Composable
fun OptionsSheet(visible: Boolean, mainChat: Boolean, hasSession: Boolean, running: Boolean, onDismiss: () -> Unit, onAction: (ChatAction) -> Unit) {
    val s = S.current
    val sc = S.chat
    val c = Theme.colors
    var confirmDelete by remember(visible) { mutableStateOf(false) }
    Sheet(visible, onDismiss, title = sc.options) {
        MenuItem(Icons.addComment, s.newSession) { onAction(ChatAction.NewSession) }
        MenuItem(Icons.tune, sc.modelAndThinking) { onAction(ChatAction.Model) }
        MenuItem(Icons.insights, S.usage.chatUsage) { onAction(ChatAction.Usage) }
        // Games open beside / over the chat, so a round fits in while the bot works.
        MenuItem(Icons.sportsEsports, S.home.play) { onAction(ChatAction.Play) }
        if (running) MenuItem(Icons.stop, s.stop) { onAction(ChatAction.Stop) }
        if (hasSession) {
            if (!mainChat) MenuItem(Icons.edit, s.rename) { onAction(ChatAction.Rename) }
            MenuItem(Icons.hub, sc.branch) { onAction(ChatAction.Branch) }
            MenuItem(Icons.inventory2, sc.compress) { onAction(ChatAction.Compress) }
        }
        if (hasSession && !mainChat) {
            MenuItem(Icons.close, s.closeSession) { onAction(ChatAction.Close) }
            if (!confirmDelete) MenuItem(Icons.delete, s.delete, c.errorText) { confirmDelete = true }
            else Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(s.confirmDelete, Theme.type.sub, c.text, Modifier.weight(1f))
                PillButton(s.deleteForGood, { onAction(ChatAction.Delete) }, kind = app.buddyverse.design.ButtonKind.Danger, height = 48.dp, haptic = Haptic.Reject)
            }
        }
    }
}

@Composable
fun RenameSheet(visible: Boolean, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val s = S.current
    var text by remember(visible) { mutableStateOf(current) }
    Sheet(visible, onDismiss, title = s.rename) {
        Field(text, { text = it }, s.untitled, Modifier.padding(horizontal = 16.dp))
        PillButton(s.save, { onSave(text.trim()) }, Modifier.fillMaxWidth().padding(16.dp), enabled = text.isNotBlank(), haptic = Haptic.Confirm)
    }
}

enum class MsgAction { Copy, Reply, Select, Trace, Edit, Regenerate, Branch }

val REACTIONS = listOf("👍", "❤️", "😂", "🎉", "😮", "🙏")

/** Long-press on a message (design §3.2): React, Copy, Reply, What it did, Select text, plus edit/regenerate/branch. */
@Composable
fun MessageSheet(
    visible: Boolean,
    mine: Boolean,
    reaction: String?,
    canEdit: Boolean,
    canRegenerate: Boolean,
    canBranch: Boolean,
    hasTrace: Boolean,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onAction: (MsgAction) -> Unit,
) {
    val s = S.current
    val sc = S.chat
    val c = Theme.colors
    Sheet(visible, onDismiss, title = sc.messageTitle) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (e in REACTIONS) Box(
                Modifier.size(52.dp).clip(CircleShape).background(if (e == reaction) c.accentFill.copy(alpha = .25f) else c.surface2)
                    .squish({ onReact(e) }, haptic = Haptic.Tick, label = sc.react),
                contentAlignment = Alignment.Center,
            ) { Txt(e, Theme.type.title2, c.text, maxLines = 1) }
        }
        MenuItem(Icons.contentCopy, s.copy) { onAction(MsgAction.Copy) }
        MenuItem(Icons.reply, s.reply) { onAction(MsgAction.Reply) }
        if (!mine && hasTrace) MenuItem(Icons.history, sc.whatItDid) { onAction(MsgAction.Trace) }
        MenuItem(Icons.description, sc.selectText) { onAction(MsgAction.Select) }
        if (canEdit) MenuItem(Icons.edit, sc.editResend) { onAction(MsgAction.Edit) }
        if (canRegenerate) MenuItem(Icons.refresh, sc.regenerate) { onAction(MsgAction.Regenerate) }
        if (canBranch) MenuItem(Icons.hub, sc.branchHere) { onAction(MsgAction.Branch) }
    }
}

/** Select text: the message in a selectable, scrollable sheet. */
@Composable
fun TextSheet(visible: Boolean, text: String, onDismiss: () -> Unit) {
    Sheet(visible, onDismiss, title = S.chat.selectText, heightFraction = .7f) {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            SelectionContainer { Txt(text, Theme.type.body, Theme.colors.text) }
        }
    }
}

/** What it did: every work card of one turn, open. */
@Composable
fun TraceSheet(visible: Boolean, works: List<WorkItem>, spec: BuddySpec, sessionId: String?, profile: String, onDismiss: () -> Unit) {
    Sheet(visible, onDismiss, title = S.chat.whatItDid, heightFraction = .8f) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)) {
            if (works.isEmpty()) item { Txt(S.current.nothingHere, Theme.type.sub, Theme.colors.tertiary, Modifier.padding(8.dp)) }
            items(works, key = { it.key }) { w -> WorkCard(w, spec, null, sessionId, profile, startOpen = true) }
        }
    }
}
