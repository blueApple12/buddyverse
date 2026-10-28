package app.buddyverse.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.core.Bot
import app.buddyverse.core.BotStatus
import app.buddyverse.core.api.Toolset
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Card
import app.buddyverse.design.Haptic
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.toolsPage
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.MorePage
import app.buddyverse.ui.PageFrame
import app.buddyverse.ui.more.tools.ToolsState
import app.buddyverse.ui.more.tools.ToolsetIcon
import app.buddyverse.ui.more.tools.ToolsetSheet
import app.buddyverse.ui.more.tools.rememberToolsState
import app.buddyverse.ui.more.tools.toolsetName

/**
 * More ▸ Tools (hermes-api.md §2.6 Tools and toolsets): one bot's toolsets (pick the bot on top), each with a squishy switch;
 * tap a row for its sheet (provider, keys, install, model, backend, tools). The bot's buddy reacts to every change.
 */
@Composable
fun ToolsPage() {
    val store = LocalStore.current
    val s = S.current
    val ts = S.toolsPage
    val state = rememberToolsState()
    val bots by store.bots.collectAsState()
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state) { if (state.profile == null) state.select(store.bots.value.firstOrNull { it.isDefault }?.profile ?: "default") }

    val profile = state.profile ?: "default"
    val bot = bots.firstOrNull { it.profile == profile }
    val spec = bot?.spec ?: remember(profile) { BuddySpec(profile, profile, BuddyShape.parse(null, profile), BuddyColors.forName(profile)) }
    state.errorText = s.error
    state.buddy = spec
    val mood = when {
        state.setupRunning -> Mood.Working
        state.loading -> Mood.Thinking
        bot?.status == BotStatus.Working -> Mood.Working
        else -> Mood.Idle
    }
    val groups = state.toolsets.groupBy { it.platform }

    Box(Modifier.fillMaxSize()) {
        PageFrame(MorePage.Tools.label, actions = { IconButton(Icons.refresh, ts.refresh, state::refresh) }) {
            item(key = "head") { Head(bot?.name ?: profile, spec, mood, state) }
            if (bots.size > 1) item(key = "bots") { BotPicker(bots.sortedWith(Bot.order), profile, state::select) }
            when {
                state.toolsets.isNotEmpty() -> groups.forEach { (platform, list) ->
                    if (groups.size > 1) item(key = "sec-$platform") {
                        SectionLabel(if (platform == "cli") ts.general else list.first().platformLabel.ifBlank { platform })
                    }
                    items(list, key = { "t-${it.name}" }) { t ->
                        ToolsetRow(
                            t, t.tools.sumOf { state.counts[it] ?: 0L }, Modifier.animateItem(),
                            onOpen = { open = t.name }, onToggle = { state.toggle(t, it) },
                        )
                    }
                }
                state.failed -> item(key = "failed") {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Txt(s.error, Theme.type.sub, Theme.colors.errorText)
                        PillButton(s.retry, state::load, kind = ButtonKind.Quiet)
                    }
                }
                !state.loading -> item(key = "empty") { Txt(s.nothingHere, Theme.type.sub, Theme.colors.secondary, Modifier.padding(20.dp)) }
            }
        }
        ToolsetSheet(state, open, spec) { open = null }
    }
}

@Composable
private fun Head(name: String, spec: BuddySpec, mood: Mood, state: ToolsState) {
    val all = state.toolsets.size
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Buddy(spec, mood, 72.dp, cue = state.cue)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Txt(name, Theme.type.title2, maxLines = 1)
                if (all > 0) Txt(S.toolsPage.toolsOn(state.toolsets.count { it.enabled }, all), Theme.type.sub, Theme.colors.secondary, maxLines = 1)
            }
        }
    }
}

/** Which bot's tools: a strip of 48dp chips, each with its own live mini buddy. */
@Composable
private fun BotPicker(bots: List<Bot>, selected: String, onPick: (String) -> Unit) {
    val c = Theme.colors
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(bots, key = { it.profile }) { b ->
            val on = b.profile == selected
            Box(Modifier.heightIn(min = 48.dp).squish({ onPick(b.profile) }, haptic = Haptic.Tick, label = b.name), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.height(44.dp).clip(CircleShape).background(if (on) c.accentFill else c.surface2).padding(start = 6.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Buddy(b.spec, if (b.status == BotStatus.Working) Mood.Working else Mood.Idle, 32.dp, interactive = false)
                    Spacer(Modifier.width(8.dp))
                    Txt(b.name, Theme.type.sub, if (on) c.onAccent else c.text, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ToolsetRow(t: Toolset, used: Long, modifier: Modifier, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    val c = Theme.colors
    val ts = S.toolsPage
    val name = toolsetName(t)
    val needsSetup = t.enabled && !t.configured
    val sub = listOfNotNull(
        ts.needsSetup.takeIf { needsSetup },
        ts.toolsCount(t.tools.size).takeIf { t.tools.isNotEmpty() },
        ts.usedTimes(used).takeIf { used > 0 },
    ).joinToString(" · ")
    PressRow(onOpen, modifier, minHeight = 72.dp, label = name) {
        ToolsetIcon(t.name, t.enabled)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(name, Theme.type.body, maxLines = 1)
            if (sub.isNotEmpty()) Txt(sub, Theme.type.caption, if (needsSetup) c.needsYouText else c.secondary, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Toggle(t.enabled, onToggle, name)
    }
}
