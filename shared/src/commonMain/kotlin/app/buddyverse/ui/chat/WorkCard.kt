package app.buddyverse.ui.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.core.api.subagentInterrupt
import app.buddyverse.core.api.subagentTail
import app.buddyverse.core.chat.Step
import app.buddyverse.core.chat.StepStatus
import app.buddyverse.core.chat.Todo
import app.buddyverse.core.chat.WorkItem
import app.buddyverse.core.nowMillis
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.chat
import app.buddyverse.core.ChatTarget
import app.buddyverse.ui.LocalNav
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.Screen
import app.buddyverse.ui.ltr
import app.buddyverse.ui.toolLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import app.buddyverse.i18n.Strings

/** "12s", "1m 12s", "1h 3m". */
fun elapsedText(ms: Long, s: Strings): String {
    val t = (ms / 1000).coerceAtLeast(0)
    return when {
        t < 60 -> s.unit(t, "s")
        t < 3600 -> s.unit(t / 60, "m") + " " + s.unit(t % 60, "s")
        else -> s.unit(t / 3600, "h") + " " + s.unit((t % 3600) / 60, "m")
    }
}

/** A helper (subagent) is a mini buddy of the parent's shape, lighter (design §3.2). */
fun helperSpec(parent: BuddySpec, id: String) = parent.copy(id = parent.id + id, color = BuddyColors.mix(parent.color, Color.White, .35f))

@Composable
private fun chevronTurn(open: Boolean): Float {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val turn by animateFloatAsState(if (open) 0f else if (rtl) 90f else -90f, Motion.snappy(), label = "chevron")
    return turn
}

@Composable
fun Spinner(size: Dp, color: Color, modifier: Modifier = Modifier) {
    val rot = rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "r")
    Canvas(modifier.size(size).graphicsLayer { rotationZ = rot.value }) {
        val w = 2.dp.toPx()
        drawArc(color, 0f, 270f, false, topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}

/**
 * The steps of one turn (design §3.2 Work card): header with the mini buddy, time and the live phrase; folded while it runs
 * (last 3 steps), closed when done; every step opens to its input and output. Helpers (subagents) can be watched live.
 */
@Composable
fun WorkCard(work: WorkItem, spec: BuddySpec, phrase: String?, sessionId: String?, profile: String, modifier: Modifier = Modifier, startOpen: Boolean = false) {
    val s = S.current
    val sc = S.chat
    val c = Theme.colors
    var open by remember(work.key) { mutableStateOf(startOpen) }
    val running = work.running
    // Nothing to show: no card (a plain reply never gets "0 steps").
    if (!running && work.steps.isEmpty() && work.reasoning.isBlank()) return
    val now by produceState(nowMillis(), running) { while (running) { delay(1000); value = nowMillis() } }
    val elapsed = elapsedText((work.endedAt ?: now) - work.at, s)
    val runningStep = work.steps.lastOrNull { it.status == StepStatus.Running && !it.subagent }
    val helpers = work.steps.filter { it.subagent && it.status == StepStatus.Running }
    // Helpers outlive the turn: while one works the card stays live (buddy busy, its row shown, watchable, stoppable).
    val helperLine = helpers.lastOrNull()?.takeIf { !running }?.let { h -> h.context?.takeIf { it.isNotBlank() } ?: sc.helper }
    val live = runningStep?.let { toolLabel(it.tool, it.context, s) }
        ?: helperLine
        ?: phrase?.takeIf { it.isNotBlank() }?.let { if (' ' in it) it else toolLabel(it, null, s) }
        ?: s.thinking
    val busy = running || helpers.isNotEmpty()
    val pulse = rememberInfiniteTransition(label = "shimmer").animateFloat(.55f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "a")
    val turn = chevronTurn(open)
    val title = when {
        running -> "${sc.working} · ${ltr(elapsed)}"
        work.steps.isEmpty() -> sc.thoughtFor(ltr(elapsed))
        else -> listOf(sc.whatItDid, sc.steps(work.steps.size), ltr(elapsed)).joinToString(" · ")
    }
    Column(
        modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(c.surface)
            .border(1.dp, c.hairline, RoundedCornerShape(20.dp)).animateContentSize(Motion.smooth()),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).squish({ open = !open }, scale = .98f, haptic = Haptic.Tick, label = title).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Buddy(remember(spec, work.key) { spec.copy(id = spec.id + "#" + work.key) }, if (busy) Mood.Working else Mood.Idle, 30.dp, interactive = false)
            for (h in helpers.take(3)) key(h.id) { Buddy(helperSpec(spec, h.id), Mood.Working, 18.dp, Modifier.padding(start = 2.dp), interactive = false) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Txt(title, Theme.type.sub, c.text, maxLines = 1)
                if (busy) Crossfade(live, animationSpec = tween(320), label = "phrase") { l ->
                    Txt(l, Theme.type.caption, c.secondary, Modifier.graphicsLayer { alpha = pulse.value }, maxLines = 1)
                }
            }
            if (work.steps.isNotEmpty() || work.reasoning.isNotBlank()) Icon(Icons.expandMore, c.secondary, 22.dp, Modifier.graphicsLayer { rotationZ = turn })
        }
        if (open && work.reasoning.isNotBlank()) Reasoning(work.reasoning)
        val shown = when {
            open -> work.steps
            running -> work.steps.takeLast(3)
            else -> helpers.takeLast(3)
        }
        if (!open && running && work.steps.size > 3) Txt(s.moreSteps(work.steps.size - 3), Theme.type.micro, c.tertiary, Modifier.padding(start = 52.dp, bottom = 2.dp))
        for (step in shown) key(step.id) { StepRow(step, spec, sessionId, profile) }
        if (shown.isNotEmpty()) Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun Reasoning(text: String) {
    val c = Theme.colors
    val s = S.current
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().squish({ open = !open }, scale = .98f, haptic = Haptic.Tick, label = s.thinking).padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.psychology, c.secondary, 18.dp)
            Spacer(Modifier.width(6.dp))
            Txt(s.thinking, Theme.type.caption, c.secondary, maxLines = 1)
        }
        Txt(text.trim(), Theme.type.sub, c.secondary, Modifier.padding(top = 4.dp), maxLines = if (open) Int.MAX_VALUE else 3)
    }
}

@Composable
private fun StatusGlyph(status: StepStatus) {
    val c = Theme.colors
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when (status) {
            StepStatus.Running -> Spinner(16.dp, c.working)
            StepStatus.Done -> Icon(Icons.check, c.success, 20.dp)
            StepStatus.Failed -> Icon(Icons.close, c.error, 20.dp)
        }
    }
}

@Composable
private fun StepRow(step: Step, spec: BuddySpec, sessionId: String?, profile: String) {
    val s = S.current
    val c = Theme.colors
    var open by remember(step.id) { mutableStateOf(false) }
    val label = if (step.subagent) step.context?.takeIf { it.isNotBlank() } ?: S.chat.helper else toolLabel(step.tool, step.context, s)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).squish({ open = !open }, scale = .98f, haptic = Haptic.Tick, label = label).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step.subagent) Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                Buddy(helperSpec(spec, step.id), if (step.status == StepStatus.Running) Mood.Working else Mood.Idle, 24.dp, interactive = false)
            } else Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { StatusGlyph(step.status) }
            Spacer(Modifier.width(10.dp))
            Txt(label, Theme.type.sub, if (step.status == StepStatus.Failed) c.errorText else c.text, Modifier.weight(1f), maxLines = 1)
            step.progress?.takeIf { it.isNotBlank() }?.let { Txt(ltr(it), Theme.type.micro, c.secondary, Modifier.padding(start = 6.dp), maxLines = 1) }
            step.durationS?.let { Txt(ltr(elapsedText((it * 1000).toLong(), s)), Theme.type.micro, c.tertiary, Modifier.padding(start = 6.dp), maxLines = 1) }
        }
        if (open) StepDetails(step, sessionId, profile)
    }
}

private val pretty = Json { prettyPrint = true }

private fun prettyJson(raw: String): String =
    runCatching { pretty.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(raw)) }.getOrDefault(raw)

@Composable
private fun StepDetails(step: Step, sessionId: String?, profile: String) {
    val s = S.current
    Column(Modifier.fillMaxWidth().padding(start = 52.dp, end = 12.dp, bottom = 10.dp)) {
        Txt(ltr(step.tool), Theme.type.micro, Theme.colors.tertiary, maxLines = 1)
        if (step.subagent) HelperLive(step, sessionId, profile)
        step.childSession?.let { child ->
            val nav = LocalNav.current
            PillButton(S.chat.openHelperChat, { nav.push(Screen.Chat(ChatTarget(profile, child, botChat = false, watch = true))) },
                Modifier.padding(top = 8.dp), kind = ButtonKind.Outline, height = 48.dp)
        }
        step.args?.takeIf { it.isNotBlank() && it != "{}" }?.let { MonoBlock(s.input, remember(it) { prettyJson(it) }) }
        step.result?.takeIf { it.isNotBlank() }?.let { MonoBlock(if (step.subagent) S.chat.result else s.output, it) }
    }
}

@Composable
private fun HelperLive(step: Step, sessionId: String?, profile: String) {
    val api = LocalStore.current.api
    val scope = rememberCoroutineScope()
    val id = step.id.removePrefix("sa-")
    val running = step.status == StepStatus.Running
    if (step.sideTask) return // /btw and /background agents: no tail or stop; their answer arrives as its own card
    val tail by produceState<String?>(null, id, running) {
        if (sessionId == null) return@produceState
        do {
            runCatching { api.subagentTail(sessionId, profile, id) }.getOrNull()?.let { value = cleanTail(it) }
            if (running) delay(1500)
        } while (running)
    }
    tail?.takeIf { it.isNotBlank() }?.let { MonoBlock(S.chat.live, it.takeLast(6_000), follow = true) }
    if (running && sessionId != null) PillButton(S.chat.stopHelper, {
        scope.launch { runCatching { api.subagentInterrupt(sessionId, profile, id) } }
    }, Modifier.padding(top = 8.dp), kind = ButtonKind.Outline, height = 48.dp, haptic = Haptic.Reject)
}

/** The engine's "=== … live transcript ===" banner lines are not ours to show. */
internal fun cleanTail(t: String): String = t.lineSequence().filterNot { l -> l.trim().let { it.startsWith("===") && it.endsWith("===") } }.joinToString("\n").trimStart('\n')

/** Mono, LTR, max 240dp tall and scrollable (design §3.2 step rows). [follow] keeps the newest line in view. */
@Composable
fun MonoBlock(title: String, text: String, follow: Boolean = false) {
    val c = Theme.colors
    val scroll = rememberScrollState()
    if (follow) LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
    Txt(title, Theme.type.caps, c.secondary, Modifier.padding(top = 8.dp, bottom = 4.dp), maxLines = 1)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2)
                .verticalScroll(scroll).horizontalScroll(rememberScrollState()).padding(10.dp),
        ) { Txt(text, Theme.type.code.copy(fontSize = Theme.type.micro.fontSize, lineHeight = Theme.type.caption.lineHeight), c.text) }
    }
}

/** design §3.2 Plan card: counter, 6dp bar on the `smooth` spring, up to 5 steps from just before the first open one. */
@Composable
fun PlanCard(todos: List<Todo>, modifier: Modifier = Modifier) {
    val s = S.current
    val c = Theme.colors
    var open by remember { mutableStateOf(false) }
    val done = todos.count { it.status == "completed" }
    val total = todos.size
    val progress by animateFloatAsState(if (total == 0) 0f else done / total.toFloat(), Motion.smooth(), label = "plan")
    val firstOpen = todos.indexOfFirst { !it.done }.let { if (it < 0) total else it }
    val current = todos.firstOrNull { it.status == "in_progress" } ?: todos.getOrNull(firstOpen)
    val turn = chevronTurn(open)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.hairline, RoundedCornerShape(20.dp))
            .animateContentSize(Motion.smooth()),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).squish({ open = !open }, scale = .98f, haptic = Haptic.Tick, label = s.plan).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(s.plan, Theme.type.sub, c.text, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Txt(ltr("$done/$total"), Theme.type.caption, c.secondary, maxLines = 1)
                }
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(c.surface2)) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(CircleShape).background(if (done == total && total > 0) c.success else c.accentFill))
                }
                if (!open && current != null && done < total) Txt(current.content, Theme.type.caption, c.secondary, Modifier.padding(top = 6.dp), maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.expandMore, c.secondary, 22.dp, Modifier.graphicsLayer { rotationZ = turn })
        }
        if (open) {
            val start = (firstOpen - 1).coerceAtLeast(0)
            val window = todos.drop(start).take(5)
            for (t in window) key(t.id) { TodoRow(t) }
            val rest = total - start - window.size
            if (rest > 0) Txt(s.moreSteps(rest), Theme.type.micro, c.tertiary, Modifier.padding(start = 48.dp, bottom = 10.dp))
            else Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TodoRow(t: Todo) {
    val c = Theme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (t.status) {
                "completed" -> Icon(Icons.check, c.success, 18.dp)
                "in_progress" -> Spinner(14.dp, c.accent)
                "cancelled" -> Icon(Icons.close, c.tertiary, 18.dp)
                else -> Box(Modifier.size(12.dp).border(1.5.dp, c.tertiary, CircleShape))
            }
        }
        Spacer(Modifier.width(10.dp))
        val style = if (t.status == "cancelled") Theme.type.sub.copy(textDecoration = TextDecoration.LineThrough) else Theme.type.sub
        Txt(t.content, style, if (t.done) c.secondary else c.text, Modifier.weight(1f), maxLines = 2)
    }
}
