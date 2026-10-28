package app.buddyverse.ui.more.messaging

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.buddyverse.core.api.MsgEnvVar
import app.buddyverse.core.api.MsgPairingUser
import app.buddyverse.core.api.MsgPlatform
import app.buddyverse.core.api.MsgTest
import app.buddyverse.core.nowMillis
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Chip
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.MessagingPageStrings
import app.buddyverse.i18n.S
import app.buddyverse.i18n.messagingPage
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.ago
import app.buddyverse.ui.ltr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Draft value of a field before any typing: allowlists come back with their real value, secrets never do. */
private fun initial(v: MsgEnvVar): String = if (v.isList) v.value.orEmpty() else ""

/**
 * One platform: its state, the QR setup where the backend has one, the on/off switch, people asking and allowed,
 * every setting (advanced ones folded), the incoming address, and Test / Save pinned at the bottom.
 */
@Composable
fun PlatformSheet(m: MessagingModel, id: String?, botName: String, open: (String) -> Unit, onDismiss: () -> Unit) {
    val s = S.messagingPage
    val base = S.current
    val c = Theme.colors
    val scope = rememberCoroutineScope()
    // Keep the last platform while the sheet slides out.
    val last = remember { arrayOfNulls<String>(1) }
    if (id != null) last[0] = id
    val shown = id ?: last[0]
    val p = shown?.let(m::platform)

    Sheet(id != null, onDismiss, title = p?.let { plain(it.name, botName) }, heightFraction = .92f) {
        if (p == null) return@Sheet
        val drafts = remember(p.id) { mutableStateMapOf<String, String>() }
        var advanced by remember(p.id) { mutableStateOf(false) }
        val setup = m.setup?.takeIf { it.platform == p.id }
        val tone = p.tone
        val vars = p.envVars.distinctBy { it.key }
        val main = vars.filterNot { it.advanced }.sortedByDescending { it.required }
        val extra = vars.filter { it.advanced }
        val name = plain(p.name, botName)

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlatformMark(p.id, name, tone, busy = setup != null || p.state == "connecting", size = 56.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        val about = plain(p.description, botName)
                        if (about.isNotBlank()) Txt(about, Theme.type.sub, c.secondary, maxLines = 3)
                        Txt(stateLabel(p.state, s), Theme.type.caption, tone.color(c), maxLines = 1)
                        val err = plain(p.errorMessage, botName)
                        if (err.isNotBlank() && tone != Tone.Good) Txt(err, Theme.type.caption, c.errorText, maxLines = 3)
                    }
                }
            }
            if (setup != null) {
                item(key = "setup") { SetupPane(m, setup, p, botName, open) }
                return@LazyColumn
            }
            if (p.id in QR_SETUP) item(key = "qr") {
                val fresh = !p.configured
                val label = when (p.id) { "telegram" -> if (fresh) s.tgQuick else s.tgNew; else -> if (fresh) s.waQuick else s.waRelink }
                PillButton(label, { m.beginSetup(p.id) }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    kind = if (fresh) ButtonKind.Primary else ButtonKind.Quiet, icon = Icons.qrCodeScanner)
            }
            ownLink(p.docsUrl)?.let { link ->
                item(key = "docs") {
                    PillButton(s.openSite(name), { open(link) }, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), kind = ButtonKind.Outline, height = 48.dp, icon = Icons.link)
                }
            }
            if (p.configured || p.enabled || vars.none { it.required }) item(key = "on") {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(s.onFor(botName), Theme.type.callout, modifier = Modifier.weight(1f), maxLines = 2)
                    Toggle(m.enabled(p), { m.setEnabled(p.id, it) }, s.onFor(botName))
                }
            }
            val asking = m.pendingOn(p.id)
            if (asking.isNotEmpty()) {
                item(key = "h-ask") { SectionLabel(s.askingSection, color = c.needsYouText) }
                items(asking, key = { "ask-${it.requestId ?: it.userId}" }) { u -> AskingRow(m, u, name, {}, Modifier.animateItem(placementSpec = Motion.smooth())) }
            }
            if (main.isNotEmpty() || extra.isNotEmpty()) item(key = "h-set") { SectionLabel(s.settings) }
            items(main, key = { "f-${it.key}" }) { v ->
                EnvField(v, drafts[v.key] ?: initial(v), { drafts[v.key] = it }, botName, open) { m.clearValue(p.id, v.key) }
            }
            if (extra.isNotEmpty()) {
                item(key = "adv") {
                    val turn by animateFloatAsState(if (advanced) 180f else 0f, Motion.snappy())
                    PressRow({ advanced = !advanced }, minHeight = 56.dp, label = base.advanced) {
                        Txt(base.advanced, Theme.type.callout, modifier = Modifier.weight(1f), maxLines = 1)
                        Icon(Icons.expandMore, c.secondary, 24.dp, Modifier.graphicsLayer { rotationZ = turn })
                    }
                }
                if (advanced) items(extra, key = { "f-${it.key}" }) { v ->
                    EnvField(v, drafts[v.key] ?: initial(v), { drafts[v.key] = it }, botName, open, Modifier.animateItem(placementSpec = Motion.smooth())) { m.clearValue(p.id, v.key) }
                }
            }
            p.homeChannel?.let { h ->
                val label = h.name?.takeIf { it.isNotBlank() } ?: h.chatId
                if (!label.isNullOrBlank()) item(key = "home") {
                    Txt(s.homeChat(label), Theme.type.sub, c.secondary, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), maxLines = 2)
                }
            }
            p.ingressUrl?.takeIf { it.isNotBlank() }?.let { url -> item(key = "ingress") { Incoming(url) } }
            val people = m.approvedOn(p.id)
            if (people.isNotEmpty()) {
                item(key = "h-people") { SectionLabel(s.people) }
                items(people, key = { "ok-${it.userId}" }) { u -> AllowedRow(m, u, Modifier.animateItem(placementSpec = Motion.smooth())) }
            }
            item(key = "end") { Spacer(Modifier.heightIn(min = 8.dp)) }
        }
        if (setup == null) {
            val changed = p.envVars.filter { v -> drafts[v.key]?.let { it != initial(v) } == true }
            val env = changed.mapNotNull { v -> drafts[v.key]?.trim()?.takeIf { it.isNotEmpty() }?.let { v.key to it } }.toMap()
            val clear = changed.filter { v -> drafts[v.key].isNullOrBlank() && v.isList && v.isSet }.map { it.key }
            Actions(
                test = m.tests[p.id], testing = m.busy["test:${p.id}"] == true, saving = m.busy[p.id] == true,
                canTest = p.configured || p.enabled, canSave = env.isNotEmpty() || clear.isNotEmpty(), bot = botName,
                onTest = { m.test(p.id) },
                onSave = { scope.launch { if (m.save(p.id, env, clear, enable = if (!p.configured && !p.enabled) true else null)) drafts.clear() } },
            )
        }
    }
}

/** One setting: a 48dp label row that unfolds what it is (and where to get it), the field, and Show for secrets. */
@Composable
private fun EnvField(v: MsgEnvVar, value: String, onValue: (String) -> Unit, bot: String, open: (String) -> Unit, modifier: Modifier = Modifier, onClear: () -> Unit) {
    val s = S.messagingPage
    val c = Theme.colors
    var help by remember(v.key) { mutableStateOf(false) }
    var reveal by remember(v.key) { mutableStateOf(false) }
    val label = plain(v.prompt.ifBlank { v.key }, bot)
    val about = listOf(plain(v.description, bot), plain(v.help, bot)).filter { it.isNotBlank() }.distinct()
    val link = ownLink(v.url)
    val canClear = v.isSet && !v.isList
    val more = about.isNotEmpty() || link != null || canClear
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .then(if (more) Modifier.squish({ help = !help }, scale = .98f, haptic = Haptic.Tick, label = s.aboutField(label)) else Modifier)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(if (v.required) label else "$label · ${s.optional}", Theme.type.caption.copy(fontSize = Theme.type.sub.fontSize), c.secondary, Modifier.weight(1f), maxLines = 2)
            if (more) Icon(Icons.info, if (help) c.accent else c.tertiary, 20.dp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(
                value, onValue,
                placeholder = if (canClear) s.savedValue(v.redactedValue) else label,
                modifier = Modifier.weight(1f),
                password = v.isPassword && !reveal,
                mono = v.isPassword || v.isList,
            )
            if (v.isPassword) IconButton(Icons.visibility, if (reveal) s.hide else s.show, { reveal = !reveal }, tint = if (reveal) c.accent else c.secondary)
        }
        AnimatedVisibility(help, enter = expandVertically(Motion.smooth()) + fadeIn(Motion.fade()), exit = shrinkVertically(Motion.smooth()) + fadeOut(Motion.fade())) {
            Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                about.forEach { Txt(it, Theme.type.sub, c.secondary) }
                if (link != null) PillButton(s.openPage, { open(link) }, kind = ButtonKind.Quiet, height = 48.dp, icon = Icons.link)
                if (canClear) PillButton(s.removeSaved, { help = false; onClear() }, kind = ButtonKind.Quiet, height = 48.dp, icon = Icons.delete, haptic = Haptic.Reject)
            }
        }
    }
}

/** Someone asked to chat: Allow is the row's one button. */
@Composable
fun AskingRow(m: MessagingModel, u: MsgPairingUser, platformName: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val base = S.current
    val s = S.messagingPage
    val who = u.userName?.takeIf { it.isNotBlank() } ?: u.userId
    val since = u.ageMinutes?.let { ago(nowMillis() / 1000.0 - it * 60, nowMillis(), base) }.orEmpty()
    PersonRow(who, if (since.isBlank()) platformName else s.askedOn(platformName, ltr(since)), u.userId, onOpen = onOpen, modifier = modifier) {
        PillButton(base.allow, { m.approve(u) }, height = 48.dp, icon = Icons.check, haptic = Haptic.Confirm)
    }
}

/** Someone who may chat; × asks once inline (turns into Remove for 3 s), so a slip never removes anyone. */
@Composable
private fun AllowedRow(m: MessagingModel, u: MsgPairingUser, modifier: Modifier = Modifier) {
    val s = S.messagingPage
    val c = Theme.colors
    val who = u.userName?.takeIf { it.isNotBlank() } ?: u.userId
    var confirm by remember(u.userId) { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { delay(3_000); confirm = false } }
    PersonRow(who, ltr(u.userId).takeIf { u.userName != null }, u.userId, onOpen = {}, modifier = modifier) {
        if (confirm) PillButton(s.remove, { confirm = false; m.revoke(u) }, kind = ButtonKind.Danger, height = 48.dp, haptic = Haptic.Reject)
        else IconButton(Icons.close, s.removePerson(who), { confirm = true }, tint = c.secondary)
    }
}

/** The address senders post to (webhook-style platforms), with copy. */
@Composable
private fun Incoming(url: String) {
    val s = S.messagingPage
    val base = S.current
    val c = Theme.colors
    val toasts = LocalToasts.current
    @Suppress("DEPRECATION") val clip = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        SectionLabel(s.incoming)
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(url, Theme.type.code, c.text, Modifier.weight(1f), maxLines = 3)
            IconButton(Icons.contentCopy, base.copy, { clip.setText(AnnotatedString(url)); toasts.show(ToastData(s.copied)) }, tint = c.secondary)
        }
    }
}

/** The pinned bottom bar: the last test's answer, then Test and Save. */
@Composable
private fun Actions(test: MsgTest?, testing: Boolean, saving: Boolean, canTest: Boolean, canSave: Boolean, bot: String, onTest: () -> Unit, onSave: () -> Unit) {
    val s = S.messagingPage
    val base = S.current
    val c = Theme.colors
    val fb = LocalFeedback.current
    LaunchedEffect(test) {
        if (test != null && (test.ok || test.state != null)) fb.haptic(if (test.ok) Haptic.Confirm else Haptic.Reject)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (test != null && !testing) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (test.ok) Icons.checkCircle else Icons.error, if (test.ok) c.success else c.error, 22.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Txt(if (test.ok) s.testOk else s.testFailed, Theme.type.callout, maxLines = 1)
                val msg = plain(test.message, bot)
                if (msg.isNotBlank()) Txt(msg, Theme.type.sub, c.secondary, maxLines = 3)
                posture(test.authPosture, s)?.let { Txt(it, Theme.type.sub, c.secondary, maxLines = 2) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(if (testing) s.testing else s.test, onTest, Modifier.weight(1f), kind = ButtonKind.Quiet, icon = Icons.bolt, enabled = canTest && !testing)
            PillButton(if (saving) s.saving else base.save, onSave, Modifier.weight(1f), enabled = canSave && !saving, haptic = Haptic.Confirm)
        }
    }
}

private fun posture(p: String?, s: MessagingPageStrings): String? = when (p) {
    "open" -> s.postureOpen
    "allowlist" -> s.postureAllowlist
    "pairing" -> s.posturePairing
    "deny_all" -> s.postureDenyAll
    else -> null
}

/** The Telegram / WhatsApp QR setup, step by step; Cancel (or the sheet's ×) drops it on the backend too. */
@Composable
private fun SetupPane(m: MessagingModel, st: Setup, p: MsgPlatform, botName: String, open: (String) -> Unit) {
    val s = S.messagingPage
    val base = S.current
    val c = Theme.colors
    val telegram = st.platform == "telegram"
    var name by remember { mutableStateOf(botName) }
    var extraIds by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(p.whatsappSetup?.mode?.takeIf { it == "self-chat" } ?: "bot") }
    var allowed by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (st.stage) {
            Setup.Stage.Options -> if (telegram) {
                Field(name, { name = it }, s.tgBotName)
                PillButton(s.tgCreate, { m.startTelegram(name.trim()) }, Modifier.fillMaxWidth(), icon = Icons.qrCodeScanner)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(s.waModeBot, mode == "bot", { mode = "bot" })
                    Chip(s.waModeSelf, mode == "self-chat", { mode = "self-chat" })
                }
                if (mode == "bot") Field(allowed, { allowed = it }, s.waAllowed, mono = true)
                PillButton(s.waShowQr, { m.startWhatsapp(mode, if (mode == "bot") allowed.trim() else "", reset = p.configured) }, Modifier.fillMaxWidth(), icon = Icons.qrCodeScanner)
            }
            Setup.Stage.Waiting -> {
                if (st.preparing || st.qr == null) Waiting(if (telegram) base.loading else s.waPreparing)
                else {
                    if (telegram) {
                        st.handle?.let { Txt(it, Theme.type.code, c.text, maxLines = 1) }
                        st.link?.let { l -> PillButton(s.tgOpen, { open(l) }, Modifier.fillMaxWidth(), icon = Icons.send) }
                    }
                    QrImage(st.qr, 240.dp, if (telegram) s.tgOpen else s.waScan)
                    Txt(if (telegram) s.scanElsewhere else s.waScan, Theme.type.sub, c.secondary, maxLines = 2)
                    Waiting(if (telegram) s.tgWaiting else null)
                }
            }
            Setup.Stage.Ready -> {
                Icon(Icons.checkCircle, c.success, 48.dp)
                val who = st.handle?.let(::ltr)
                Txt(if (telegram) s.tgReady(who ?: "Telegram") else who?.let(s.waLinked) ?: s.stConnected, Theme.type.headline, maxLines = 2)
                if (telegram) Field(extraIds, { extraIds = it }, s.tgMoreIds, mono = true)
                st.problem?.let { Txt(it.text(s, base, st.detail, botName), Theme.type.sub, c.errorText, maxLines = 3) }
                PillButton(s.finish, { if (telegram) m.applyTelegram(extraIds) else m.applyWhatsapp() }, Modifier.fillMaxWidth(), icon = Icons.check, haptic = Haptic.Confirm)
            }
            Setup.Stage.Applying -> Waiting(s.finishing)
            Setup.Stage.Failed -> {
                Icon(Icons.error, c.error, 48.dp)
                val why = when (st.problem) {
                    Problem.Gone -> s.setupExpired
                    null, Problem.Other -> plain(st.detail, botName).ifBlank { s.setupFailed }
                    else -> st.problem.text(s, base, st.detail, botName)
                }
                Txt(why, Theme.type.callout, c.errorText, maxLines = 4)
                PillButton(base.retry, { m.beginSetup(st.platform) }, Modifier.fillMaxWidth(), icon = Icons.refresh)
            }
        }
        if (st.stage != Setup.Stage.Applying) PillButton(base.cancel, { m.cancelSetup() }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
    }
}

@Composable
private fun Waiting(text: String?) {
    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Pulse(Theme.colors.working, 12.dp)
        if (text != null) {
            Spacer(Modifier.width(12.dp))
            Txt(text, Theme.type.callout, maxLines = 2)
        }
    }
}

/** Restart asks once: a forced restart pauses every bot the engine serves and stops replies in progress. */
@Composable
fun RestartSheet(visible: Boolean, onRestart: () -> Unit, onDismiss: () -> Unit) {
    val s = S.messagingPage
    val base = S.current
    Sheet(visible, onDismiss, title = s.restartConfirmTitle) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt(s.restartConfirmBody, Theme.type.callout, Theme.colors.secondary, maxLines = 4)
            Box(Modifier.heightIn(min = 4.dp))
            PillButton(s.restartNow, { onDismiss(); onRestart() }, Modifier.fillMaxWidth(), kind = ButtonKind.Danger, icon = Icons.refresh, haptic = Haptic.Reject)
            PillButton(base.cancel, onDismiss, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
        }
    }
}
