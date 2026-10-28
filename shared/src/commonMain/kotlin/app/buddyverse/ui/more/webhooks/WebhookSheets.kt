package app.buddyverse.ui.more.webhooks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.Bot
import app.buddyverse.core.api.WebhookCreated
import app.buddyverse.core.api.WebhookDraft
import app.buddyverse.core.api.WebhookRoute
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
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.WebhooksPageStrings
import app.buddyverse.i18n.webhooksPage
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.ago
import app.buddyverse.ui.ltr
import app.buddyverse.ui.parseIsoMillis
import kotlinx.coroutines.delay

/** "Telegram · direct" / "Log only": where a route's result goes. */
fun deliverLine(r: WebhookRoute, t: WebhooksPageStrings): String =
    if (r.deliverOnly) "${t.deliverLabel(r.deliver)} · ${t.direct}" else t.deliverLabel(r.deliver)

fun eventsLine(r: WebhookRoute, t: WebhooksPageStrings): String =
    if (r.events.isEmpty()) t.allEvents else ltr(r.events.joinToString(", "))

/** The model's latest cue, unless it is old news for a buddy composed just now. */
fun freshCue(m: WebhooksModel): BuddyCue? = m.cue?.takeIf { nowMillis() - it.serial < 2_000 }

/** Copy to the clipboard with a confirm tick and a toast. */
@Composable
fun rememberCopy(): (text: String, toast: String) -> Unit {
    @Suppress("DEPRECATION") val clip = LocalClipboardManager.current
    val fb = LocalFeedback.current
    val toasts = LocalToasts.current
    return remember<(String, String) -> Unit>(clip, fb, toasts) { { text, toast -> clip.setText(AnnotatedString(text)); fb.haptic(Haptic.Confirm); toasts.show(ToastData(toast)) } }
}

/** Keeps the last non-null value so a sheet's content stays put while it slides away. */
@Composable
private fun <T : Any> rememberLast(v: T?): T? {
    val box = remember { arrayOfNulls<Any>(1) }
    if (v != null) box[0] = v
    @Suppress("UNCHECKED_CAST")
    return box[0] as T?
}

@Composable
private fun Caps(text: String) = Txt(text.uppercase(), Theme.type.caps, Theme.colors.secondary, maxLines = 1)

/** A value you copy (link, secret): LTR mono text on a field-colored block with a 48dp copy button. */
@Composable
private fun CopyBox(label: String, value: String, copyLabel: String, onCopy: () -> Unit) {
    val c = Theme.colors
    Column {
        Caps(label)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface2).padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(value, Theme.type.code, c.text, Modifier.weight(1f).padding(vertical = 12.dp), maxLines = 4)
            IconButton(Icons.contentCopy, copyLabel, onCopy, tint = c.secondary)
        }
    }
}

/** Small read-only tag (event names, skills). Not a control, so no touch size. */
@Composable
private fun Tag(text: String, color: Color = Theme.colors.text, fill: Color = Theme.colors.surface2) =
    Box(Modifier.clip(CircleShape).background(fill).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Txt(text, Theme.type.code, color, maxLines = 1)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tags(names: List<String>) = FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    names.forEach { Tag(it) }
}

/** Label on the start side, value after it. */
@Composable
private fun Fact(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Theme.type.sub, Theme.colors.secondary, Modifier.width(104.dp), maxLines = 1)
        Box(Modifier.weight(1f)) { content() }
    }
}

/** One webhook: on/off, its link, where it delivers, events, skills, prompt, delete. Shown while [name] != null. */
@Composable
fun HookSheet(m: WebhooksModel, name: String?, bot: Bot?, mood: Mood, onDismiss: () -> Unit) {
    val t = S.webhooksPage
    val s = S.current
    val c = Theme.colors
    val copy = rememberCopy()
    val shown = rememberLast(name)
    Sheet(name != null, onDismiss, title = shown) {
        val r = m.route(shown)
        if (r == null) {
            Box(Modifier.fillMaxWidth().heightIn(min = 140.dp), contentAlignment = Alignment.Center) { if (bot != null) Buddy(bot.spec, Mood.Thinking, 72.dp) }
            return@Sheet
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (bot != null) { Buddy(bot.spec, if (r.enabled) mood else Mood.Sleeping, 56.dp, cue = freshCue(m)); Spacer(Modifier.width(14.dp)) }
                Column(Modifier.weight(1f)) {
                    Txt(if (r.enabled) t.on else t.off, Theme.type.headline, if (r.enabled) c.success else c.secondary, maxLines = 1)
                    Txt(deliverLine(r, t), Theme.type.sub, c.secondary, maxLines = 2)
                }
                Toggle(r.enabled, { on -> m.toggle(r, on) }, r.name)
            }
            if (r.url.isNotEmpty()) CopyBox(t.link, r.url, t.copyLink) { copy(r.url, t.linkCopied) }
            Column {
                Fact(t.deliverTo) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(t.deliverLabel(r.deliver), Theme.type.callout, maxLines = 1)
                        if (r.deliverOnly) { Spacer(Modifier.width(8.dp)); Tag(t.direct, c.needsYouText, c.needsYou.copy(alpha = .18f)) }
                    }
                }
                Fact(t.events) { if (r.events.isEmpty()) Txt(t.allEvents, Theme.type.callout, maxLines = 1) else Tags(r.events) }
                if (r.skills.isNotEmpty()) Fact(t.skills) { Tags(r.skills) }
                Fact(t.secret) { Txt(if (r.secretSet) t.secretSet else t.secretNone, Theme.type.callout, if (r.secretSet) c.text else c.needsYouText, maxLines = 1) }
                r.createdAt?.let(::parseIsoMillis)?.let { ms ->
                    val a = ago(ms / 1000.0, nowMillis(), s)
                    Fact(t.created) { Txt(if (a == s.justNow) a else s.ago(a), Theme.type.callout, maxLines = 1) }
                }
            }
            if (r.description.isNotBlank()) Column {
                Caps(t.about)
                Txt(r.description, Theme.type.callout, c.text)
            }
            if (r.prompt.isNotBlank()) Column {
                Caps(t.prompt)
                Spacer(Modifier.height(6.dp))
                Txt(r.prompt, Theme.type.callout, c.text, Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface2).padding(16.dp))
            }
            if (r.script.isNotBlank()) Column {
                Caps(t.script)
                Txt(r.script, Theme.type.code, c.text)
            }
            PillButton(s.delete, { onDismiss(); m.delete(r) }, Modifier.fillMaxWidth(), kind = ButtonKind.Danger, icon = Icons.delete, haptic = Haptic.Reject)
        }
    }
}

/** Long-press menu of a webhook row (design §3.7: the row shows one control, the rest live here). */
@Composable
fun HookMenu(m: WebhooksModel, name: String?, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val t = S.webhooksPage
    val s = S.current
    val copy = rememberCopy()
    val shown = rememberLast(name)
    Sheet(name != null, onDismiss, title = shown) {
        val r = m.route(shown) ?: return@Sheet
        MenuRow(Icons.visibility, s.open) { onDismiss(); onOpen(r.name) }
        MenuRow(if (r.enabled) Icons.pause else Icons.playArrow, if (r.enabled) t.turnOff else t.turnOn) { onDismiss(); m.toggle(r, !r.enabled) }
        if (r.url.isNotEmpty()) MenuRow(Icons.contentCopy, t.copyLink) { onDismiss(); copy(r.url, t.linkCopied) }
        MenuRow(Icons.delete, s.delete, danger = true) { onDismiss(); m.delete(r) }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = Theme.colors
    PressRow(onClick, minHeight = 56.dp, label = label) {
        Icon(icon, if (danger) c.error else c.text)
        Spacer(Modifier.width(16.dp))
        Txt(label, Theme.type.body, if (danger) c.errorText else c.text, maxLines = 1)
    }
}

/** New webhook: the form, then (once) its link and secret to copy. */
@Composable
fun CreateSheet(m: WebhooksModel, open: Boolean, bot: Bot?, onDismiss: () -> Unit) {
    val t = S.webhooksPage
    var made by remember { mutableStateOf<WebhookCreated?>(null) }
    // Forget the one-time secret once the sheet has slid away.
    LaunchedEffect(open) { if (!open) { delay(400); made = null } }
    val result = made
    Sheet(open, onDismiss, title = result?.let { t.createdTitle(it.route.name) } ?: t.newWebhook, heightFraction = if (result == null) .92f else null) {
        if (result != null) CreatedBody(result, bot, onDismiss) else FormBody(m, bot) { made = it }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.FormBody(m: WebhooksModel, bot: Bot?, onCreated: (WebhookCreated) -> Unit) {
    val t = S.webhooksPage
    val c = Theme.colors
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var events by remember { mutableStateOf("") }
    var skills by remember { mutableStateOf("") }
    var deliver by remember { mutableStateOf("log") }
    var direct by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var chatId by remember { mutableStateOf("") }
    var script by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val turn by animateFloatAsState(if (more) 180f else 0f, Motion.snappy())
    Column(
        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (bot != null) { Buddy(bot.spec, if (m.creating) Mood.Working else Mood.Listening, 48.dp, cue = freshCue(m), lookAtComposer = true); Spacer(Modifier.width(12.dp)) }
            Field(name, { name = webhookName(it); err = null }, t.name, Modifier.weight(1f), mono = true)
        }
        Field(description, { description = it; err = null }, t.description)
        Field(prompt, { prompt = it; err = null }, t.promptHint, singleLine = false, minHeight = 120.dp)
        Field(events, { events = it; err = null }, t.eventsHint, mono = true)
        Field(skills, { skills = it; err = null }, t.skillsHint, mono = true)
        Spacer(Modifier.height(4.dp))
        Caps(t.deliverTo)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DELIVER_TARGETS.forEach { d ->
                Chip(t.deliverLabel(d), deliver == d, { deliver = d; if (d == "log") direct = false; err = null })
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(t.directSwitch, Theme.type.callout, if (deliver == "log") c.tertiary else c.text, Modifier.weight(1f))
            Toggle(direct, { direct = it; err = null }, t.directSwitch, enabled = deliver != "log")
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).squish({ more = !more }, scale = .97f, haptic = Haptic.Tick, label = t.moreOptions),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(t.moreOptions, Theme.type.button, c.accent, Modifier.weight(1f), maxLines = 1)
            Icon(Icons.expandMore, c.accent, 24.dp, Modifier.graphicsLayer { rotationZ = turn })
        }
        AnimatedVisibility(more, enter = expandVertically(Motion.smooth()) + fadeIn(Motion.fade()), exit = shrinkVertically(Motion.smooth()) + fadeOut(Motion.fade())) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(chatId, { chatId = it; err = null }, t.chatId, mono = true)
                Field(script, { script = it; err = null }, t.scriptHint, mono = true)
                Field(secret, { secret = it; err = null }, t.secretHint, password = true)
            }
        }
        err?.let { Txt(it, Theme.type.sub, c.errorText) }
        PillButton(
            if (m.creating) t.creating else t.create,
            {
                m.create(
                    WebhookDraft(
                        name = name, description = description, events = words(events), prompt = prompt, script = script,
                        skills = words(skills), deliver = deliver, deliverOnly = direct, chatId = chatId, secret = secret,
                    ),
                    onError = { err = it }, onCreated = onCreated,
                )
            },
            Modifier.fillMaxWidth(), icon = Icons.check, enabled = !m.creating,
        )
    }
}

@Composable
private fun ColumnScope.CreatedBody(made: WebhookCreated, bot: Bot?, onDone: () -> Unit) {
    val t = S.webhooksPage
    val s = S.current
    val copy = rememberCopy()
    val cheer = remember(made) { BuddyCue(nowMillis(), OneShot.Done) }
    Column(
        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (bot != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Buddy(bot.spec, Mood.Idle, 88.dp, cue = cheer) }
        if (made.route.url.isNotEmpty()) CopyBox(t.link, made.route.url, t.copyLink) { copy(made.route.url, t.linkCopied) }
        if (made.secret.isNotEmpty()) {
            CopyBox(t.secret, made.secret, s.copy) { copy(made.secret, t.secretCopied) }
            Txt(t.secretOnce, Theme.type.sub, Theme.colors.needsYouText)
        }
        PillButton(s.done, onDone, Modifier.fillMaxWidth())
    }
}
