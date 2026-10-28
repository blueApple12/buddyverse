package app.buddyverse.ui.more.connectors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.buddyverse.core.api.CONNECTOR_ALIAS
import app.buddyverse.core.api.ConnectorAccount
import app.buddyverse.core.api.ConnectorTool
import app.buddyverse.core.api.aliasDraft
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Dot
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.i18n.ConnectorsPageStrings
import app.buddyverse.i18n.S
import app.buddyverse.i18n.connectorsPage
import kotlinx.coroutines.launch

/** What the small account sheet shows. */
sealed interface AccountAction {
    data class Menu(val account: ConnectorAccount) : AccountAction
    data class Rename(val account: ConnectorAccount) : AccountAction
    data class Remove(val account: ConnectorAccount) : AccountAction
    data class Add(val slug: String) : AccountAction
}

/** One app: status, the one primary action, its on/off rule for the bot, accounts, and its tools with per-tool switches. */
@Composable
fun ConnectorSheet(m: ConnectorsModel, slug: String?, botName: String, open: (String) -> Unit, onDismiss: () -> Unit) {
    val s = S.connectorsPage
    val c = Theme.colors
    // Keep the last app while the sheet slides out.
    val last = remember { arrayOfNulls<String>(1) }
    if (slug != null) last[0] = slug
    val shown = slug ?: last[0]
    var action by remember { mutableStateOf<AccountAction?>(null) }
    LaunchedEffect(slug) { if (slug != null) m.loadTools(slug) }

    Sheet(slug != null, onDismiss, title = shown?.let(m::title), heightFraction = .9f) {
        val id = shown ?: return@Sheet
        val app = m.app(id)
        val status = m.status(id)
        val accounts = m.accountsOf(id)
        val policy = m.policy
        val tools = m.tools[id]
        val off = m.disabledTools(id).toSet()
        val locked = policy?.lockedTools(id).orEmpty()
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppMark(id, app.name, status, 56.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        if (app.description.isNotBlank()) Txt(app.description, Theme.type.sub, c.secondary, maxLines = 3)
                        status.label(s)?.let { Txt(it, Theme.type.caption, status.color(c) ?: c.secondary, maxLines = 1) }
                    }
                }
            }
            item(key = "action") { PrimaryAction(m, id, status, accounts, open, onAdd = { action = AccountAction.Add(id) }) }
            if (status != Status.Available && status != Status.Connecting && policy != null) item(key = "rule") {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(s.onFor(botName), Theme.type.callout, modifier = Modifier.weight(1f), maxLines = 2)
                    if (policy.orgLocked(id)) {
                        Icon(Icons.lock, c.secondary, 20.dp)
                        Spacer(Modifier.width(6.dp))
                        Txt(s.lockedByOrg, Theme.type.caption, c.secondary, maxLines = 2)
                    } else Toggle(m.enabled(id), { m.setEnabled(id, it) }, s.onFor(botName), enabled = m.rulesWritable)
                }
            }
            if (accounts.isNotEmpty()) {
                item(key = "h-acc") { SectionLabel(s.accounts) }
                items(accounts, key = { "acc-${it.connectionId}" }) { a ->
                    AccountRow(a, s, busy = m.busy[a.connectionId] == true, onMenu = { action = AccountAction.Menu(a) }, modifier = Modifier.animateItem())
                }
            }
            item(key = "h-tools") {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val label = if (tools.isNullOrEmpty()) s.tools else "${s.tools} · ${s.toolsOn(tools.count { it.slug !in off }, tools.size)}"
                    SectionLabel(label, Modifier.weight(1f))
                    IconButton(Icons.refresh, s.refreshTools, { m.loadTools(id, refresh = true) }, tint = c.secondary)
                }
            }
            when {
                tools == null && m.toolsProblem[id] != null -> item(key = "t-err") {
                    Txt(m.toolsProblem[id]!!.text(s, S.current), Theme.type.sub, c.errorText, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                tools == null -> item(key = "t-wait") { Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) { Pulse(c.working) } }
                tools.isEmpty() -> item(key = "t-none") { Txt(s.noTools, Theme.type.sub, c.secondary, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                else -> items(tools, key = { "tool-${it.slug}" }) { t ->
                    ToolRow(t, s, on = t.slug !in off && t.slug !in locked, editable = m.rulesWritable && t.slug !in locked && policy?.orgLocked(id) != true,
                        showSwitch = policy != null) { m.setTool(id, t.slug, it) }
                }
            }
        }
    }
    AccountSheet(m, action, open, onAction = { action = it })
}

@Composable
private fun PrimaryAction(m: ConnectorsModel, id: String, status: Status, accounts: List<ConnectorAccount>, open: (String) -> Unit, onAdd: () -> Unit) {
    val s = S.connectorsPage
    val c = Theme.colors
    val busy = m.busy[id] == true
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (status) {
            Status.Connecting -> {
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pulse(c.working, 12.dp)
                    Spacer(Modifier.width(12.dp))
                    Txt(s.waiting, Theme.type.callout, maxLines = 1)
                }
                val url = m.signInUrls[id]
                if (url != null) PillButton(s.openSignIn, { open(url) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = Icons.link)
                else PillButton(s.reconnect, { m.connect(id, open, reconnect = accounts.isNotEmpty(), connectionId = accounts.firstOrNull()?.connectionId) },
                    Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = Icons.refresh, enabled = !busy)
                PillButton(s.stopWaiting, { m.stopWaiting(id) }, Modifier.fillMaxWidth(), kind = ButtonKind.Outline, haptic = Haptic.Reject)
            }
            Status.Available -> PillButton(s.connect, { m.connect(id, open) }, Modifier.fillMaxWidth(), icon = Icons.add, enabled = !busy)
            Status.Expired, Status.Broken -> PillButton(s.reconnect, {
                m.connect(id, open, reconnect = true, connectionId = accounts.firstOrNull()?.connectionId)
            }, Modifier.fillMaxWidth(), icon = Icons.refresh, enabled = !busy)
            Status.Connected, Status.Off -> PillButton(s.addAccount, onAdd, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet, icon = Icons.add, enabled = !busy)
        }
    }
}

@Composable
private fun AccountRow(a: ConnectorAccount, s: ConnectorsPageStrings, busy: Boolean, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val st = when (a.status) { "pending" -> Status.Connecting; "expired", "revoked" -> Status.Expired; "failed" -> Status.Broken; else -> Status.Connected }
    PressRow(onMenu, modifier, minHeight = 64.dp, onLongPress = onMenu, label = a.name) {
        if (busy || st == Status.Connecting) Pulse(st.color(c) ?: c.working) else Dot(st.color(c) ?: c.success, 10.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(a.name, Theme.type.headline, maxLines = 1)
            val sub = listOfNotNull(a.label.takeIf { a.alias != null && it.isNotBlank() }, st.label(s), s.inUse.takeIf { a.active }).joinToString(" · ")
            Txt(sub, Theme.type.sub, if (st == Status.Connected) c.secondary else st.color(c) ?: c.secondary, maxLines = 1)
        }
        IconButton(Icons.moreHoriz, s.accountOptions, onMenu, tint = c.secondary)
    }
}

@Composable
private fun ToolRow(t: ConnectorTool, s: ConnectorsPageStrings, on: Boolean, editable: Boolean, showSwitch: Boolean, onChange: (Boolean) -> Unit) {
    val c = Theme.colors
    val facet = when (t.facet) { "read" -> s.facetRead; "write" -> s.facetWrite; "destructive" -> s.facetDestructive; else -> null }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(t.name, Theme.type.callout, if (on || !showSwitch) c.text else c.secondary, maxLines = 1)
            val line = listOfNotNull(facet, t.description.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (line.isNotEmpty()) Txt(line, Theme.type.caption, if (t.facet == "destructive") c.errorText else c.secondary, maxLines = 2)
        }
        if (showSwitch) Toggle(on, onChange, t.name, enabled = editable)
    }
}

/** Account menu, rename, remove confirm and the add-another-account name, in one small sheet over the app sheet. */
@Composable
private fun AccountSheet(m: ConnectorsModel, action: AccountAction?, open: (String) -> Unit, onAction: (AccountAction?) -> Unit) {
    val s = S.connectorsPage
    val base = S.current
    val c = Theme.colors
    val scope = rememberCoroutineScope()
    val last = remember { arrayOfNulls<AccountAction>(1) }
    if (action != null) last[0] = action
    val shown = action ?: last[0]
    var draft by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(action) {
        problem = null
        draft = when (action) { is AccountAction.Rename -> action.account.alias.orEmpty(); else -> "" }
    }
    val close = { onAction(null) }
    val title = when (val a = shown) {
        is AccountAction.Menu -> a.account.name
        is AccountAction.Remove -> a.account.name
        is AccountAction.Rename -> base.rename
        is AccountAction.Add -> s.addAccount
        null -> null
    }
    Sheet(action != null, close, title = title) {
        when (val a = shown) {
            is AccountAction.Menu -> {
                MenuRow(Icons.edit, base.rename, c.text) { onAction(AccountAction.Rename(a.account)) }
                MenuRow(Icons.refresh, s.reconnect, c.text) {
                    m.connect(a.account.connector, open, reconnect = true, connectionId = a.account.connectionId); close()
                }
                MenuRow(Icons.delete, s.remove, c.errorText) { onAction(AccountAction.Remove(a.account)) }
            }
            is AccountAction.Rename, is AccountAction.Add -> {
                val self = (a as? AccountAction.Rename)?.account
                val slug = self?.connector ?: (a as AccountAction.Add).slug
                fun submit() {
                    val alias = draft.trim('-')
                    problem = when {
                        !CONNECTOR_ALIAS.matches(alias) -> s.nameBad
                        m.accountsOf(slug).any { it.alias == alias && it.connectionId != self?.connectionId } -> s.nameTaken
                        else -> null
                    }
                    if (problem != null) return
                    if (self == null) { m.connect(slug, open, alias = alias); close(); return }
                    if (alias == self.alias) return close()
                    scope.launch { val p = m.rename(self, alias); if (p == null) close() else { problem = p.text(s, base) } }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(draft, { draft = aliasDraft(it); problem = null }, s.accountName, mono = true)
                    problem?.let { Txt(it, Theme.type.caption, c.errorText) }
                    PillButton(if (self == null) s.connect else base.save, ::submit, Modifier.fillMaxWidth(), enabled = draft.isNotBlank())
                }
            }
            is AccountAction.Remove -> Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(s.removeAccount(a.account.name), Theme.type.headline, maxLines = 3)
                PillButton(s.remove, { m.remove(a.account); close() }, Modifier.fillMaxWidth(), kind = ButtonKind.Danger, haptic = Haptic.Reject)
                PillButton(base.cancel, close, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
            }
            null -> Unit
        }
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) =
    PressRow(onClick, minHeight = 64.dp, label = text) {
        Icon(icon, tint, 24.dp)
        Spacer(Modifier.width(16.dp))
        Txt(text, Theme.type.callout, tint, Modifier.weight(1f), maxLines = 1)
    }
