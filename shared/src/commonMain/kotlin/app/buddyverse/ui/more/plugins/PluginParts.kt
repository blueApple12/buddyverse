package app.buddyverse.ui.more.plugins

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.core.api.CatalogPlugin
import app.buddyverse.core.api.PluginRow
import app.buddyverse.core.api.SuggestedPlugin
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Radius
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.PluginsPageStrings
import app.buddyverse.i18n.S
import app.buddyverse.i18n.pluginsPage
import app.buddyverse.ui.ltr
import kotlinx.coroutines.delay

/** [cue] minus the one already showing when this buddy appeared: a buddy that comes on screen must not replay an old one-shot. */
@Composable
fun freshCue(cue: BuddyCue?, vararg keys: Any?): BuddyCue? {
    val seen = remember(*keys) { cue?.serial }
    return cue?.takeIf { it.serial != seen }
}

/** Shows [content] only after [ms], so a quick answer never flashes "loading" (design rule 3). */
@Composable
fun Later(ms: Long = 300, content: @Composable () -> Unit) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(ms); show = true }
    if (show) content()
}

@Composable
fun Note(text: String, color: Color = Theme.colors.secondary) =
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp), contentAlignment = Alignment.Center) { Txt(text, Theme.type.callout, color) }

@Composable
fun FailRow(text: String, onRetry: () -> Unit) {
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

fun tierLabel(tier: String?, t: PluginsPageStrings): String? = when (tier) {
    "official" -> t.official
    "verified" -> t.verified
    "community" -> t.community
    else -> null
}

@Composable
fun tierColor(tier: String?): Color = when (tier) {
    "official", "verified" -> Theme.colors.success
    "community" -> Theme.colors.needsYouText
    else -> Theme.colors.secondary
}

/** Where an installed plugin came from, in words. */
fun originLabel(r: PluginRow, t: PluginsPageStrings): String =
    tierLabel(r.tier, t) ?: when {
        r.source == "bundled" -> t.builtIn
        r.catalogName != null -> t.fromCatalog
        else -> t.fromLink
    }

/** A puzzle piece in a soft tile tinted from the plugin's name, so rows are told apart at a glance. */
@Composable
fun PluginGlyph(name: String, dim: Boolean = false, icon: ImageVector = Icons.extension) {
    val tint = remember(name) { BuddyColors.forName(name) }.let { if (dim) Theme.colors.tertiary else it }
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
        Icon(icon, tint, 22.dp)
    }
}

/**
 * Whose plugins: one buddy per bot (each bot has its own plugins folder and switches). The chosen one is bigger and lives the page's
 * mood (thinking while loading, working while an install runs, hurt after a failure) and plays its one-shots; the others idle,
 * breathing and looking around.
 */
@Composable
fun CrewPicker(crew: List<Pair<String, BuddySpec>>, selected: String, mood: Mood, cue: BuddyCue?, onSelect: (String) -> Unit) {
    val fb = LocalFeedback.current
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(crew, key = { it.first }) { (profile, spec) ->
            val sel = profile == selected
            val fresh = freshCue(cue, sel)
            val size by animateDpAsState(if (sel) 60.dp else 44.dp, Motion.bouncy())
            Column(
                Modifier.width(84.dp).squish({ onSelect(profile) }, haptic = Haptic.Tick, enabled = !sel, label = spec.name)
                    .clip(RoundedCornerShape(Radius.row.dp)).background(if (sel) Theme.colors.surface2 else Color.Transparent).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.height(64.dp), contentAlignment = Alignment.BottomCenter) {
                    // The chosen buddy takes its own touches (squash, giggle on long press); the others are buttons that pick them.
                    Buddy(spec, if (sel) mood else Mood.Idle, size, cue = if (sel) fresh else null, interactive = sel,
                        onTap = { fb.haptic(Haptic.Press) })
                }
                Spacer(Modifier.height(4.dp))
                Txt(spec.name, Theme.type.caption, if (sel) Theme.colors.accent else Theme.colors.secondary, maxLines = 1)
            }
        }
    }
}

/** 64dp+ row: glyph, name with its origin, one status line, and its one visible control, the switch. Long press = menu (design §3.7). */
@Composable
fun PluginItem(r: PluginRow, updating: Boolean, onOpen: () -> Unit, onMenu: () -> Unit, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val t = S.pluginsPage
    val title = remember(r.name) { pluginTitle(r.name) }
    val trouble = r.servers.firstOrNull { it.state != "connected" }
    val (line, lineColor) = when {
        updating -> t.updating to c.working
        r.updateAvailable -> (r.updateTo?.let { "${t.updateReady} · ${ltr(it)}" } ?: t.updateReady) to c.accent
        r.on && trouble != null -> "${trouble.name}: ${brandFree(trouble.sentence).ifBlank { t.serverState(trouble.state) }}" to c.needsYouText
        else -> remember(r.description) { brandFree(r.description).lineSequence().firstOrNull().orEmpty() } to c.secondary
    }
    PressRow(onOpen, modifier, minHeight = 72.dp, onLongPress = onMenu, label = title) {
        PluginGlyph(r.name, dim = !r.on)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(title, Theme.type.headline, if (r.on) c.text else c.secondary, Modifier.weight(1f, fill = false), maxLines = 1)
                tierLabel(r.tier, t)?.let { Spacer(Modifier.width(8.dp)); Tag(it, tierColor(r.tier)) }
            }
            if (line.isNotBlank()) Txt(line, Theme.type.sub, lineColor, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        // Stays live while a flip is in flight (the model drops a second flip): no dimmed knob flashing on every tap.
        Toggle(r.on, onToggle, title, enabled = r.toggleable && !updating)
    }
}

/** Catalog row: trailing 48dp install, a check once installed, or the bot's buddy at work while it installs. */
@Composable
fun CatalogItem(c: CatalogPlugin, installed: Boolean, busy: Boolean, worker: BuddySpec?, onOpen: () -> Unit, onInstall: () -> Unit, modifier: Modifier = Modifier) {
    val col = Theme.colors
    val t = S.pluginsPage
    val title = remember(c.name) { pluginTitle(c.name) }
    PressRow(onOpen, modifier, minHeight = 72.dp, label = title) {
        PluginGlyph(c.name)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(title, Theme.type.headline, modifier = Modifier.weight(1f, fill = false), maxLines = 1)
                if (c.tier != "community") tierLabel(c.tier, t)?.let { Spacer(Modifier.width(8.dp)); Tag(it, tierColor(c.tier)) }
            }
            val d = remember(c.description) { brandFree(c.description) }
            if (d.isNotBlank()) Txt(d, Theme.type.sub, col.secondary, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        InstallSlot(c.name, installed, busy, worker, onInstall)
    }
}

/** A curated plugin for this PC (`onboarding`); its [SuggestedPlugin.sentence] says what the PC still lacks. */
@Composable
fun SuggestedItem(s: SuggestedPlugin, installed: Boolean, busy: Boolean, worker: BuddySpec?, onOpen: () -> Unit, onInstall: () -> Unit) {
    val col = Theme.colors
    PressRow(onOpen, minHeight = 72.dp, label = s.title) {
        PluginGlyph(s.name, icon = Icons.autoAwesome)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(brandFree(s.title), Theme.type.headline, maxLines = 1)
            val line = brandFree(s.sentence).ifBlank { brandFree(s.description) }
            if (line.isNotBlank()) Txt(line, Theme.type.sub, if (s.sentence.isNotBlank()) col.needsYouText else col.secondary, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        InstallSlot(s.name, installed, busy, worker, onInstall)
    }
}

@Composable
private fun InstallSlot(name: String, installed: Boolean, busy: Boolean, worker: BuddySpec?, onInstall: () -> Unit) {
    val c = Theme.colors
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        when {
            busy && worker != null -> Buddy(remember(worker, name) { worker.copy(id = worker.id + "/" + name) }, Mood.Working, 36.dp, interactive = false)
            installed -> Icon(Icons.checkCircleFilled, c.success)
            else -> IconButton(Icons.add, S.pluginsPage.install, onInstall, fill = c.surface2)
        }
    }
}

/** Long-press / menu sheet row. */
@Composable
fun MenuRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = Theme.colors
    PressRow(onClick, minHeight = 56.dp, label = label) {
        Icon(icon, if (danger) c.error else c.text)
        Spacer(Modifier.width(16.dp))
        Txt(label, Theme.type.body, if (danger) c.errorText else c.text, maxLines = 1)
    }
}
