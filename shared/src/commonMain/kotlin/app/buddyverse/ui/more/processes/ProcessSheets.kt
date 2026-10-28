package app.buddyverse.ui.more.processes

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.Bot
import app.buddyverse.core.Hermes
import app.buddyverse.core.nowMillis
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Chip
import app.buddyverse.design.Dot
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.ProcessesPageStrings
import app.buddyverse.i18n.S
import app.buddyverse.i18n.Strings
import app.buddyverse.i18n.processesPage
import app.buddyverse.ui.ago
import app.buddyverse.ui.ltr
import kotlinx.coroutines.delay

private val TERM_FONT = 12.sp
private val TERM_LINE = 16.sp

/** The owning bot's look; a profile the roster doesn't know gets a stable look from its name. */
fun specOf(p: Proc, bot: Bot?): BuddySpec =
    bot?.spec ?: BuddySpec(p.profile, p.profile, BuddyShape.parse(null, p.profile), BuddyColors.forName(p.profile))

fun moodOf(p: Proc, stopping: Boolean): Mood = when {
    stopping -> Mood.Thinking
    p.running -> Mood.Working
    p.failed -> Mood.Error
    else -> Mood.Idle
}

/** A cue only plays while fresh: a buddy composed later must not replay an old one. */
fun fresh(vararg cues: BuddyCue?): BuddyCue? = cues.filterNotNull().maxByOrNull { it.serial }?.takeIf { nowMillis() - it.serial < 2_000 }

/** "Main chat" for the bot's forever chat, else the chat's own title. */
fun chatName(p: Proc, bot: Bot?, s: Strings): String =
    if (p.chat == null || p.chat == Hermes.BOT_CHAT || p.sessionId == bot?.botChatId) s.mainChat else p.chat

/** Second line of a row and the sheet: who, where, and how it is doing. */
fun metaLine(p: Proc, bot: Bot?, s: Strings, t: ProcessesPageStrings, nowMs: Long): String = listOfNotNull(
    bot?.name ?: p.profile,
    chatName(p, bot, s),
    when {
        p.running -> p.uptime?.let { t.up(ltr(dur(it, s))) }
        p.failed -> t.failedExit(p.exitCode ?: -1L)
        p.exitCode == 0L -> t.done
        else -> t.ended
    },
    if (p.running) null else p.exitedAt?.let { ago(it, nowMs, s) }?.takeIf { it.isNotEmpty() },
).joinToString(" · ")

/** The bot buddy with a status dot that breathes while the process runs. */
@Composable
fun ProcBuddy(p: Proc, bot: Bot?, stopping: Boolean, cue: BuddyCue?, size: Dp) {
    val c = Theme.colors
    val color by animateColorAsState(
        when {
            stopping -> c.needsYou
            p.running -> c.working
            p.failed -> c.error
            else -> c.success
        },
        Motion.fade(),
    )
    val pulse = if (p.running) rememberInfiniteTransition().animateFloat(1f, .35f, infiniteRepeatable(tween(700), RepeatMode.Reverse)) else null
    Box(Modifier.size(size)) {
        Buddy(specOf(p, bot), moodOf(p, stopping), size, cue = cue, interactive = false)
        Box(Modifier.align(Alignment.BottomEnd).size(16.dp).clip(CircleShape).background(c.surface), contentAlignment = Alignment.Center) {
            Dot(color, 10.dp, Modifier.graphicsLayer { alpha = pulse?.value ?: 1f })
        }
    }
}

/**
 * Terminal output: always left-to-right and monospace (design §9.5), follows the end while the reader is at the bottom.
 */
@Composable
fun TermView(buf: TermBuffer, modifier: Modifier = Modifier, empty: String? = null) {
    val c = Theme.colors
    val rev = buf.rev
    val lines = remember(buf, rev) { buf.snapshot() }
    val state = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(state.isScrollInProgress) { if (!state.isScrollInProgress) follow = !state.canScrollForward }
    LaunchedEffect(rev) { if (follow && lines.isNotEmpty()) state.scrollToItem(lines.lastIndex) }
    val style = Theme.type.code.copy(fontSize = TERM_FONT, lineHeight = TERM_LINE)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier.clip(RoundedCornerShape(18.dp)).background(c.surface2)) {
            if (empty != null && lines.all { it.isBlank() }) {
                Txt(empty, Theme.type.sub.copy(textAlign = TextAlign.Center), c.secondary, Modifier.align(Alignment.Center).padding(24.dp))
            }
            LazyColumn(Modifier.fillMaxSize(), state, contentPadding = PaddingValues(12.dp)) {
                items(lines.size) { i -> Txt(lines[i].ifEmpty { " " }, style, c.text, Modifier.fillMaxWidth()) }
            }
        }
    }
}

/** Stop that asks once: the first tap turns it into "Really stop?", the second stops (3 s to decide). */
@Composable
fun StopControl(stopping: Boolean, onStop: () -> Unit, wide: Boolean = false, modifier: Modifier = Modifier) {
    val t = S.processesPage
    val c = Theme.colors
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3_000); armed = false } }
    val confirm = { armed = false; onStop() }
    Box(modifier.animateContentSize(Motion.smooth()), contentAlignment = Alignment.Center) {
        when {
            stopping -> if (wide) PillButton(t.stopping, {}, Modifier.fillMaxWidth(), ButtonKind.Quiet, enabled = false)
                else IconButton(Icons.timer, t.stopping, {}, tint = c.needsYouText, enabled = false)
            armed -> PillButton(t.reallyStop, confirm, if (wide) Modifier.fillMaxWidth() else Modifier, ButtonKind.Danger, height = if (wide) 56.dp else 48.dp, haptic = Haptic.Reject)
            wide -> PillButton(t.stop, { armed = true }, Modifier.fillMaxWidth(), ButtonKind.Outline, icon = Icons.stopFilled)
            else -> IconButton(Icons.stopFilled, t.stop, { armed = true }, tint = c.error)
        }
    }
}

/** A process up close: who runs it, its live output, Stop (and Stop all in that chat), Open chat, Copy. */
@Composable
fun ProcessSheet(m: ProcessesModel, id: String?, bots: List<Bot>, onDismiss: () -> Unit, onOpenChat: (Proc) -> Unit, onCopy: (String) -> Unit) {
    val t = S.processesPage
    val s = S.current
    val c = Theme.colors
    val live = id?.let { m.proc(it) }
    val kept = remember { mutableStateOf<Proc?>(null) }
    SideEffect { if (live != null) kept.value = live }
    val p = live ?: kept.value
    Sheet(visible = live != null, onDismiss = onDismiss, title = p?.title, heightFraction = .9f) {
        if (p == null) return@Sheet
        val bot = bots.firstOrNull { it.profile == p.profile }
        val stopping = p.id in m.stopping
        var hop by remember { mutableStateOf<BuddyCue?>(null) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Buddy(specOf(p, bot), moodOf(p, stopping), 64.dp, cue = fresh(m.cues[p.id], hop), onTap = { hop = BuddyCue(nowMillis(), OneShot.Giggle) })
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(metaLine(p, bot, s, t, nowMillis()), Theme.type.callout, if (p.failed) c.errorText else c.text, maxLines = 2)
                val facts = listOfNotNull(p.pid?.let { t.pid(it) }, t.tellsWhenDone.takeIf { p.notify }, t.watchHit.takeIf { p.watchHit }, p.reason)
                if (facts.isNotEmpty()) Txt(facts.joinToString(" · "), Theme.type.caption, c.secondary, maxLines = 2)
                p.cwd?.let { Txt(it, Theme.type.code.copy(fontSize = TERM_FONT), c.secondary, maxLines = 1) }
            }
        }
        TermView(m.buffer(p.id), Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), empty = t.noOutput)
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.running) {
                StopControl(stopping, { m.kill(p) }, wide = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
            }
            PillButton(t.openChat, { onOpenChat(p) }, Modifier.weight(1f), if (p.running) ButtonKind.Quiet else ButtonKind.Primary)
            Spacer(Modifier.width(4.dp))
            IconButton(Icons.contentCopy, t.copyOutput, { onCopy(m.buffer(p.id).plain()) })
        }
        val others = m.runningIn(p.sessionId)
        if (p.running && others > 1) {
            PillButton(t.stopAll(others), { m.stopAll(p) }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), ButtonKind.Quiet,
                height = 48.dp, haptic = Haptic.Reject, enabled = !stopping)
        }
    }
}

/** A shell on the PC (the Webapp's terminal rail, natively). Closing the sheet ends the shell. */
@Composable
fun ShellSheet(shell: HostShell, visible: Boolean, profile: String, buddy: BuddySpec?, onDismiss: () -> Unit) {
    val t = S.processesPage
    val c = Theme.colors
    val scope = rememberCoroutineScope()
    Sheet(visible = visible, onDismiss = onDismiss, title = t.shell, heightFraction = .92f) {
        val st = shell.state
        val rev = shell.buf.rev
        var talkAt by remember { mutableLongStateOf(0L) }
        LaunchedEffect(rev) { val now = nowMillis(); if (st is ShellState.Open && now - talkAt > 600) talkAt = now }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (buddy != null) {
                Buddy(buddy, shellMood(st), 40.dp, talkAt = talkAt, talkChars = 24)
                Spacer(Modifier.width(12.dp))
            }
            val (text, color) = shellLine(st, t, c.secondary, c.errorText)
            Txt(text, if (st is ShellState.Open) Theme.type.code.copy(fontSize = TERM_FONT) else Theme.type.sub, color, Modifier.weight(1f), maxLines = 1)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            val density = LocalDensity.current
            val charW = with(density) { (TERM_FONT * .6f).toDp() }
            val lineH = with(density) { TERM_LINE.toDp() }
            val cols = ((maxWidth - 24.dp) / charW).toInt().coerceIn(20, 400)
            val rows = ((maxHeight - 24.dp) / lineH).toInt().coerceIn(5, 200)
            LaunchedEffect(Unit) { if (shell.state is ShellState.Idle) shell.open(scope, profile, cols, rows) }
            LaunchedEffect(cols, rows) { shell.resize(cols, rows) }
            TermView(shell.buf, Modifier.fillMaxSize())
        }
        if (st is ShellState.Closed) {
            PillButton(t.openAgain, { shell.reopen(scope) }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), icon = Icons.refresh)
        } else {
            val open = st is ShellState.Open
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(t.ctrlC to "\u0003", t.tab to "\t", t.up1 to "\u001b[A", t.down1 to "\u001b[B", t.esc to "\u001b").forEach { (label, keys) ->
                    Chip(label, false, { if (open) shell.send(keys) })
                }
            }
            var line by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(line, { line = it }, t.typeCommand, Modifier.weight(1f), mono = true)
                Spacer(Modifier.width(8.dp))
                IconButton(Icons.keyboardReturn, t.run, { shell.send(line + "\r"); line = "" }, tint = c.onAccent, fill = c.accentFill, enabled = open)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

private fun shellMood(st: ShellState): Mood = when (st) {
    ShellState.Idle, ShellState.Opening -> Mood.Thinking
    is ShellState.Open -> Mood.Listening
    is ShellState.Closed -> if (st.end == ShellEnd.Exited) Mood.Sleeping else Mood.Error
}

private fun shellLine(st: ShellState, t: ProcessesPageStrings, quiet: Color, bad: Color): Pair<String, Color> = when (st) {
    ShellState.Idle, ShellState.Opening -> t.opening to quiet
    is ShellState.Open -> (st.cwd ?: st.shell ?: "") to quiet
    is ShellState.Closed -> when (st.end) {
        ShellEnd.Exited -> t.shellExited to quiet
        ShellEnd.Busy -> t.shellBusy to bad
        ShellEnd.Denied -> t.shellDenied to bad
        ShellEnd.TakenOver -> t.shellTakenOver to bad
        ShellEnd.Failed -> t.shellFailed to bad
    }
}
