package app.buddyverse.ui.more

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Mood
import app.buddyverse.core.api.OwnEndpointDraft
import app.buddyverse.core.api.MODEL_REASONING_LEVELS
import app.buddyverse.core.api.modelTokens
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Card
import app.buddyverse.design.Chip
import app.buddyverse.design.Hairline
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.modelsPage
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.PageFrame
import app.buddyverse.ui.ltr
import app.buddyverse.ui.more.models.AddRow
import app.buddyverse.ui.more.models.BotStrip
import app.buddyverse.ui.more.models.CatalogSheet
import app.buddyverse.ui.more.models.ConfirmSheet
import app.buddyverse.ui.more.models.ContextSheet
import app.buddyverse.ui.more.models.EndpointSheet
import app.buddyverse.ui.more.models.InfoRow
import app.buddyverse.ui.more.models.JobRow
import app.buddyverse.ui.more.models.KeySheet
import app.buddyverse.ui.more.models.KeysSheet
import app.buddyverse.ui.more.models.LiveDot
import app.buddyverse.ui.more.models.LocalFileSheet
import app.buddyverse.ui.more.models.ModelIcon
import app.buddyverse.ui.more.models.ModelsModel
import app.buddyverse.ui.more.models.PageMessage
import app.buddyverse.ui.more.models.PickTarget
import app.buddyverse.ui.more.models.PickerSheet
import app.buddyverse.ui.more.models.ProviderSheet
import app.buddyverse.ui.more.models.ProvidersSheet
import app.buddyverse.ui.more.models.NewTeamSheet
import app.buddyverse.ui.more.models.SubagentCard
import app.buddyverse.ui.more.models.TeamSheet
import app.buddyverse.ui.more.models.statusColor
import app.buddyverse.ui.more.models.statusOf
import kotlinx.coroutines.delay
import app.buddyverse.ui.modelLabel
import app.buddyverse.ui.LocalNav

/**
 * More ▸ Models (hermes-api.md §2.3), per bot: the default model with its thinking level, speed and context; backups;
 * helper models; providers (phone sign-in, PC sign-in, keys) with their quotas; your own endpoints; models running on
 * this PC (catalog, Hugging Face, files, downloads with progress); and every key in the bot's `.env`.
 */
@Composable
fun ModelsPage() {
    val store = LocalStore.current
    val t = S.modelsPage
    val s = S.current
    val toasts = LocalToasts.current
    val fb = LocalFeedback.current
    val m = remember(store) { ModelsModel(store, toasts, fb) }
    m.t = t
    m.undoLabel = s.undo
    val bots by store.bots.collectAsState()
    var picked by remember { mutableStateOf<String?>(null) }
    val profile = picked ?: bots.firstOrNull { it.isDefault }?.profile ?: bots.firstOrNull()?.profile ?: "default"
    val bot = bots.firstOrNull { it.profile == profile }
    LaunchedEffect(profile) {
        m.load(profile)
        while (true) { delay(m.pollMs); m.refreshLocal() }
    }
    var hurt by remember { mutableStateOf(false) }
    LaunchedEffect(m.failedAt) { if (m.failedAt > 0) { hurt = true; delay(2_500); hurt = false } }
    val mood = when {
        m.unsupported -> Mood.Sleeping
        !m.loaded -> Mood.Thinking
        hurt || (m.error != null && m.info == null) -> Mood.Error
        m.flow != null || m.pending != null -> Mood.NeedsYou
        m.working -> Mood.Working
        else -> Mood.Idle
    }

    var picker by remember { mutableStateOf<PickTarget?>(null) }
    var pickerOnly by remember { mutableStateOf<String?>(null) }
    var context by remember { mutableStateOf(false) }
    var providersOpen by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf<String?>(null) }
    var endpoint by remember { mutableStateOf<OwnEndpointDraft?>(null) }
    var catalogOpen by remember { mutableStateOf(false) }
    var localFile by remember { mutableStateOf<String?>(null) }
    var keysOpen by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf<String?>(null) }
    var helpersOpen by remember { mutableStateOf(false) }
    var team by remember { mutableStateOf<String?>(null) }
    var newTeam by remember { mutableStateOf(false) }
    fun pick(target: PickTarget, only: String? = null) { pickerOnly = only; picker = target }
    val nav = LocalNav.current
    LaunchedEffect(nav.connectModel, m.loaded) { if (nav.connectModel && m.loaded) { nav.connectModel = false; providersOpen = true } }

    val info = m.info
    val summary = when {
        info != null && info.model.isNotBlank() -> t.botModel(bot?.name ?: profile, ltr(modelLabel(info.model) ?: info.model))
        m.loaded -> bot?.name ?: profile
        else -> t.looking
    }

    Box(Modifier.fillMaxSize()) {
        PageFrame(s.models, actions = { IconButton(Icons.refresh, t.refresh, { fb.haptic(Haptic.Tick); m.load(profile, refresh = true) }, enabled = m.loaded) }) {
            item(key = "bots") {
                BotStrip(bots, profile, mood, m.cue, summary) { p ->
                    if (p != profile) { picked = p; provider = null; localFile = null; key = null; picker = null }
                }
            }
            when {
                m.unsupported -> item(key = "msg") { PageMessage(s.unsupported) }
                !m.loaded -> Unit
                info == null -> item(key = "msg") { PageMessage(m.error ?: s.error, s.retry, Icons.refresh) { m.load(profile) } }
                else -> {
                    item(key = "main") { MainCard(m, onPick = { pick(PickTarget.Main) }, onContext = { context = true }) }

                    // backups, tried in order when the default fails
                    val fallbacks = m.settings?.fallbacks.orEmpty()
                    item(key = "hdr:fb") { SectionLabel(t.fallbacks) }
                    items(fallbacks, key = { "fb:${it.provider}:${it.model}" }) { f ->
                        val i = fallbacks.indexOf(f)
                        InfoRow(f.model, m.providerName(f.provider), { if (i > 0) m.moveFallback(f, up = true) }, Modifier.smoothItem(this), mono = true,
                            lead = { Rank(i + 1) }, trail = { IconButton(Icons.close, t.remove, { m.removeFallback(f) }) })
                    }
                    item(key = "add:fb") { AddRow(t.addFallback, { pick(PickTarget.Fallback) }, Modifier.smoothItem(this)) }

                    // helper models (titles, vision, compression…), collapsed by default
                    val pinned = m.helpers.count { !it.auto }
                    item(key = "hdr:aux") { SectionLabel(t.helpers) }
                    item(key = "tgl:aux") { HelpersToggle(if (pinned == 0) t.allAutomatic else t.pinnedCount(pinned), helpersOpen) { helpersOpen = !helpersOpen } }
                    if (helpersOpen) {
                        items(m.helpers, key = { "aux:" + it.task }) { h ->
                            val busy = "helper:${h.task}" in m.busy
                            InfoRow(t.helperLabel(h.task, h.label), if (h.auto) t.followsMain else "${h.model} · ${m.providerName(h.provider)}",
                                { pick(PickTarget.Helper(h.task)) }, Modifier.smoothItem(this),
                                lead = { LiveDot(if (h.auto) Theme.colors.tertiary else Theme.colors.accent, busy) })
                        }
                        if (pinned > 0) item(key = "rst:aux") {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).smoothItem(this)) {
                                PillButton(t.allToAuto, m::resetHelpers, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet, icon = Icons.undo, enabled = "helpers" !in m.busy)
                            }
                        }
                    }

                    // subagents: the helpers a bot starts for parallel work
                    item(key = "hdr:sub") { SectionLabel(t.subagents) }
                    item(key = "card:sub") { SubagentCard(m) { pick(PickTarget.Subagent) } }

                    // team answers: several models answer, one combines (Mixture of Agents presets)
                    val teams = m.teams
                    if (teams != null) {
                        item(key = "hdr:team") { SectionLabel(t.teams) }
                        items(teams.teams, key = { "team:" + it.name }) { tm ->
                            InfoRow(tm.name, t.teamSummary(tm.advisors.count { it.enabled }, tm.combiner.model) + if (tm.enabled) "" else " · ${t.off}",
                                { team = tm.name }, Modifier.smoothItem(this),
                                lead = {
                                    LiveDot(
                                        when {
                                            tm.name == teams.defaultTeam -> Theme.colors.accent
                                            tm.enabled -> Theme.colors.success
                                            else -> Theme.colors.tertiary
                                        },
                                        "teams" in m.busy,
                                    )
                                })
                        }
                        item(key = "add:team") { AddRow(t.addTeam, { newTeam = true }, Modifier.smoothItem(this), icon = Icons.group) }
                    }

                    // providers you're connected to; the rest are one tap away
                    val connected = m.entries.filter { it.connected }
                    item(key = "hdr:p") { SectionLabel(t.providers) }
                    items(connected, key = { "p:" + it.slug }) { e ->
                        val w = e.provider?.usage?.maxByOrNull { it.usedPercent }
                        InfoRow(e.name, listOfNotNull(statusOf(e, t), w?.let { "${it.label} ${ltr("${it.usedPercent}%")}" }).joinToString(" · "),
                            { provider = e.slug }, Modifier.smoothItem(this), lead = { LiveDot(statusColor(e), "keys:${e.slug}" in m.busy) },
                            subColor = if ((w?.usedPercent ?: 0) >= 80) Theme.colors.needsYouText else Theme.colors.secondary)
                    }
                    item(key = "add:p") { AddRow(t.addProvider, { providersOpen = true }, Modifier.smoothItem(this)) }

                    // your own addresses (a local server, a LAN box, any compatible API)
                    val eps = m.endpoints.filter { "ep:${it.id}" !in m.hidden }
                    item(key = "hdr:ep") { SectionLabel(t.endpoints) }
                    items(eps, key = { "ep:" + it.id }) { ep ->
                        InfoRow(ep.name, listOf(ep.model, ep.baseUrl.substringAfter("://").substringBefore('/')).filter { it.isNotBlank() }.joinToString(" · "),
                            {
                                endpoint = OwnEndpointDraft(ep.id, ep.name, ep.baseUrl, ep.model, "", ep.apiMode, ep.contextLength?.takeIf { it > 0 }?.toString().orEmpty())
                            }, Modifier.smoothItem(this), lead = { LiveDot(if (ep.current) Theme.colors.accent else Theme.colors.success, "ep:${ep.id}" in m.busy) })
                    }
                    item(key = "add:ep") { AddRow(t.addEndpoint, { endpoint = OwnEndpointDraft() }, Modifier.smoothItem(this), icon = Icons.link) }

                    // on this PC
                    item(key = "hdr:l") { SectionLabel(t.onThisPc) }
                    item(key = "card:l") { LocalCard(m) }
                    items(m.jobs.filter { it.active }, key = { "job:" + it.id }) { j -> JobRow(m, j, Modifier.smoothItem(this)) }
                    val files = m.local?.files.orEmpty().filter { "local:${it.id}" !in m.hidden }
                    items(files, key = { "lf:" + it.id }) { f ->
                        val st = m.local
                        val active = st?.activeModelId == f.id
                        val loaded = st?.loaded?.contains(f.id) == true
                        InfoRow(f.id, listOfNotNull(ltr(f.sizeLabel), t.inUse.takeIf { active }, t.loaded.takeIf { loaded }).joinToString(" · "),
                            { localFile = f.id }, Modifier.smoothItem(this), mono = true,
                            lead = { LiveDot(if (active) Theme.colors.accent else if (loaded) Theme.colors.success else Theme.colors.tertiary, "local:${f.id}" in m.busy) })
                    }
                    item(key = "add:l") { AddRow(t.getModel, { catalogOpen = true }, Modifier.smoothItem(this), icon = Icons.autoAwesome) }

                    // keys
                    item(key = "hdr:k") { SectionLabel(t.keys) }
                    item(key = "all:k") {
                        val set = m.env.count { it.isSet && "env:${it.key}" !in m.hidden }
                        InfoRow(t.allKeys, t.keysSet(set), { keysOpen = true }, lead = { ModelIcon(Icons.lock) })
                    }
                }
            }
        }
        TeamSheet(m, team, onPick = { target -> pick(target) }) { team = null }
        NewTeamSheet(m, newTeam) { newTeam = false }
        PickerSheet(m, picker, pickerOnly) { picker = null }
        ContextSheet(m, context) { context = false }
        ProvidersSheet(m, providersOpen, onOpen = { provider = it }) { providersOpen = false }
        ProviderSheet(m, provider, bot, mood, onPickModel = { slug -> provider = null; providersOpen = false; pick(PickTarget.Main, slug) }) { provider = null }
        EndpointSheet(m, endpoint) { endpoint = null }
        CatalogSheet(m, catalogOpen, bot) { catalogOpen = false }
        LocalFileSheet(m, localFile) { localFile = null }
        KeysSheet(m, keysOpen, onOpenKey = { key = it }) { keysOpen = false }
        KeySheet(m, key) { key = null }
        ConfirmSheet(m)
    }
}

/** Lists animate inserts, removals and moves on `smooth` (design §5.1 Lists). */
private fun Modifier.smoothItem(scope: LazyItemScope): Modifier =
    with(scope) { this@smoothItem.animateItem(fadeInSpec = Motion.fade(), placementSpec = Motion.smooth(), fadeOutSpec = Motion.fade()) }

/** The default model, with thinking level, speed and context. The name pops when it changes. */
@Composable
private fun MainCard(m: ModelsModel, onPick: () -> Unit, onContext: () -> Unit) {
    val t = S.modelsPage
    val c = Theme.colors
    val info = m.info ?: return
    val st = m.settings
    val pop = remember { Animatable(1f) }
    LaunchedEffect(info.model) { pop.snapTo(.88f); pop.animateTo(1f, Motion.bouncy()) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), padding = 0.dp) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 84.dp).squish(onPick, scale = .97f, label = t.defaultModel).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Txt(t.defaultModel.uppercase(), Theme.type.caps, c.secondary, maxLines = 1)
                Crossfade(info.model, animationSpec = Motion.fade()) { model ->
                    Txt(ltr(modelLabel(model) ?: t.none), Theme.type.title2, modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }, maxLines = 1)
                }
                Txt(m.providerName(info.provider), Theme.type.sub, c.secondary, maxLines = 1)
            }
            Icon(Icons.chevronRight, c.tertiary, mirror = true)
        }
        Hairline()
        PressRow(onContext, minHeight = 56.dp, label = t.context) {
            Txt(t.context, Theme.type.callout, modifier = Modifier.weight(1f), maxLines = 1)
            val ctx = st?.contextLength ?: 0L
            Txt(ltr(if (ctx > 0) modelTokens(ctx) else t.autoWith(modelTokens(info.effectiveContext))), Theme.type.sub, c.secondary, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.chevronRight, c.tertiary, 20.dp, mirror = true)
        }
        if (st != null) {
            Txt(t.thinking.uppercase(), Theme.type.caps, c.secondary, Modifier.padding(start = 20.dp, top = 6.dp), maxLines = 1)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MODEL_REASONING_LEVELS.forEach { lv -> Chip(t.level(lv), st.reasoning == lv, { m.setReasoning(lv) }) }
            }
            val p = m.providers.firstOrNull { it.slug == info.provider }
            val fast = p?.fast?.contains(info.model) == true
            val ultra = p?.ultrafast?.contains(info.model) == true
            if (fast || ultra || st.serviceTier.isNotBlank()) {
                Txt(t.speed.uppercase(), Theme.type.caps, c.secondary, Modifier.padding(start = 20.dp, top = 6.dp), maxLines = 1)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOfNotNull("", "priority".takeIf { fast || st.serviceTier == "priority" }, "ultrafast".takeIf { ultra || st.serviceTier == "ultrafast" })
                        .forEach { tier -> Chip(t.speedName(tier), st.serviceTier == tier, { m.setSpeed(tier) }, icon = Icons.bolt.takeIf { tier.isNotEmpty() }) }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun Rank(n: Int) {
    val c = Theme.colors
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Txt(ltr("$n"), Theme.type.headline, c.secondary, maxLines = 1) }
}

@Composable
private fun HelpersToggle(summary: String, open: Boolean, onToggle: () -> Unit) {
    val t = S.modelsPage
    val c = Theme.colors
    val turn by animateFloatAsState(if (open) 180f else 0f, Motion.snappy())
    PressRow(onToggle, label = t.helpers) {
        ModelIcon(Icons.psychology)
        Spacer(Modifier.width(14.dp))
        Txt(summary, Theme.type.headline, modifier = Modifier.weight(1f), maxLines = 1)
        Box(Modifier.size(48.dp).graphicsLayer { rotationZ = turn }, contentAlignment = Alignment.Center) { Icon(Icons.expandMore, c.tertiary) }
    }
}

/** This PC's GPU and the local engine: install it, update it, switch it on or off. */
@Composable
private fun LocalCard(m: ModelsModel) {
    val t = S.modelsPage
    val c = Theme.colors
    val st = m.local
    val hw = m.hardware
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModelIcon(Icons.memory, c.working)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(hw?.gpu ?: t.thisPc, Theme.type.headline, maxLines = 1)
                Txt(listOfNotNull(hw?.vramLabel?.let(t.gpuMemory), when {
                    st == null -> null
                    !st.runtimeInstalled -> t.engineMissing
                    st.serverRunning -> t.engineOn
                    else -> t.engineOff
                }).joinToString(" · "), Theme.type.sub, if (st?.serverRunning == true) c.success else c.secondary, maxLines = 1)
            }
            if (st?.runtimeInstalled == true) Toggle(st.serverRunning, { on -> m.setServer(on) }, t.localEngine, enabled = "server" !in m.busy)
        }
        if (st != null && (!st.runtimeInstalled || st.updateAvailable)) {
            Spacer(Modifier.height(12.dp))
            val running = m.jobs.any { it.active && it.kind == "runtime-install" }
            PillButton(if (st.runtimeInstalled) t.updateEngine else t.installEngine, m::installRuntime, Modifier.fillMaxWidth(),
                kind = if (st.runtimeInstalled) ButtonKind.Outline else ButtonKind.Primary, icon = Icons.build, enabled = !running && "runtime" !in m.busy)
        }
    }
}
