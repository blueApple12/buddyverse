package app.buddyverse.ui.more.insights

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.Bot
import app.buddyverse.core.api.DayUse
import app.buddyverse.core.api.ModelUse
import app.buddyverse.core.api.Plan
import app.buddyverse.core.nowMillis
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Card
import app.buddyverse.design.Dot
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.insightsPage
import app.buddyverse.ui.ago
import app.buddyverse.ui.ltr
import kotlin.math.max
import kotlin.math.min

/** `2026-10-25` → `25/10` (Western digits, design §9.6). */
fun shortDay(iso: String): String = iso.split('-').let { p -> if (p.size == 3) "${p[2].trimStart('0')}/${p[1].trimStart('0')}" else iso }

/** "All bots" plus one living buddy per bot; the picked one wears an accent ring. Bots hop when picked. */
@Composable
fun BotPicker(bots: List<Bot>, picked: String?, cue: BuddyCue?, onPick: (String?) -> Unit) {
    val c = Theme.colors
    val t = S.insightsPage
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PickTile(t.allBots, picked == null, { onPick(null) }) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(if (picked == null) c.accentFill else c.surface2), contentAlignment = Alignment.Center) {
                Icon(Icons.insights, if (picked == null) c.onAccent else c.text, 26.dp)
            }
        }
        bots.filter { !it.hidden }.forEach { b ->
            val on = picked == b.profile
            PickTile(b.name, on, { onPick(b.profile) }) {
                Box(Modifier.size(56.dp).border(2.dp, if (on) c.accent else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                    Buddy(b.spec, Mood.Idle, 44.dp, cue = if (on) cue?.takeIf { nowMillis() - it.serial < 2_000 } else null, interactive = false)
                }
            }
        }
    }
}

@Composable
private fun PickTile(label: String, selected: Boolean, onClick: () -> Unit, face: @Composable () -> Unit) {
    val c = Theme.colors
    Column(
        Modifier.width(72.dp).heightIn(min = 84.dp).squish(onClick, haptic = Haptic.Tick, label = label).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { face() }
        Spacer(Modifier.height(2.dp))
        Txt(label, Theme.type.micro, if (selected) c.accent else c.secondary, maxLines = 1)
    }
}

/** A stat that also picks what the day chart plots. */
@Composable
fun StatTile(label: String, value: String, note: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val ring by animateColorAsState(if (selected) c.accent else Color.Transparent, Motion.fade())
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.squish(onClick, scale = .97f, haptic = Haptic.Tick, label = label).heightIn(min = 92.dp).clip(shape).background(c.surface)
            .border(2.dp, ring, shape).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Txt(label, Theme.type.caption, if (selected) c.accent else c.secondary, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Txt(ltr(value), Theme.type.title, maxLines = 1)
        if (note != null) Txt(note, Theme.type.micro, c.secondary, maxLines = 1)
    }
}

/**
 * One bar per day, oldest at the start edge. Tap or drag across it to read a day (a tick per day crossed). [grow] 0→1 raises the
 * bars when the numbers land. Bars with nothing keep a 2dp stub so the calendar still reads.
 */
@Composable
fun DayChart(series: List<DayUse>, metric: Metric, selected: Int, grow: Float, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val fb = LocalFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val values = series.map { metric.of(it.use) }
    val top = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val n = series.size
    val sel by rememberUpdatedState(selected)
    val pick by rememberUpdatedState<(Float, Int) -> Unit> { x, w ->
        if (n > 0 && w > 0) {
            val slot = (x / (w.toFloat() / n)).toInt().coerceIn(0, n - 1)
            val i = if (rtl) n - 1 - slot else slot
            if (i != sel) { fb.haptic(Haptic.Tick); onSelect(i) }
        }
    }
    val label = series.getOrNull(selected)?.let { "${shortDay(it.day)} ${metric.label(metric.of(it.use))}" }.orEmpty()
    Canvas(
        modifier.fillMaxWidth().height(168.dp)
            .pointerInput(Unit) { detectTapGestures { o -> pick(o.x, size.width) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> pick(change.position.x, size.width) } }
            .semantics { contentDescription = label },
    ) {
        if (n == 0) return@Canvas
        val slot = size.width / n
        val bw = slot * if (n > 45) .72f else .62f
        val radius = CornerRadius(min(bw / 2f, 5.dp.toPx()))
        val stub = 2.dp.toPx()
        values.forEachIndexed { i, v ->
            val at = if (rtl) n - 1 - i else i
            val x = at * slot + (slot - bw) / 2f
            val h = if (v <= 0) stub else max(4.dp.toPx(), (v / top).toFloat() * size.height * grow)
            val color = when {
                i == selected -> c.accent
                v <= 0 -> c.hairline
                else -> c.accent.copy(alpha = .38f)
            }
            drawRoundRect(color, topLeft = Offset(x, size.height - h), size = Size(bw, h), cornerRadius = radius)
        }
    }
}

/** Under the chart: first and last day at the edges, the picked day's value in the middle. */
@Composable
fun ChartAxis(series: List<DayUse>, metric: Metric, selected: Int) {
    val c = Theme.colors
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(series.firstOrNull()?.let { shortDay(it.day) }.orEmpty(), Theme.type.micro, c.tertiary, Modifier.width(56.dp), maxLines = 1)
        val d = series.getOrNull(selected)
        Txt(
            d?.let { "${shortDay(it.day)} · ${metric.label(metric.of(it.use))}" }.orEmpty(),
            Theme.type.caption.copy(textAlign = TextAlign.Center, textDirection = TextDirection.Ltr), c.text, Modifier.weight(1f), maxLines = 1,
        )
        Txt(series.lastOrNull()?.let { shortDay(it.day) }.orEmpty(), Theme.type.micro.copy(textAlign = TextAlign.End), c.tertiary, Modifier.width(56.dp), maxLines = 1)
    }
}

/** A share bar under a row: [part] of the period, animated in on `smooth`. */
@Composable
fun ShareBar(fraction: Float, color: Color = Theme.colors.accent, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val c = Theme.colors
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), Motion.smooth())
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(c.surface2)) {
        if (f > 0f) Box(Modifier.fillMaxWidth(max(f, .02f)).height(height).clip(CircleShape).background(color))
    }
}

/** A label/value line in a sheet or card; the value keeps LTR (numbers, model names). */
@Composable
fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    val c = Theme.colors
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Theme.type.sub, c.secondary, Modifier.weight(1f), maxLines = 1)
        Txt(ltr(value), Theme.type.callout, c.text, maxLines = 1)
    }
}

/** Everything the engine knows about one model's use in the period. */
@Composable
fun ModelSheet(model: ModelUse?, total: Long, onDismiss: () -> Unit) {
    val t = S.insightsPage
    val s = S.current
    val c = Theme.colors
    // Keep the last model while the sheet slides out.
    var last by remember { mutableStateOf(model) }
    if (model != null) last = model
    Sheet(model != null, onDismiss, title = last?.model?.let(::ltr)) {
        val m = last ?: return@Sheet
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (m.provider.isNotBlank()) Txt(m.provider, Theme.type.sub, c.secondary, Modifier.padding(horizontal = 20.dp), maxLines = 1)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.padding(horizontal = 20.dp)) { ShareBar(share(m.use.tokens.toDouble(), total.toDouble()), height = 10.dp) }
            Spacer(Modifier.height(8.dp))
            Fact(t.cost, money(m.use.cost))
            if (m.use.billed > 0) Fact(t.billedLabel, money(m.use.billed))
            Fact(t.input, grouped(m.use.input))
            Fact(t.output, grouped(m.use.output))
            if (m.use.cacheRead > 0) Fact(t.cacheRead, grouped(m.use.cacheRead))
            if (m.use.reasoning > 0) Fact(t.reasoning, grouped(m.use.reasoning))
            Fact(t.chats, grouped(m.use.sessions))
            Fact(t.calls, grouped(m.use.apiCalls))
            if (m.toolCalls > 0) Fact(t.toolCalls, grouped(m.toolCalls))
            if (m.perSession > 0) Fact(t.perChat, compact(m.perSession))
            m.contextWindow?.let { Fact(t.contextWindow, compact(it)) }
            m.maxOutput?.let { Fact(t.maxOutput, compact(it)) }
            m.tools?.let { Fact(t.canUseTools, if (it) t.yes else t.no) }
            m.vision?.let { Fact(t.seesImages, if (it) t.yes else t.no) }
            m.thinks?.let { Fact(t.thinks, if (it) t.yes else t.no) }
            if (m.lastUsedAt > 0) Fact(t.lastUsed, ago(m.lastUsedAt, nowMillis(), s).let { if (it == s.justNow) it else s.ago(it) })
            Spacer(Modifier.height(8.dp))
            PillButton(s.close, onDismiss, Modifier.fillMaxWidth().padding(horizontal = 20.dp), kind = ButtonKind.Quiet)
        }
    }
}

/** The signed-in paid plan (`usage.bars`): name, state, and one bar per pot of credit. */
@Composable
fun PlanCard(plan: Plan, modifier: Modifier = Modifier) {
    val t = S.insightsPage
    val c = Theme.colors
    val tone = when (plan.status) { "depleted" -> c.error; "low" -> c.needsYou; else -> c.success }
    Card(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(tone, 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(plan.name ?: t.plan, Theme.type.headline, maxLines = 1)
                Txt(listOfNotNull(t.planStatus(plan.status), plan.renews?.let(t.renews)).joinToString(" · "), Theme.type.sub, c.secondary, maxLines = 2)
            }
            plan.left?.let { Txt(ltr(it), Theme.type.headline, maxLines = 1) }
        }
        plan.bars.forEach { b ->
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(if (b.topUp) t.topUpBar else t.planBar, Theme.type.caption, c.secondary, Modifier.weight(1f), maxLines = 1)
                Txt(t.leftOf(ltr(b.left), ltr(b.total)), Theme.type.caption, c.text, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            ShareBar(b.fill, tone, height = 10.dp)
        }
    }
}

/** Empty, error and unsupported states: one line, at most one button. */
@Composable
fun InsightsMessage(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(text, Theme.type.title2.copy(textAlign = TextAlign.Center), Theme.colors.secondary)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            PillButton(action, onAction, icon = Icons.refresh, kind = ButtonKind.Primary)
        }
    }
}

/** A buddy line on top of the page: who, how much, and the buddy mouthing it when numbers land. */
@Composable
fun InsightsHero(bot: Bot?, others: List<Bot>, mood: Mood, cue: BuddyCue?, talkAt: Long, headline: String, sub: String?) {
    val c = Theme.colors
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val shown = cue?.takeIf { nowMillis() - it.serial < 2_000 }
        if (bot != null) Buddy(bot.spec, mood, 72.dp, cue = shown, talkAt = talkAt, talkChars = headline.length)
        else if (others.isNotEmpty()) app.buddyverse.buddy.BuddyGroup(others.map { it.spec }, others.map { mood }, 72.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Txt(headline, Theme.type.title2, maxLines = 2)
            if (sub != null) Txt(sub, Theme.type.sub, c.secondary, maxLines = 1)
        }
    }
}

/** A hop cue now (used when a bot or period is picked). */
fun hopNow() = BuddyCue(nowMillis(), OneShot.Hop)
