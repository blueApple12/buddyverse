package app.buddyverse.ui.more.status

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.core.api.PcComponent
import app.buddyverse.core.api.PcHealth
import app.buddyverse.core.api.PcReceipt
import app.buddyverse.core.api.PcUpdate
import app.buddyverse.core.nowMillis
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Card
import app.buddyverse.design.Chip
import app.buddyverse.design.Dot
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.StatusPageStrings
import app.buddyverse.i18n.statusPage
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.ago
import app.buddyverse.ui.ltr
import app.buddyverse.ui.parseIsoMillis
import kotlinx.coroutines.delay
import app.buddyverse.i18n.Strings

private val logStyleDir = TextDirection.Ltr

/** The hero line of the engine, in plain words. */
fun PcHealth?.title(t: StatusPageStrings, reachable: Boolean): String = when {
    !reachable -> t.unreachable
    this == null -> t.engineRunning
    state == "fatal" || state == "startup_failed" -> t.engineFailed
    !running || state == "stopped" -> t.engineStopped
    state == "starting" -> t.engineStarting
    state == "draining" -> t.engineDraining
    overall != null && overall != "ok" -> t.engineTrouble
    busy || activeAgents > 0 -> t.engineBusy
    else -> t.engineRunning
}

private const val LOW_MEMORY_MB = 2_048.0
private val CALM_PRESSURE = setOf("ok", "unknown")

/** Things worth a look, worst first. Empty = all good. */
fun PcHealth.warnings(t: StatusPageStrings, s: Strings): List<String> = buildList {
    if (components["storage"]?.reason == "corrupt") add(t.storageCorrupt)
    if (memory?.lastBootOom == true) add(t.oomBoot) else if (memory?.lastBootUnclean == true) add(t.uncleanBoot)
    heartbeatStaleS?.takeIf { it > 0 }?.let { add(t.heartbeatStale(ltr(span(it.toLong(), s)))) }
    if (!running) exitReason?.takeIf { it.isNotBlank() }?.let { add(t.exitReason(it)) }
    // Only real shortage counts: under 2 GB free. The engine says pressure "unknown" when it can't read the PC (Windows).
    if ((memory?.availableMb ?: Double.MAX_VALUE) < LOW_MEMORY_MB) add(t.memoryPressure)
    if (disk?.pressure != null && disk.pressure !in CALM_PRESSURE) add(t.diskPressure)
}

private fun statusTone(status: String?, c: app.buddyverse.design.Palette): Color = when (status) {
    "ok", "connected", "running" -> c.success
    "degraded", "connecting", "starting", "draining" -> c.needsYou
    "error", "failed", "fatal", "startup_failed" -> c.error
    else -> c.tertiary
}

/** The PC as a living buddy: state, uptime, version, who's working; one primary action (start, or restart with a confirm). */
@Composable
fun HeroCard(
    spec: BuddySpec, h: PcHealth?, reachable: Boolean, mood: Mood, cue: BuddyCue?, talkAt: Long,
    restart: ActionRun?, start: ActionRun?, onStart: () -> Unit, onRestart: () -> Unit, onBuddy: () -> Unit,
) {
    val t = S.statusPage
    val c = Theme.colors
    val title = h.title(t, reachable)
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Buddy(spec, mood, 88.dp, cue = cue?.takeIf { nowMillis() - it.serial < 2_000 }, talkAt = talkAt, talkChars = title.length, onTap = onBuddy)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Txt(title, Theme.type.title2, maxLines = 2)
                if (h != null && reachable) {
                    val up = h.uptimeSeconds(nowMillis())?.takeIf { h.running }?.let { t.upFor(ltr(span(it, S.current))) }
                    val ver = h.version?.let { t.version(ltr(it)) }
                    listOfNotNull(up, ver).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Txt(it, Theme.type.sub, c.secondary, maxLines = 1) }
                    val busy = listOfNotNull(
                        h.activeAgents.takeIf { it > 0 }?.let(t.working),
                        h.activeSessions.takeIf { it > 0 }?.let(t.liveChats),
                    ).joinToString(" · ")
                    if (busy.isNotEmpty()) Txt(busy, Theme.type.sub, c.working, maxLines = 1)
                }
            }
        }
        if (h != null && reachable) {
            Spacer(Modifier.height(16.dp))
            val restarting = restart?.running == true
            val starting = start?.running == true
            if (!h.running || h.state == "stopped" || h.state == "fatal" || h.state == "startup_failed") {
                PillButton(if (starting) t.starting else t.start, onStart, Modifier.fillMaxWidth(), icon = Icons.powerSettingsNew, enabled = !starting && !restarting)
            } else {
                PillButton(if (restarting) t.restarting else t.restart, onRestart, Modifier.fillMaxWidth(), kind = ButtonKind.Outline,
                    icon = Icons.refresh, enabled = !restarting && !starting)
            }
            (restart?.error ?: start?.error)?.let { Txt(it, Theme.type.sub, c.errorText, Modifier.padding(top = 8.dp), maxLines = 3) }
        }
    }
}

/** Amber card listing what needs a look; the old settings file gets its own fix button. */
@Composable
fun AttentionCard(lines: List<String>, configBehind: Boolean, migrating: Boolean, onMigrate: () -> Unit) {
    val t = S.statusPage
    val c = Theme.colors
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.warning, c.needsYou, 24.dp)
            Spacer(Modifier.width(10.dp))
            Txt(t.attention, Theme.type.headline, c.needsYouText, maxLines = 1)
        }
        (lines + listOfNotNull(t.configBehind.takeIf { configBehind })).forEach { l ->
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                Dot(c.needsYou, 8.dp, Modifier.padding(top = 7.dp))
                Spacer(Modifier.width(10.dp))
                Txt(l, Theme.type.sub, c.text)
            }
        }
        if (configBehind) {
            Spacer(Modifier.height(14.dp))
            PillButton(if (migrating) t.runningNow else t.migrate, onMigrate, Modifier.fillMaxWidth(), kind = ButtonKind.Outline,
                icon = Icons.tune, enabled = !migrating)
        }
    }
}

/** Updates: the check's answer, what's new, and the one button that fits (update, or copy the command for other installs). */
@Composable
fun UpdatesCard(
    u: PcUpdate?, checking: Boolean, error: String?, receipt: PcReceipt?, run: ActionRun?,
    onCheck: () -> Unit, onUpdate: () -> Unit, onOpenRun: () -> Unit,
) {
    val t = S.statusPage
    val s = S.current
    val c = Theme.colors
    val toasts = LocalToasts.current
    @Suppress("DEPRECATION") val clip = LocalClipboardManager.current
    val updating = run?.running == true
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val state = when {
            updating -> t.updating
            u == null && checking -> t.checking
            u == null && error != null -> t.updateUnknown
            u == null -> t.notChecked
            u.behind == 0 && !u.available -> t.upToDate
            (u.behind ?: 0) > 0 -> t.behind(u.behind ?: 0)
            u.available -> t.updateReady
            u.behind == -1 -> t.updateUnknown
            else -> t.upToDate
        }
        val tone = when {
            updating -> c.working
            u?.available == true -> c.needsYou
            u != null && u.behind == 0 -> c.success
            else -> c.tertiary
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(tone, 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(state, Theme.type.headline, maxLines = 1)
                u?.currentVersion?.let { Txt(t.version(ltr(it)), Theme.type.sub, c.secondary, maxLines = 1) }
            }
        }
        if (updating) {
            Spacer(Modifier.height(10.dp))
            Txt(run?.lines?.lastOrNull().orEmpty(), Theme.type.code, c.secondary,
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).squish(onOpenRun, scale = .98f, label = t.logs).padding(12.dp), maxLines = 2)
        }
        if (u != null && u.commits.isNotEmpty() && !updating) {
            Spacer(Modifier.height(12.dp))
            Txt(t.changes, Theme.type.caption, c.secondary, maxLines = 1)
            u.commits.take(5).forEach { cm ->
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                    Txt(cm.sha.orEmpty().take(7), Theme.type.code, c.tertiary, Modifier.width(64.dp), maxLines = 1)
                    Txt(cm.summary.orEmpty(), Theme.type.sub, c.text, Modifier.weight(1f), maxLines = 2)
                }
            }
        }
        receipt?.let { r ->
            Spacer(Modifier.height(12.dp))
            val whenText = r.finishedAt?.let(::parseIsoMillis)?.let { ago(it / 1000.0, nowMillis(), s) }?.let { if (it == s.justNow) it else s.ago(it) }
            Txt(listOfNotNull("${t.lastUpdate}: ${t.outcome(r.outcome.orEmpty())}", whenText).joinToString(" · "), Theme.type.sub, c.secondary, maxLines = 1)
            if (r.followups.isNotEmpty()) Txt("${t.stillToDo}: " + r.followups.joinToString("; ") { listOfNotNull(it.step, it.reason?.let { x -> "($x)" }).joinToString(" ") },
                Theme.type.sub, c.needsYouText, maxLines = 3)
            r.userAction?.let { a -> Txt("${t.youNeedTo}: " + listOfNotNull(a.step, a.reason).joinToString(": "), Theme.type.sub, c.needsYouText, maxLines = 3) }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(if (checking) t.checking else t.checkNow, onCheck, Modifier.weight(1f), kind = ButtonKind.Quiet, icon = Icons.refresh,
                enabled = !checking && !updating)
            if (u?.available == true && u.canApply) {
                PillButton(t.updateNow, onUpdate, Modifier.weight(1f), icon = Icons.arrowUpward, enabled = !updating)
            } else if (u?.available == true && !u.command.isNullOrBlank()) {
                PillButton(t.copyCommand, {
                    clip.setText(AnnotatedString(u.command.orEmpty())); toasts.show(ToastData(t.commandCopied))
                }, Modifier.weight(1f), kind = ButtonKind.Outline, icon = Icons.contentCopy)
            }
        }
        if (run?.error != null && !updating) Txt(run.error.orEmpty(), Theme.type.sub, c.errorText, Modifier.padding(top = 8.dp), maxLines = 3)
    }
}

/** A part of the engine (or a messaging app) with its state dot and one detail line. */
@Composable
fun PartRow(name: String, status: String?, detail: String?) {
    val c = Theme.colors
    val tone by animateColorAsState(statusTone(status, c), Motion.fade())
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(tone, 12.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(name, Theme.type.headline, maxLines = 1)
            if (!detail.isNullOrBlank()) Txt(detail, Theme.type.sub, c.secondary, maxLines = 2)
        }
    }
}

/** What a component's row says under its name. */
fun PcComponent.detail(key: String, t: StatusPageStrings): String = listOfNotNull(
    if (key == "platforms" && configured != null) t.connectedOf(connected ?: 0, configured) else null,
    recentErrors?.takeIf { it > 0 }?.let(t.recentErrors),
    reason?.takeIf { it.isNotBlank() },
    (status ?: state)?.let { if (it == "ok" || it == "running") t.ok else if (it == "degraded") t.degraded else it },
).distinct().joinToString(" · ")

/** A labelled meter: amber over 75%, red over 90%. */
@Composable
fun Meter(label: String, value: String, fraction: Float?) {
    val c = Theme.colors
    val f by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), Motion.smooth())
    val tone = when { (fraction ?: 0f) > .9f -> c.error; (fraction ?: 0f) > .75f -> c.needsYou; else -> c.working }
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(label, Theme.type.callout, modifier = Modifier.weight(1f), maxLines = 1)
            Txt(ltr(value), Theme.type.callout, c.secondary, maxLines = 1)
        }
        if (fraction != null) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.surface2)) {
                if (f > 0f) Box(Modifier.fillMaxWidth(maxOf(f, .02f)).height(8.dp).clip(CircleShape).background(tone))
            }
        }
    }
}

/** A tappable page row: icon, name, one detail line, chevron (mirrored in RTL). */
@Composable
fun NavRow(icon: ImageVector, name: String, detail: String?, onClick: () -> Unit, tint: Color = Theme.colors.text) {
    val c = Theme.colors
    PressRow(onClick, minHeight = 64.dp, label = name) {
        Icon(icon, tint, 26.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Txt(name, Theme.type.headline, maxLines = 1)
            if (!detail.isNullOrBlank()) Txt(detail, Theme.type.sub, c.secondary, maxLines = 1)
        }
        Icon(Icons.chevronRight, c.tertiary, 24.dp, mirror = true)
    }
}

/** A yes/no question before something that interrupts bots or rewrites a file. */
@Composable
fun ConfirmSheet(visible: Boolean, title: String, lines: List<String>, confirm: String, danger: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val s = S.current
    val c = Theme.colors
    Sheet(visible, onDismiss, title = title) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            lines.forEach { Txt(it, Theme.type.body, c.text, Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(confirm, { onDismiss(); onConfirm() }, Modifier.weight(1f), kind = if (danger) ButtonKind.Danger else ButtonKind.Primary,
                    haptic = if (danger) Haptic.Reject else Haptic.Press)
                PillButton(s.cancel, onDismiss, Modifier.weight(1f), kind = ButtonKind.Quiet)
            }
        }
    }
}

/** A background action's output, live while it runs; Run/Run again starts it (never on open). */
@Composable
fun ActionSheet(run: ActionRun?, title: String, canRun: Boolean, onRun: () -> Unit, onDismiss: () -> Unit) {
    val t = S.statusPage
    val c = Theme.colors
    val toasts = LocalToasts.current
    @Suppress("DEPRECATION") val clip = LocalClipboardManager.current
    var last by remember { mutableStateOf(run) }
    if (run != null) last = run
    Sheet(run != null, onDismiss, title = title, heightFraction = .86f) {
        val r = last ?: return@Sheet
        val state = when {
            r.running -> t.runningNow
            r.error != null -> r.error.orEmpty()
            r.receipt != null -> "${t.lastUpdate}: ${t.outcome(r.receipt?.outcome.orEmpty())}"
            r.exitCode != null && r.exitCode != 0 -> t.failedCode(r.exitCode ?: 0)
            r.exitCode == 0 -> t.finished
            else -> null
        }
        val tone = when { r.running -> c.working; r.error != null || (r.exitCode ?: 0) != 0 -> c.errorText; else -> c.secondary }
        if (state != null) Txt(state, Theme.type.sub, tone, Modifier.padding(horizontal = 20.dp), maxLines = 3)
        val list = rememberLazyListState()
        LaunchedEffect(r.lines.size) { if (r.lines.isNotEmpty()) list.animateScrollToItem(r.lines.lastIndex) }
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 10.dp).clip(RoundedCornerShape(16.dp)).background(c.surface2)) {
            if (r.lines.isEmpty()) Txt(if (r.running) t.runningNow else t.noOutput, Theme.type.sub, c.secondary, Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                itemsIndexed(r.lines) { _, l -> LogLine(l) }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canRun) PillButton(if (r.running) t.runningNow else if (r.finished) t.runAgain else t.run, onRun, Modifier.weight(1f),
                icon = Icons.playArrow, enabled = !r.running)
            PillButton(t.copyAll, { clip.setText(AnnotatedString(r.lines.joinToString("\n"))); toasts.show(ToastData(t.copied)) },
                Modifier.weight(1f), kind = ButtonKind.Quiet, icon = Icons.contentCopy, enabled = r.lines.isNotEmpty())
        }
    }
}

/** One log line: monospace, always LTR, tinted by level. */
@Composable
fun LogLine(line: String, onLong: (() -> Unit)? = null) {
    val c = Theme.colors
    val color = when (lineLevel(line)) { 2 -> c.errorText; 1 -> c.needsYouText; else -> c.text }
    val m = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    Txt(line, Theme.type.code.copy(textDirection = logStyleDir), color, if (onLong != null) m.squish({}, scale = .99f, haptic = null, onLongPress = onLong) else m)
}

/** Logs: file and level chips, a search, Follow (re-reads every 3 s) and copy; long-press a line to copy it. */
@Composable
fun LogsSheet(visible: Boolean, m: LogsModel, onDismiss: () -> Unit) {
    val t = S.statusPage
    val c = Theme.colors
    val toasts = LocalToasts.current
    @Suppress("DEPRECATION") val clip = LocalClipboardManager.current
    LaunchedEffect(visible, m.file, m.level, m.search, m.follow) {
        if (!visible) return@LaunchedEffect
        if (m.search.isNotEmpty()) delay(300)
        m.load()
        while (m.follow) { delay(3_000); m.load() }
    }
    val list = rememberLazyListState()
    LaunchedEffect(m.lines.size, m.follow) { if (m.follow && m.lines.isNotEmpty()) list.scrollToItem(m.lines.lastIndex) }
    Sheet(visible, onDismiss, title = t.logs, heightFraction = .92f) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogsModel.LOG_FILES.forEach { f -> Chip(t.logFile(f), m.file == f, { m.file = f }) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogsModel.LEVELS.forEach { l ->
                val label = when (l) { null -> t.levelAll; "INFO" -> t.levelInfo; "WARNING" -> t.levelWarning; else -> t.levelError }
                Chip(label, m.level == l, { m.level = l })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(m.search, { m.search = it }, t.searchLogs, Modifier.weight(1f))
            Spacer(Modifier.width(4.dp))
            Toggle(m.follow, { m.follow = it }, t.follow)
            IconButton(Icons.contentCopy, t.copyAll, { clip.setText(AnnotatedString(m.lines.joinToString("\n"))); toasts.show(ToastData(t.copied)) },
                enabled = m.lines.isNotEmpty())
        }
        AnimatedVisibility(m.error != null, enter = fadeIn(Motion.fade()) + expandVertically(Motion.smooth()), exit = fadeOut(Motion.fade()) + shrinkVertically(Motion.smooth())) {
            Txt(m.error.orEmpty(), Theme.type.sub, c.errorText, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), maxLines = 3)
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp)).background(c.surface2)) {
            if (m.lines.isEmpty() && !m.loading && m.error == null) Txt(t.noLines, Theme.type.sub, c.secondary, Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                itemsIndexed(m.lines) { _, l ->
                    LogLine(l) { clip.setText(AnnotatedString(l)); toasts.show(ToastData(t.copied)) }
                }
            }
        }
    }
}
