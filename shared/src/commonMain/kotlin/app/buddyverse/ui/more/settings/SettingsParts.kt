package app.buddyverse.ui.more.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.buddyverse.core.Bot
import app.buddyverse.core.api.ConfigField
import app.buddyverse.core.api.UsageBar
import app.buddyverse.core.api.at
import app.buddyverse.core.api.configRaw
import app.buddyverse.core.api.configRawSave
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Card
import app.buddyverse.design.Chip
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.settingsPage
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.bot.openCount
import app.buddyverse.ui.chat.MenuItem
import app.buddyverse.ui.chat.MenuPopup
import app.buddyverse.ui.chat.Spinner
import app.buddyverse.ui.ltr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// ------------------------------------------------------------------ app rows

/** A setting with a few choices: label on top, chips below (they never squeeze the label). */
@Composable
internal fun <T> ChoiceRow(icon: ImageVector, label: String, choices: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, Theme.colors.text, 24.dp)
            Spacer(Modifier.width(14.dp))
            Txt(label, Theme.type.headline, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 30.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((v, text) in choices) Chip(text, v == selected, { onPick(v) })
        }
    }
}

/** A switch row: the whole row toggles. */
@Composable
internal fun ToggleRow(icon: ImageVector, label: String, on: Boolean, detail: String? = null, onChange: (Boolean) -> Unit) {
    PressRow({ onChange(!on) }, minHeight = 64.dp, label = label) {
        Icon(icon, Theme.colors.text, 24.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(label, Theme.type.headline, maxLines = 2)
            if (!detail.isNullOrBlank()) Txt(detail, Theme.type.sub, Theme.colors.secondary, maxLines = 2)
        }
        Toggle(on, onChange, label)
    }
}

/** Which bot's engine settings: one chip per bot (each Hermes profile has its own settings file). */
@Composable
internal fun BotChips(bots: List<Bot>, selected: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in bots) Chip(b.name, b.profile == selected, { onPick(b.profile) }, icon = if (b.isDefault) Icons.psychology else Icons.smartToy)
    }
}

internal fun categoryIcon(id: String): ImageVector = when (id) {
    "general" -> Icons.settings
    "agent" -> Icons.smartToy
    "terminal" -> Icons.terminal
    "display" -> Icons.palette
    "delegation" -> Icons.group
    "memory" -> Icons.psychology
    "compression" -> Icons.inventory2
    "security" -> Icons.lock
    "browser" -> Icons.language
    "voice", "stt" -> Icons.mic
    "tts" -> Icons.volumeUp
    "logging" -> Icons.description
    "discord" -> Icons.chat
    "auxiliary" -> Icons.tune
    else -> Icons.settings
}

// ------------------------------------------------------------------ engine settings rows

@Composable
private fun SaveMark(st: SaveState?) {
    val c = Theme.colors
    when (st) {
        SaveState.Saving -> Spinner(14.dp, c.tertiary)
        SaveState.Saved -> Icon(Icons.check, c.success, 16.dp)
        is SaveState.Failed -> Icon(Icons.error, c.errorText, 16.dp)
        null -> Unit
    }
}

/**
 * One engine setting, edited the way its schema type says: a switch, a pick list, a number, text, a list (one per line) or
 * JSON. Typing saves after a pause; long press (or ⋯) offers Reset to default and Copy the key.
 */
@Composable
internal fun ConfigRow(f: ConfigField, m: EngineSettings, onOptions: (ConfigField) -> Unit) {
    val t = S.settingsPage
    val c = Theme.colors
    val toasts = LocalToasts.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    val v = m.valueOf(f.key)
    val st = m.saves[f.key]
    val label = f.label()

    Box {
        when (f.type) {
            "boolean" -> {
                val on = (v as? JsonPrimitive)?.booleanOrNull ?: (v as? JsonPrimitive)?.content?.let { it == "true" || it == "on" || it == "1" } ?: false
                PressRow({ m.set(f.key, JsonPrimitive(!on)) }, minHeight = 64.dp, onLongPress = { menu = true }, label = label) {
                    Heading(label, f, st, Modifier.weight(1f))
                    Toggle(on, { m.set(f.key, JsonPrimitive(it)) }, label)
                }
            }
            "select" -> PressRow({ onOptions(f) }, minHeight = 64.dp, onLongPress = { menu = true }, label = label) {
                Heading(label, f, st, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Txt(textOf(v, "string").ifEmpty { t.none }, Theme.type.callout, c.secondary, Modifier.width(120.dp), maxLines = 1)
                Icon(Icons.chevronRight, c.tertiary, 22.dp, mirror = true)
            }
            else -> Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Heading(label, f, st, Modifier.weight(1f))
                    IconButton(Icons.moreHoriz, label, { menu = true })
                }
                TextEditor(f, m)
            }
        }
        if (menu) MenuPopup(label, alignEnd = true, onDismiss = { menu = false }) {
            val d = m.defaultOf(f.key)
            if (d != null && d != v) MenuItem(Icons.undo, t.resetDefault) { menu = false; if (m.reset(f.key)) toasts.show(ToastData(t.wasReset)) }
            MenuItem(Icons.contentCopy, t.copyKey) { menu = false; clipboard.setText(AnnotatedString(f.key)); toasts.show(ToastData(t.keyCopied)) }
        }
    }
}

@Composable
private fun Heading(label: String, f: ConfigField, st: SaveState?, modifier: Modifier) {
    val t = S.settingsPage
    val c = Theme.colors
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(label, Theme.type.headline, modifier = Modifier.weight(1f, fill = false), maxLines = 2)
            Spacer(Modifier.width(6.dp))
            SaveMark(st)
        }
        f.ownDescription?.let { Txt(it, Theme.type.sub, c.secondary, maxLines = 3) }
        (st as? SaveState.Failed)?.let { Txt(t.notSaved(it.why), Theme.type.caption, c.errorText, maxLines = 3) }
    }
}

@Composable
private fun TextEditor(f: ConfigField, m: EngineSettings) {
    val t = S.settingsPage
    val c = Theme.colors
    val server = m.values?.at(f.key)
    var text by remember(f.key, m.profile) { mutableStateOf(textOf(server, f.type)) }
    // A reload (or a reset) brings a new value in: show it unless the user is mid-edit.
    val serverText = textOf(server, f.type)
    LaunchedEffect(serverText) { if (m.edits[f.key] == null) text = serverText }
    val multi = f.type == "list" || f.type == "object"
    val parsed = parseFor(text, f.type, server)
    val bad = parsed == null && !(f.type == "number" && text.isBlank())
    Field(
        text,
        { new ->
            text = new
            parseFor(new, f.type, server)?.let { m.set(f.key, it, debounceMs = 900) }
        },
        placeholder = textOf(m.defaultOf(f.key), f.type).ifEmpty { if (f.type == "list") t.onePerLine else "" },
        modifier = Modifier.padding(top = 6.dp, end = 12.dp),
        singleLine = !multi,
        mono = multi || f.key.endsWith("path") || f.key.endsWith("cwd") || f.key.endsWith("dir"),
        keyboard = if (f.type == "number") KeyboardType.Decimal else KeyboardType.Text,
        minHeight = if (multi) 96.dp else 56.dp,
    )
    if (bad) Txt(if (f.type == "number") t.notANumber else t.badJson, Theme.type.caption, c.errorText, Modifier.padding(top = 4.dp), maxLines = 1)
}

/** Every setting of one category. */
@Composable
internal fun CategorySheet(category: String?, m: EngineSettings, onOptions: (ConfigField) -> Unit, onDismiss: () -> Unit) {
    val t = S.settingsPage
    val held = remember { arrayOfNulls<String>(1) }
    if (category != null) held[0] = category
    val shown = held[0]
    Sheet(category != null, onDismiss, title = shown?.let { t.category(it) }, heightFraction = .92f) {
        val fields = m.schema?.fields.orEmpty().filter { it.category == shown }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(fields, key = { it.key }) { ConfigRow(it, m, onOptions) }
        }
    }
}

/** A select's choices; long lists get a search. Picking saves at once. */
@Composable
internal fun OptionsSheet(field: ConfigField?, m: EngineSettings, onDismiss: () -> Unit) {
    val t = S.settingsPage
    val c = Theme.colors
    val fb = LocalFeedback.current
    val held = remember { arrayOfNulls<ConfigField>(1) }
    if (field != null) held[0] = field
    val f = held[0]
    val tall = (f?.options?.size ?: 0) > 8
    Sheet(field != null, onDismiss, title = f?.label(), heightFraction = if (tall) .8f else null) {
        if (f != null) key(f.key) {
            var q by remember { mutableStateOf("") }
            if (f.searchable || f.options.size > 12) Field(q, { q = it }, t.searchSettings, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            val cur = textOf(m.valueOf(f.key), "string")
            val shown = f.options.distinct().filter { q.isBlank() || it.contains(q.trim(), ignoreCase = true) }
            LazyColumn(Modifier.fillMaxWidth().then(if (tall) Modifier.weight(1f) else Modifier)) {
                items(shown, key = { "o:$it" }) { o ->
                    PressRow({ m.set(f.key, JsonPrimitive(o)); fb.haptic(Haptic.Confirm); onDismiss() }, minHeight = 56.dp, label = o.ifEmpty { t.none }) {
                        Txt(o.ifEmpty { t.none }, Theme.type.callout, c.text, Modifier.weight(1f), maxLines = 1)
                        if (o == cur) Icon(Icons.check, c.accent, 22.dp)
                    }
                }
            }
        }
    }
}

/** The whole settings file as text (`GET/PUT /api/config/raw`): a full replacement, refused unless it's a YAML mapping. */
@Composable
internal fun RawSheet(visible: Boolean, profile: String, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val t = S.settingsPage
    val s = S.current
    val c = Theme.colors
    val store = LocalStore.current
    val toasts = LocalToasts.current
    val fb = LocalFeedback.current
    val scope = rememberCoroutineScope()
    val generation = openCount(visible)
    Sheet(visible, onDismiss, title = t.rawFile, heightFraction = .92f) {
        key(generation, profile) {
            var text by remember { mutableStateOf<String?>(null) }
            var path by remember { mutableStateOf("") }
            var error by remember { mutableStateOf<String?>(null) }
            var busy by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                try { val (y, p) = store.api.configRaw(profile); text = y; path = p } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.why() }
            }
            if (path.isNotEmpty()) Txt(ltr(path), Theme.type.micro, c.secondary, Modifier.padding(horizontal = 20.dp), maxLines = 1)
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp)).background(c.surface2)) {
                val cur = text
                if (cur == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (error == null) Spinner(24.dp, c.tertiary) }
                else CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    BasicTextField(cur, { text = it }, Modifier.fillMaxSize().padding(12.dp), textStyle = Theme.type.code.copy(color = c.text), cursorBrush = SolidColor(c.accent))
                }
            }
            error?.let { Txt(it, Theme.type.sub, c.errorText, Modifier.padding(horizontal = 20.dp), maxLines = 4) }
            PillButton(s.save, {
                val y = text ?: return@PillButton
                busy = true; error = null
                scope.launch {
                    try {
                        store.api.configRawSave(profile, y)
                        fb.haptic(Haptic.Confirm); toasts.show(ToastData(t.rawSaved)); onSaved(); onDismiss()
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.why(); fb.haptic(Haptic.Reject) } finally { busy = false }
                }
            }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), enabled = text != null && !busy, haptic = Haptic.Confirm)
        }
    }
}

// ------------------------------------------------------------------ plan & credits

@Composable
private fun UsageMeter(label: String, bar: UsageBar) {
    val c = Theme.colors
    val t = S.settingsPage
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(label, Theme.type.callout, modifier = Modifier.weight(1f), maxLines = 1)
            if (bar.remaining != null && bar.total != null) Txt(ltr(t.left(bar.remaining, bar.total)), Theme.type.caption, c.secondary, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        val left = (bar.fill ?: 0.0).toFloat().coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.surface2)) {
            if (left > 0f) Box(Modifier.fillMaxWidth(maxOf(left, .02f)).height(8.dp).clip(CircleShape).background(if (left < .15f) c.needsYou else c.working))
        }
    }
}

/**
 * Plan & credits card: plan, balance and usage bars, a pending switch with Keep my plan, credit presets (each asks once
 * before money moves), auto top-up, keep going on credits, payment and monthly limit, plans and the browser portal.
 */
@Composable
internal fun BillingCard(b: BillingModel, onPlans: () -> Unit, onOpen: (String) -> Unit) {
    val t = S.settingsPage
    val s = S.current
    val c = Theme.colors
    val toasts = LocalToasts.current
    var buying by remember { mutableStateOf<Pair<String, String>?>(null) }
    val info = b.info
    Column(Modifier.fillMaxWidth()) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            when {
                !b.loaded -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { Spinner(22.dp, c.tertiary) }
                info == null -> Txt(b.error ?: s.error, Theme.type.sub, c.errorText, maxLines = 3)
                !info.loggedIn -> Txt(if (info.freeTier) t.freeTier(info.freeTierModel) else t.notSignedIn, Theme.type.callout, c.secondary)
                else -> {
                    val cur = b.plans?.current
                    Txt(listOfNotNull(cur?.tierName ?: info.usage?.planName, info.org).joinToString(" · ").ifEmpty { t.plan }, Theme.type.headline, maxLines = 2)
                    info.usage?.renews?.let { Txt(t.renews(it), Theme.type.sub, c.secondary, maxLines = 1) }
                    info.balance?.let { bal ->
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Txt(t.balance, Theme.type.callout, modifier = Modifier.weight(1f), maxLines = 1)
                            Txt(ltr(bal), Theme.type.title2, maxLines = 1)
                        }
                    }
                    info.usage?.planBar?.let { UsageMeter(t.planBar, it) }
                    info.usage?.topupBar?.let { UsageMeter(t.topupBar, it) }

                    // A scheduled switch or cancellation can be undone.
                    val pending = when {
                        cur?.pendingTier != null -> t.pendingSwitch(cur.pendingTier, cur.pendingWhen)
                        cur?.cancelAtEnd == true -> t.pendingCancel(cur.cancelWhen)
                        else -> null
                    }
                    if (pending != null) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(pending, Theme.type.sub, c.needsYouText, Modifier.weight(1f), maxLines = 2)
                        PillButton(t.keepPlan, b::resume, kind = ButtonKind.Outline, height = 48.dp, enabled = !b.busy)
                    }

                    // Credits: pick a preset, then confirm (money moves only on that second tap).
                    if (info.canCharge == true && info.presets.isNotEmpty()) {
                        Txt(t.addCredits, Theme.type.caption, c.secondary, Modifier.padding(top = 14.dp), maxLines = 1)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            info.presets.forEachIndexed { i, a ->
                                val shown = info.presetsDisplay.getOrNull(i) ?: "\$$a"
                                Chip(shown, buying?.first == a, { buying = if (buying?.first == a) null else a to shown })
                            }
                        }
                        val charge = b.charge
                        buying?.let { (a, shown) ->
                            PillButton(if (charge?.state == "charging") t.charging else t.buy(shown), {
                                b.buy(a) { state ->
                                    toasts.show(ToastData(when (state) {
                                        "settled" -> t.chargeDone
                                        "failed" -> t.chargeFailed(b.charge?.note ?: s.error)
                                        else -> t.chargeUnknown
                                    }))
                                }
                                buying = null
                            }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = charge?.state != "charging" && !b.busy, haptic = Haptic.Confirm)
                        }
                        when (charge?.state) {
                            "charging" -> Txt(t.charging, Theme.type.sub, c.working, Modifier.padding(top = 6.dp), maxLines = 1)
                            "unknown" -> Txt(t.chargeUnknown, Theme.type.sub, c.needsYouText, Modifier.padding(top = 6.dp), maxLines = 3)
                            "failed" -> Txt(t.chargeFailed(charge?.note ?: s.error), Theme.type.sub, c.errorText, Modifier.padding(top = 6.dp), maxLines = 3)
                        }
                    }
                }
            }
        }
        if (info?.loggedIn == true) {
            info.autoReload?.let { ar ->
                ToggleRow(Icons.refresh, t.autoReload, ar.enabled, listOfNotNull(ar.threshold, ar.reloadTo).takeIf { ar.enabled && it.size == 2 }?.joinToString(" → ")?.let(::ltr)) { on ->
                    b.setAutoReload(on, ar.thresholdUsd, ar.reloadToUsd)
                }
            }
            info.keepGoing?.let { kg -> ToggleRow(Icons.bolt, t.keepGoing, kg) { b.setKeepGoing(it) } }
            info.payment?.let { pm ->
                val text = listOfNotNull(pm.brand, pm.last4?.let { "•••• $it" }, pm.wallet, pm.email).joinToString(" ")
                if (text.isNotBlank()) Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.lock, c.text, 24.dp)
                    Spacer(Modifier.width(14.dp))
                    Txt(t.payment, Theme.type.headline, modifier = Modifier.weight(1f), maxLines = 1)
                    Txt(ltr(text), Theme.type.callout, c.secondary, maxLines = 1)
                }
            }
            info.cap?.let { cap ->
                if (cap.limit != null) Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.timer, c.text, 24.dp)
                    Spacer(Modifier.width(14.dp))
                    Txt(t.monthlyCap, Theme.type.headline, modifier = Modifier.weight(1f), maxLines = 1)
                    Txt(ltr(listOfNotNull(cap.spent, cap.limit).joinToString(" / ")), Theme.type.callout, c.secondary, maxLines = 1)
                }
            }
            if (b.plans?.tiers?.isNotEmpty() == true) PressRow(onPlans, minHeight = 64.dp, label = t.plans) {
                Icon(Icons.tune, c.text, 24.dp)
                Spacer(Modifier.width(14.dp))
                Txt(t.plans, Theme.type.headline, modifier = Modifier.weight(1f), maxLines = 1)
                Icon(Icons.chevronRight, c.tertiary, 24.dp, mirror = true)
            }
            (b.plans?.portal ?: info.portal)?.let { url ->
                PressRow({ onOpen(url) }, minHeight = 64.dp, label = t.portal) {
                    Icon(Icons.link, c.text, 24.dp)
                    Spacer(Modifier.width(14.dp))
                    Txt(t.portal, Theme.type.headline, modifier = Modifier.weight(1f), maxLines = 1)
                }
            }
        }
        StepUpBlock(b, onOpen)
    }
}

/** Billing changes that need more rights ask for a browser approval; the code to type there shows here. */
@Composable
private fun StepUpBlock(b: BillingModel, onOpen: (String) -> Unit) {
    val t = S.settingsPage
    val c = Theme.colors
    val toasts = LocalToasts.current
    if (!b.needsStepUp && !b.steppingUp) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (!b.steppingUp) PillButton(t.stepUp, { b.stepUp { toasts.show(ToastData(t.stepUpDone)) } }, Modifier.fillMaxWidth(), kind = ButtonKind.Outline, icon = Icons.lock)
        else {
            Txt(t.stepUpWaiting, Theme.type.sub, c.working, maxLines = 2)
            b.stepUpCode?.let { (url, code) ->
                if (code.isNotBlank()) Txt(t.stepUpCode(code), Theme.type.headline, c.text, Modifier.padding(top = 6.dp))
                if (url.isNotBlank()) PillButton(t.openLink, { onOpen(url) }, Modifier.padding(top = 8.dp), kind = ButtonKind.Quiet, height = 48.dp, icon = Icons.link)
            }
        }
    }
}

/** The plans: current, upgrades (finished in the browser), and switches down (quoted first, then scheduled). */
@Composable
internal fun PlansSheet(visible: Boolean, b: BillingModel, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val t = S.settingsPage
    val s = S.current
    val c = Theme.colors
    Sheet(visible, { b.closeQuote(); onDismiss() }, title = t.plans, heightFraction = .8f) {
        val p = b.plans
        val tiers = p?.tiers.orEmpty().sortedBy { it.order }
        val curOrder = tiers.firstOrNull { it.current }?.order ?: -1
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(tiers, key = { it.id }) { tier ->
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Txt(tier.name, Theme.type.headline, maxLines = 1)
                            val sub = listOfNotNull(tier.price.takeIf { it.isNotBlank() }?.let { t.perMonth(ltr(it)) }, tier.credits).joinToString(" · ")
                            if (sub.isNotEmpty()) Txt(sub, Theme.type.sub, c.secondary, maxLines = 1)
                        }
                        when {
                            tier.current -> Txt(t.current, Theme.type.caption, c.accent, maxLines = 1)
                            !tier.enabled || p?.canChange == false -> Unit
                            tier.order > curOrder -> PillButton(t.upgrade, { p?.portal?.let(onOpen) }, kind = ButtonKind.Outline, height = 48.dp, enabled = p?.portal != null)
                            else -> PillButton(t.switchTo, { b.quote(tier) }, kind = ButtonKind.Quiet, height = 48.dp, enabled = !b.busy && b.previewFor == null)
                        }
                    }
                    if (b.previewFor?.id == tier.id) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(c.surface2).padding(14.dp)) {
                        val q = b.preview
                        if (q == null) Txt(t.previewing, Theme.type.sub, c.secondary, maxLines = 1)
                        else {
                            Txt(t.effect(q.effect.orEmpty(), q.target ?: tier.name, q.effectiveAt?.take(10)?.let(::ltr)), Theme.type.callout, c.text)
                            q.reason?.takeIf { it.isNotBlank() && q.effect == "blocked" }?.let { Txt(it, Theme.type.sub, c.secondary, Modifier.padding(top = 4.dp), maxLines = 3) }
                            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                when (q.effect) {
                                    "scheduled" -> PillButton(t.confirmSwitch, b::confirmQuote, Modifier.weight(1f), height = 48.dp, enabled = !b.busy, haptic = Haptic.Confirm)
                                    "charge_now" -> p?.portal?.let { url -> PillButton(t.portal, { onOpen(url) }, Modifier.weight(1f), height = 48.dp, icon = Icons.link) }
                                }
                                PillButton(s.cancel, b::closeQuote, Modifier.weight(1f), kind = ButtonKind.Quiet, height = 48.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}
