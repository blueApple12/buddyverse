package app.buddyverse.ui.more.skills

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.core.bool
import app.buddyverse.core.long
import app.buddyverse.core.nowMillis
import app.buddyverse.core.str
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Card
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.SkillsPageStrings
import app.buddyverse.i18n.Strings
import app.buddyverse.i18n.skillsPage
import app.buddyverse.ui.ago
import app.buddyverse.ui.parseIsoMillis
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** Shows [content] only after [ms], so a quick answer never flashes "loading" (design rule 3). */
@Composable
fun Delayed(ms: Long = 300, content: @Composable () -> Unit) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(ms); show = true }
    if (show) content()
}

@Composable
fun LoadingNote() = Txt(S.current.loading, Theme.type.sub, Theme.colors.secondary, Modifier.padding(horizontal = 20.dp, vertical = 16.dp))

@Composable
fun EmptyNote(text: String) = Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp), contentAlignment = Alignment.Center) {
    Txt(text, Theme.type.callout, Theme.colors.secondary)
}

@Composable
fun ErrorRow(text: String, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.error, Theme.colors.error)
        Spacer(Modifier.width(12.dp))
        Txt(text, Theme.type.sub, Theme.colors.errorText, Modifier.weight(1f), maxLines = 3)
        Spacer(Modifier.width(8.dp))
        PillButton(S.current.retry, onRetry, height = 48.dp, kind = ButtonKind.Quiet)
    }
}

@Composable
fun Tag(text: String, color: Color = Theme.colors.secondary) {
    Box(Modifier.clip(CircleShape).background(Theme.colors.surface2).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Txt(text, Theme.type.micro, color, maxLines = 1)
    }
}

/** Last lines of a running action, in mono (always LTR, design §9.5). */
@Composable
fun LogLines(lines: List<String>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Theme.colors.surface2).padding(12.dp)) {
        lines.forEach { Txt(it, Theme.type.code.copy(fontSize = 12.sp, lineHeight = 16.sp), Theme.colors.secondary, maxLines = 2) }
    }
}

fun provenanceLabel(p: String, t: SkillsPageStrings): String? = when (p) {
    "agent" -> t.yours
    "hub" -> t.fromHub
    "external" -> t.external
    else -> null
}

fun trustLabel(trust: String, t: SkillsPageStrings): String? = when (trust) {
    "builtin" -> t.official
    "trusted" -> t.trusted
    "community" -> t.community
    else -> null
}

/** "5m ago" / "now". */
fun agoText(epochSeconds: Double, s: Strings): String = ago(epochSeconds, nowMillis(), s).let { if (it == s.justNow) it else s.ago(it) }

/** 64dp row: name (+ whose tag), one-line description, and its one visible control, the switch. Long press = menu (design §3.7). */
@Composable
fun SkillRow(sk: Skill, onOpen: () -> Unit, onMenu: () -> Unit, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val tag = provenanceLabel(sk.provenance, S.skillsPage)
    PressRow(onOpen, modifier, minHeight = 64.dp, onLongPress = onMenu, label = sk.name) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(sk.name, Theme.type.headline, if (sk.enabled) c.text else c.secondary, Modifier.weight(1f, fill = false), maxLines = 1)
                if (tag != null) { Spacer(Modifier.width(8.dp)); Tag(tag) }
            }
            if (sk.description.isNotBlank()) Txt(sk.description, Theme.type.sub, c.secondary, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Toggle(sk.enabled, onToggle, sk.name)
    }
}

/** Hub row: trailing 48dp install, a check once installed, or the bot's buddy at work while it installs. */
@Composable
fun HubRow(h: HubSkill, installed: Boolean, busy: Boolean, spec: BuddySpec?, onOpen: () -> Unit, onInstall: () -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val t = S.skillsPage
    val trust = trustLabel(h.trust, t)
    PressRow(onOpen, modifier, minHeight = 64.dp, label = h.name) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(h.name, Theme.type.headline, modifier = Modifier.weight(1f, fill = false), maxLines = 1)
                if (trust != null) {
                    Spacer(Modifier.width(8.dp))
                    Tag(trust, when (h.trust) { "community" -> c.needsYouText; "trusted" -> c.success; else -> c.secondary })
                }
            }
            if (h.description.isNotBlank()) Txt(h.description, Theme.type.sub, c.secondary, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            when {
                busy && spec != null -> Buddy(remember(spec, h.identifier) { spec.copy(id = spec.id + "/" + h.identifier) }, Mood.Working, 36.dp, interactive = false)
                installed -> Icon(Icons.checkCircleFilled, c.success)
                else -> IconButton(Icons.add, t.install, onInstall, fill = c.surface2)
            }
        }
    }
}

/** Curator card: on/paused switch, its rhythm in words, and Tidy now (destructive, confirmed by the caller). */
@Composable
fun CuratorCard(cur: JsonObject, busy: Boolean, lines: List<String>, onPause: (Boolean) -> Unit, onRun: () -> Unit) {
    val t = S.skillsPage
    val s = S.current
    val c = Theme.colors
    val enabled = cur.bool("enabled") != false
    val paused = cur.bool("paused") == true
    Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(t.curator, Theme.type.headline, maxLines = 1)
                Txt(
                    when { !enabled -> t.curatorOff; paused -> t.curatorPaused; else -> t.curatorOn },
                    Theme.type.caption, if (enabled && !paused) c.success else c.secondary, maxLines = 1,
                )
            }
            Toggle(enabled && !paused, { on -> onPause(!on) }, t.curator, enabled = enabled)
        }
        Spacer(Modifier.height(12.dp))
        cur.long("interval_hours")?.let { h -> Fact(Icons.schedule, t.every(if (h > 0 && h % 24 == 0L) s.days((h / 24).toInt()) else t.hours(h))) }
        val last = cur.str("last_run_at")?.let { parseIsoMillis(it)?.div(1000.0) ?: it.toDoubleOrNull() }
        Fact(Icons.history, if (last != null && last > 0) t.lastRun(agoText(last, s)) else t.neverRan)
        cur.long("stale_after_days")?.let { Fact(Icons.timer, t.staleAfter(it)) }
        cur.long("archive_after_days")?.let { Fact(Icons.archive, t.archiveAfter(it)) }
        cur.long("min_idle_hours")?.let { Fact(Icons.pause, t.idleFirst(it)) }
        Spacer(Modifier.height(16.dp))
        PillButton(if (busy) t.tidying else t.tidyNow, onRun, Modifier.fillMaxWidth(), kind = ButtonKind.Outline, icon = Icons.autoAwesome, enabled = !busy)
        if (lines.isNotEmpty()) { Spacer(Modifier.height(12.dp)); LogLines(lines) }
    }
}

@Composable
private fun Fact(icon: ImageVector, text: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, Theme.colors.tertiary, 20.dp)
        Spacer(Modifier.width(10.dp))
        Txt(text, Theme.type.sub, Theme.colors.secondary)
    }
}
