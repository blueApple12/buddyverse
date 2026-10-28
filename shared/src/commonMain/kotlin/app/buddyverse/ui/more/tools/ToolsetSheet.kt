package app.buddyverse.ui.more.tools

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.api.ComputerUseStatus
import app.buddyverse.core.api.MODEL_TOOLSETS
import app.buddyverse.core.api.SetupStatus
import app.buddyverse.core.api.TerminalBackend
import app.buddyverse.core.api.ToolModels
import app.buddyverse.core.api.ToolProvider
import app.buddyverse.core.api.Toolset
import app.buddyverse.core.api.ToolsetConfig
import app.buddyverse.core.api.computerUseStatus
import app.buddyverse.core.api.grantComputerUse
import app.buddyverse.core.api.saveToolKeys
import app.buddyverse.core.api.selectTerminalBackend
import app.buddyverse.core.api.selectToolModel
import app.buddyverse.core.api.selectToolProvider
import app.buddyverse.core.api.terminalBackends
import app.buddyverse.core.api.toolModels
import app.buddyverse.core.api.toolsetConfig
import app.buddyverse.design.ButtonKind
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
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.toolsPage
import app.buddyverse.ui.toolLabel
import kotlinx.coroutines.CancellationException

/** A toolset's name in the UI language, without engine branding. */
@Composable
internal fun toolsetName(t: Toolset): String = S.toolsPage.toolsetName(t.name) ?: brandFree(t.label)

internal fun toolsetIcon(name: String): ImageVector = when (name) {
    "web", "x_search" -> Icons.search
    "browser" -> Icons.language
    "terminal" -> Icons.terminal
    "file" -> Icons.folder
    "code_execution" -> Icons.bolt
    "vision" -> Icons.visibility
    "video", "video_gen" -> Icons.playArrow
    "image_gen" -> Icons.image
    "tts" -> Icons.volumeUp
    "stt" -> Icons.mic
    "skills" -> Icons.autoAwesome
    "todo" -> Icons.checkCircle
    "kanban" -> Icons.viewKanban
    "memory" -> Icons.psychology
    "context_engine" -> Icons.memory
    "session_search" -> Icons.history
    "connections" -> Icons.cable
    "clarify" -> Icons.help
    "delegation" -> Icons.group
    "cronjob" -> Icons.schedule
    "discord", "discord_admin", "yuanbao" -> Icons.chat
    "computer_use" -> Icons.devices
    "a2a" -> Icons.hub
    else -> Icons.build
}

/** Icon tile that lights up in the accent when on, with a bouncy pop the moment it turns on. */
@Composable
internal fun ToolsetIcon(name: String, on: Boolean, size: Dp = 44.dp) {
    val c = Theme.colors
    val bg by animateColorAsState(if (on) c.accent.copy(alpha = .16f) else c.surface2, Motion.fade())
    val fg by animateColorAsState(if (on) c.accent else c.tertiary, Motion.fade())
    val pop = remember { Animatable(1f) }
    var was by remember { mutableStateOf(on) }
    LaunchedEffect(on) {
        if (on && !was) { pop.snapTo(.6f); pop.animateTo(1f, Motion.bouncy()) }
        was = on
    }
    Box(
        Modifier.size(size).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.clip(RoundedCornerShape(14.dp)).background(bg),
        contentAlignment = Alignment.Center,
    ) { Icon(toolsetIcon(name), fg, 24.dp) }
}

/** Radio mark: the dot springs in with `bouncy`. */
@Composable
private fun Radio(on: Boolean) {
    val c = Theme.colors
    val k by animateFloatAsState(if (on) 1f else 0f, Motion.bouncy())
    Box(Modifier.size(24.dp).clip(CircleShape).border(2.dp, if (on) c.accentFill else c.tertiary, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(12.dp).graphicsLayer { scaleX = k; scaleY = k }.clip(CircleShape).background(c.accentFill))
    }
}

@Composable
private fun statusText(status: String): String? = when (status) {
    "needs_keys" -> S.toolsPage.needsKey
    "needs_auth" -> S.toolsPage.needsSignIn
    "needs_setup" -> S.toolsPage.needsSetup
    "unavailable" -> S.toolsPage.unavailable
    else -> null
}

private val secretKey = Regex("KEY|TOKEN|SECRET|PASSWORD")

private fun ToolsetConfig.isActive(p: ToolProvider, cap: String?): Boolean = when (cap) {
    "search" -> p.webBackend != null && p.webBackend == activeSearch
    "extract" -> p.webBackend != null && p.webBackend == activeExtract
    else -> p.active
}

private fun ToolsetConfig.withActive(p: ToolProvider, cap: String?): ToolsetConfig = when (cap) {
    "search" -> copy(activeSearch = p.webBackend)
    "extract" -> copy(activeExtract = p.webBackend)
    else -> copy(activeProvider = p.name, providers = providers.map { it.copy(active = it.name == p.name) })
}

/**
 * One toolset, all of it (hermes-api.md §2.6): on/off, provider and keys, PC-side install with its live log, model,
 * terminal backend, computer-use readiness, and every tool with its call count. Closes by back, scrim and ×.
 */
@Composable
fun ToolsetSheet(state: ToolsState, open: String?, spec: BuddySpec, onDismiss: () -> Unit) {
    var last by remember { mutableStateOf<String?>(null) }
    SideEffect { if (open != null) last = open }
    val name = open ?: last
    val t = name?.let { n -> state.toolsets.firstOrNull { it.name == n } }
    Sheet(open != null, onDismiss, title = t?.let { toolsetName(it) }, heightFraction = .92f) {
        val p = state.profile
        if (t != null && p != null) SheetBody(state, t, p, spec)
    }
}

@Composable
private fun ColumnScope.SheetBody(state: ToolsState, t: Toolset, p: String, spec: BuddySpec) {
    val s = S.current
    val ts = S.toolsPage
    val c = Theme.colors
    val api = state.api
    val name = t.name
    var config by remember(name, p) { mutableStateOf<ToolsetConfig?>(null) }
    var models by remember(name, p) { mutableStateOf<ToolModels?>(null) }
    var backends by remember(name, p) { mutableStateOf<List<TerminalBackend>>(emptyList()) }
    var cu by remember(name, p) { mutableStateOf<ComputerUseStatus?>(null) }
    var loading by remember(name, p) { mutableStateOf(true) }
    var failed by remember(name, p) { mutableStateOf(false) }
    var expanded by remember(name, p) { mutableStateOf<String?>(null) }
    var cap by remember(name) { mutableStateOf("search") }
    val input = remember(name, p) { mutableStateMapOf<String, String>() }
    LaunchedEffect(name, p, state.configRev) {
        loading = true; failed = false
        try {
            config = api.toolsetConfig(name, p)
            if (name in MODEL_TOOLSETS) models = api.toolModels(name, null, p)
            if (name == "terminal") backends = api.terminalBackends(p)
            if (name == "computer_use") cu = api.computerUseStatus(p)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
        loading = false
    }
    val installing = state.setupRunning && state.setupFor == name
    val mood = when { installing -> Mood.Working; loading && config == null -> Mood.Thinking; else -> Mood.Idle }
    // Only cues fired while the sheet is open: no replay of an old reaction on open.
    val since = remember { state.cue?.serial ?: 0L }
    val capArg = if (name == "web") cap else null
    val desc = t.description.lineSequence().firstOrNull().orEmpty()
        .let { d -> if (d.split(',').any { it.trim().substringBefore(' ') in t.tools }) "" else brandFree(d) }

    LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Buddy(spec, mood, 56.dp, cue = state.cue?.takeIf { it.serial > since })
                Spacer(Modifier.width(14.dp))
                Txt(desc, Theme.type.sub, c.secondary, Modifier.weight(1f), maxLines = 3)
                Toggle(t.enabled, { state.toggle(t, it) }, toolsetName(t))
            }
        }
        if (failed && config == null) item(key = "failed") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt(s.error, Theme.type.sub, c.errorText)
                PillButton(s.retry, state::refresh, kind = ButtonKind.Quiet)
            }
        }

        cu?.let { status ->
            item(key = "cu") { ComputerUse(status) { state.write { api.grantComputerUse() } } }
        }

        if (backends.isNotEmpty()) {
            item(key = "be-label") { SectionLabel(ts.runsOn) }
            items(backends, key = { "be-${it.name}" }) { b ->
                PressRow({
                    if (!b.active) {
                        val before = backends
                        state.write(optimistic = { backends = backends.map { it.copy(active = it.name == b.name) } }, revert = { backends = before }) { pp ->
                            api.selectTerminalBackend(b.name, pp)
                        }
                    }
                }, Modifier.animateItem(), minHeight = 64.dp, label = b.label) {
                    Radio(b.active)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(brandFree(b.label), Theme.type.callout, maxLines = 1)
                        if (b.description.isNotBlank()) Txt(brandFree(b.description), Theme.type.caption, c.secondary, maxLines = 2)
                        if (b.status != "ready" && b.detail.isNotBlank()) Txt(brandFree(b.detail), Theme.type.caption, c.needsYouText, maxLines = 3)
                    }
                }
            }
        }

        val cfg = config
        val visible = cfg?.providers.orEmpty().filter { !it.vendorManaged && (capArg == null || capArg in it.capabilities) }
        if (cfg != null && cfg.hasCategory && (visible.isNotEmpty() || capArg != null)) {
            item(key = "pv-label") { SectionLabel(ts.provider) }
            if (capArg != null) item(key = "caps") {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(s.search, cap == "search", { cap = "search"; expanded = null })
                    Chip(ts.readPages, cap == "extract", { cap = "extract"; expanded = null })
                }
            }
            items(visible, key = { "pv-${it.name}" }) { pr ->
                val active = cfg.isActive(pr, capArg)
                ProviderItem(
                    pr, active, open = expanded == pr.name, input = input, setupBusy = state.setupRunning, modifier = Modifier.animateItem(),
                    onExpand = { expanded = if (expanded == pr.name) null else pr.name },
                    onUse = { typed ->
                        typed.keys.forEach { input.remove(it) }
                        val before = config
                        state.write(OneShot.Learned, confirm = true, optimistic = { if (!active) config = config?.withActive(pr, capArg) }, revert = { config = before }) { pp ->
                            if (typed.isNotEmpty()) api.saveToolKeys(name, typed, pp)
                            if (!active) api.selectToolProvider(name, pr.name, pp, capArg)
                        }
                    },
                    onSetup = { pr.postSetup?.let { state.runSetup(name, it) } },
                )
            }
        }

        models?.takeIf { it.models.isNotEmpty() }?.let { m ->
            item(key = "md-label") { SectionLabel(ts.model) }
            items(m.models, key = { "md-${it.id}" }) { md ->
                PressRow({
                    if (md.id != m.current) {
                        val before = models
                        state.write(optimistic = { models = m.copy(current = md.id) }, revert = { models = before }) { pp ->
                            api.selectToolModel(name, md.id, m.provider, pp)
                        }
                    }
                }, Modifier.animateItem(), minHeight = 64.dp, label = md.display) {
                    Radio(md.id == m.current)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(md.display, Theme.type.callout, maxLines = 1)
                        val sub = listOf(md.strengths, md.speed, md.price).filter { it.isNotBlank() }.joinToString(" · ")
                        if (sub.isNotEmpty()) Txt(brandFree(sub), Theme.type.caption, c.secondary, maxLines = 2)
                    }
                }
            }
        }

        val log = state.setupLog
        if (state.setupFor == name && (installing || log != null)) item(key = "setup") { SetupLog(installing, log) }

        if (t.tools.isNotEmpty()) {
            item(key = "tl-label") { SectionLabel("${s.tools} · ${t.tools.size}") }
            items(t.tools, key = { "tl-$it" }) { tool ->
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Txt(toolLabel(tool, null, s), Theme.type.callout, maxLines = 1)
                        Txt(tool, Theme.type.code.copy(fontSize = 12.sp, lineHeight = 16.sp), c.tertiary, maxLines = 1)
                    }
                    state.counts[tool]?.takeIf { it > 0 }?.let { Txt(ts.usedTimes(it), Theme.type.caption, c.secondary, maxLines = 1) }
                }
            }
        }
    }
}

/** A provider row: tap to open it; open shows its note, key fields, `Use this` / `Save`, and `Set up on PC` when it needs an install. */
@Composable
private fun ProviderItem(
    pr: ToolProvider,
    active: Boolean,
    open: Boolean,
    input: MutableMap<String, String>,
    setupBusy: Boolean,
    modifier: Modifier,
    onExpand: () -> Unit,
    onUse: (Map<String, String>) -> Unit,
    onSetup: () -> Unit,
) {
    val s = S.current
    val ts = S.toolsPage
    val c = Theme.colors
    val uri = LocalUriHandler.current
    val status = statusText(pr.status)
    Column(modifier.fillMaxWidth().animateContentSize(Motion.smooth())) {
        PressRow(onExpand, minHeight = 64.dp, label = pr.name) {
            Radio(active)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(brandFree(pr.name), Theme.type.callout, maxLines = 1)
                val sub = listOfNotNull(brandFree(pr.badge).takeIf { it.isNotBlank() }, status).joinToString(" · ")
                if (sub.isNotEmpty()) Txt(sub, Theme.type.caption, if (status != null) c.needsYouText else c.secondary, maxLines = 1)
            }
            val turn by animateFloatAsState(if (open) 180f else 0f, Motion.snappy())
            Icon(Icons.expandMore, c.secondary, 24.dp, Modifier.graphicsLayer { rotationZ = turn })
        }
        if (open) Column(Modifier.fillMaxWidth().padding(start = 58.dp, end = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            brandFree(pr.tag).takeIf { it.isNotBlank() }?.let { Txt(it, Theme.type.sub, c.secondary, maxLines = 5) }
            pr.envVars.forEach { e ->
                val secret = secretKey.containsMatchIn(e.key)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(
                        input[e.key].orEmpty(), { input[e.key] = it }, if (e.isSet) ts.keySaved else brandFree(e.prompt), Modifier.weight(1f),
                        password = secret, mono = true, keyboard = if (secret) KeyboardType.Password else KeyboardType.Uri,
                    )
                    e.url?.let { u -> Spacer(Modifier.width(4.dp)); IconButton(Icons.link, ts.getKey, { uri.openUri(u) }) }
                }
            }
            val typed = pr.envVars.mapNotNull { e -> input[e.key]?.trim()?.takeIf { it.isNotEmpty() }?.let { e.key to it } }.toMap()
            if (!active || typed.isNotEmpty()) PillButton(if (active) s.save else ts.useThis, { onUse(typed) }, Modifier.fillMaxWidth(), haptic = Haptic.Confirm)
            if (pr.postSetup != null && !pr.ready) {
                PillButton(ts.setUp, onSetup, Modifier.fillMaxWidth(), kind = ButtonKind.Outline, icon = Icons.build, enabled = !setupBusy)
            }
        }
    }
}

@Composable
private fun ComputerUse(cu: ComputerUseStatus, onGrant: () -> Unit) {
    val c = Theme.colors
    val ts = S.toolsPage
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (cu.ready) c.success else c.needsYou, 10.dp)
            Spacer(Modifier.width(10.dp))
            Txt(if (cu.ready) ts.ready else ts.notReady, Theme.type.headline, modifier = Modifier.weight(1f), maxLines = 1)
            cu.version?.let { Txt(it, Theme.type.code, c.secondary, maxLines = 1) }
        }
        cu.checks.forEach { ch ->
            Row(verticalAlignment = Alignment.Top) {
                Dot(checkColor(ch.status), 8.dp, Modifier.padding(top = 7.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Txt(ch.label, Theme.type.sub, maxLines = 1)
                    if (ch.message.isNotBlank()) Txt(brandFree(ch.message), Theme.type.caption, c.secondary, maxLines = 2)
                }
            }
        }
        cu.error?.let { Txt(brandFree(it), Theme.type.caption, c.errorText, maxLines = 3) }
        if (cu.canGrant) PillButton(ts.grant, onGrant, Modifier.fillMaxWidth())
    }
}

@Composable
private fun checkColor(status: String): Color = when (status) {
    "ok" -> Theme.colors.success
    "warn" -> Theme.colors.needsYou
    "err", "error", "fail" -> Theme.colors.error
    else -> Theme.colors.tertiary
}

@Composable
private fun SetupLog(running: Boolean, log: SetupStatus?) {
    val c = Theme.colors
    val ts = S.toolsPage
    val failed = !running && log?.exitCode.let { it != null && it != 0L }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(18.dp)).background(c.surface2).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(when { running -> c.working; failed -> c.error; else -> c.success }, 10.dp)
            Spacer(Modifier.width(10.dp))
            Txt(when { running -> ts.settingUp; failed -> ts.setupFailed; else -> ts.setupDone }, Theme.type.sub, maxLines = 1)
        }
        val lines = log?.lines.orEmpty().takeLast(8)
        if (lines.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Txt(lines.joinToString("\n") { brandFree(it) }, Theme.type.code.copy(fontSize = 12.sp, lineHeight = 16.sp), c.secondary)
        }
    }
}
