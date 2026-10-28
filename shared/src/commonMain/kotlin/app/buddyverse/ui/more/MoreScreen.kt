package app.buddyverse.ui.more

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.LocalMotion
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.MotionLevel
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.AppVersion
import app.buddyverse.core.Bot
import app.buddyverse.core.Hermes
import app.buddyverse.core.LinkState
import app.buddyverse.core.RestError
import app.buddyverse.core.api.Engine
import app.buddyverse.core.api.PcHealth
import app.buddyverse.core.decodeAs
import app.buddyverse.i18n.EnglishStrings
import app.buddyverse.i18n.StatusPageStrings
import app.buddyverse.ui.more.status.warnings
import app.buddyverse.core.api.engineStart
import app.buddyverse.core.api.engineStop
import app.buddyverse.core.api.mcpCount
import app.buddyverse.core.api.pluginsOn
import app.buddyverse.core.api.projectsCount
import app.buddyverse.core.api.skillsOn
import app.buddyverse.core.api.toEngine
import app.buddyverse.core.api.toolsetsOn
import app.buddyverse.core.api.webhooksCount
import app.buddyverse.core.nowMillis
import app.buddyverse.design.BackHandler
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Card
import app.buddyverse.design.Dot
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.Radius
import app.buddyverse.design.Sound
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.more
import app.buddyverse.ui.LocalNav
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.MorePage
import app.buddyverse.ui.Screen
import app.buddyverse.ui.ScreenHeader
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.ltr
import app.buddyverse.ui.parseIsoMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import app.buddyverse.i18n.Strings
import app.buddyverse.ui.modelLabel

/** More tab (design §3.7): engine card + 2-column tiles, one per [MorePage]. */
@Composable
fun MoreScreen() {
    val nav = LocalNav.current
    val store = LocalStore.current
    val bots by store.bots.collectAsState()
    val link by store.link.collectAsState()
    val home = bots.firstOrNull { it.isDefault } ?: bots.firstOrNull()
    val profile = home?.profile ?: "default"
    val scope = rememberCoroutineScope()
    val es = remember(store) { HubEngineState(store.api, scope) }
    val counts = remember { mutableStateMapOf<MorePage, Int>() }
    val grid = rememberLazyGridState()
    val ready = link is LinkState.Ready
    val covered = nav.listHidden

    // /api/status has no live event (hermes-api.md §2.6): poll it while the hub is on screen.
    LaunchedEffect(es) { while (isActive) { if (!nav.listHidden) es.refresh(); delay(5_000) } }
    // Counts again whenever the hub comes back from a page (a skill may have been switched off there).
    LaunchedEffect(profile, ready, covered) {
        if (!ready || covered) return@LaunchedEffect
        COUNTED.forEach { p -> launch { quietly { store.api.countFor(p, profile) }?.let { counts[p] = it } } }
    }
    LaunchedEffect(nav.reselect) { if (nav.reselect > 0) grid.animateScrollToItem(0) }

    val start = remember { nowMillis() }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(S.current.tabMore)
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxWidth().weight(1f), grid,
            // Clears the floating tab bar: 68 tall + 8 above the nav bar (design §2.1).
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = navBottom + 68.dp + 8.dp + 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "engine", span = { GridItemSpan(maxLineSpan) }) { EngineCard(es, home, link, Modifier.padding(bottom = 4.dp)) }
            itemsIndexed(MorePage.entries, key = { _, p -> p.name }) { i, p ->
                Tile(p, tileLine(p, counts[p], es.engine, home), if (nowMillis() - start < 1_000) i else -1) { nav.push(Screen.More(p)) }
            }
            item(key = "version", span = { GridItemSpan(maxLineSpan) }) { VersionLine() }
        }
    }
}

/** About: this build's version, the same generated label on phone, PC and web (so they can be compared). Tap = copy. */
@Composable
private fun VersionLine() {
    val fb = LocalFeedback.current
    val toasts = LocalToasts.current
    val clip = LocalClipboardManager.current
    val copied = S.more.copied
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
        Txt(ltr("Buddyverse ${AppVersion.LABEL}"), Theme.type.caption, Theme.colors.tertiary,
            Modifier.clip(RoundedCornerShape(8.dp)).squish({
                clip.setText(AnnotatedString(AppVersion.LABEL)); fb.haptic(Haptic.Confirm); toasts.show(ToastData(copied))
            }, scale = .97f).padding(horizontal = 10.dp, vertical = 8.dp), maxLines = 1)
    }
}

// ------------------------------------------------------------------ engine

private enum class HubConfirm { Stop, Busy }

/** Engine status plus the on/off switch. The toggle flips at touch (optimistic); the gateway follows. */
@Stable
private class HubEngineState(private val api: Hermes, private val scope: CoroutineScope) {
    var engine by mutableStateOf<Engine?>(null)
    var unreachable by mutableStateOf(false)
    /** Where a running start/stop is heading; null when idle. */
    var target by mutableStateOf<Boolean?>(null)
    /** The inline stop confirm is open; [ask] says which one (kept while it animates out). */
    var asking by mutableStateOf(false)
    var ask by mutableStateOf(HubConfirm.Stop)
    var cue by mutableStateOf<BuddyCue?>(null)
    var now by mutableLongStateOf(nowMillis())
    private var serial = 0L

    suspend fun refresh() {
        now = nowMillis()
        try {
            api.status()?.let { o ->
                // The tile uses the status page's own "needs a look" rule, so the two never disagree.
                val calm = runCatching { o.decodeAs<PcHealth>().warnings(StatusPageStrings(), EnglishStrings).isEmpty() }.getOrNull()
                engine = o.toEngine().let { e -> e.copy(allGood = calm ?: e.healthy) }
            }
            unreachable = false
        }
        catch (e: CancellationException) { throw e } catch (_: Exception) { unreachable = true }
    }

    fun shot(s: OneShot) { cue = BuddyCue(++serial, s) }

    fun switch(on: Boolean, interrupt: Boolean, onFail: () -> Unit) {
        if (target != null) return
        asking = false
        target = on
        scope.launch {
            try { if (on) api.engineStart() else api.engineStop(interrupt) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                target = null
                // 409 on stop = live sessions refused it (no profile is passed, so it is never the multiplexer refusal).
                if (!on && e is RestError && e.status == 409) { ask = HubConfirm.Busy; asking = true } else onFail()
                return@launch
            }
            // `hermes gateway start|stop` is a detached spawn: watch /api/status flip (Desktop polls 1.2 s × 18; Windows starts slower).
            repeat(30) {
                delay(1_200)
                refresh()
                if (engine?.running == on) { target = null; shot(if (on) OneShot.Arrive else OneShot.Yawn); return@launch }
            }
            target = null
            onFail()
        }
    }
}

@Composable
private fun EngineCard(es: HubEngineState, home: Bot?, link: LinkState, modifier: Modifier = Modifier) {
    val s = S.current
    val m = S.more
    val c = Theme.colors
    val fb = LocalFeedback.current
    val toasts = LocalToasts.current
    val nav = LocalNav.current
    val store = LocalStore.current
    val clip = LocalClipboardManager.current
    val e = es.engine
    val trouble = e != null && e.running && (!e.healthy || e.state in TROUBLE_STATES)
    val switching = es.target != null
    val onFail = { fb.haptic(Haptic.Reject); fb.sound(Sound.Bonk); toasts.show(ToastData(m.engineFailed)) }
    BackHandler(es.asking && !nav.covered) { es.asking = false }

    val mood = when {
        switching -> Mood.Thinking
        e == null -> if (es.unreachable) Mood.Offline else Mood.Idle
        !e.running -> Mood.Sleeping
        trouble -> Mood.Error
        e.activeAgents > 0 -> Mood.Working
        else -> Mood.Idle
    }
    val status = when {
        es.target == true -> m.engineStarting
        es.target == false -> m.engineStopping
        e == null -> if (es.unreachable) m.linkOffline else ""
        !e.running -> m.engineStopped
        e.state == "draining" -> m.engineDraining
        trouble -> m.engineTrouble
        else -> listOfNotNull(
            s.engineRunning,
            uptime(e.sinceIso, es.now, s)?.let(::ltr),
            e.activeAgents.takeIf { it > 0 }?.let(m.working),
        ).joinToString(" · ")
    }
    val dot = when {
        switching -> c.working
        e == null -> if (es.unreachable) c.error else c.tertiary
        !e.running -> c.tertiary
        trouble -> c.needsYou
        else -> c.success
    }
    val accent = c.accent
    val fallback = remember(accent) { BuddySpec("engine", s.engine, BuddyShape.Blob, accent) }
    val spec = home?.spec ?: fallback

    Card(modifier.fillMaxWidth(), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Buddy(spec, mood, 52.dp, cue = es.cue, onTap = { fb.haptic(Haptic.Press) })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(s.engine, Theme.type.headline, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(dot, switching)
                    Spacer(Modifier.width(6.dp))
                    Txt(status, Theme.type.caption, c.secondary, maxLines = 1)
                }
            }
            val checked = es.target ?: (e?.running == true)
            // The engine serves this very link: off is allowed only on the PC itself (a phone could never switch it back on).
            val onPc = store.savedLink.let { it.contains("://127.0.0.1") || it.contains("://localhost") || it.contains("://[::1]") }
            Toggle(
                checked,
                { on ->
                    when {
                        on -> es.switch(true, false, onFail)
                        !onPc -> { fb.haptic(Haptic.Reject); toasts.show(ToastData(m.engineOffPhone)) }
                        else -> { es.ask = HubConfirm.Stop; es.asking = true; es.shot(OneShot.Startle) }
                    }
                },
                if (checked) m.engineOff else m.engineOn,
                enabled = e != null && !switching,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(14.dp))
            Icon(Icons.link, c.secondary, 22.dp)
            Spacer(Modifier.width(26.dp))
            val (word, wordColor) = when (link) {
                is LinkState.Ready -> m.linkActive to c.success
                is LinkState.Connecting -> m.linkConnecting to c.working
                else -> m.linkOffline to c.error
            }
            Txt(s.phoneLink, Theme.type.sub, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Dot(wordColor, 6.dp)
            Spacer(Modifier.width(6.dp))
            Txt(word, Theme.type.sub, c.secondary, Modifier.weight(1f), maxLines = 1)
            val saved = store.savedLink
            IconButton(Icons.contentCopy, m.copyLink, {
                clip.setText(AnnotatedString(saved))
                fb.haptic(Haptic.Confirm)
                toasts.show(ToastData(m.copied))
            }, tint = c.secondary, enabled = saved.isNotEmpty())
        }
        // Stop is destructive: confirm inline (no overlay under the tab bar). Closes by Cancel or back.
        AnimatedVisibility(
            es.asking,
            enter = expandVertically(Motion.smooth()) + fadeIn(Motion.fade()),
            exit = shrinkVertically(Motion.smooth()) + fadeOut(Motion.fade()),
        ) {
            val busy = es.ask == HubConfirm.Busy
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(Radius.row.dp)).background(c.surface2).padding(16.dp)) {
                Txt(if (busy) m.busyTitle else m.stopTitle, Theme.type.headline, maxLines = 2)
                Spacer(Modifier.height(2.dp))
                Txt(if (busy) m.busyBody else m.stopBody, Theme.type.sub, c.secondary)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(s.cancel, { es.asking = false }, Modifier.weight(1f), ButtonKind.Quiet, height = 48.dp)
                    PillButton(
                        if (busy) m.stopAnyway else m.stop,
                        { es.switch(false, busy, onFail) },
                        Modifier.weight(1f), ButtonKind.Danger, height = 48.dp, haptic = Haptic.Reject,
                    )
                }
            }
        }
    }
}

/** Status dot that breathes while the engine is switching. */
@Composable
private fun PulseDot(color: androidx.compose.ui.graphics.Color, pulsing: Boolean) {
    if (!pulsing || LocalMotion.current == MotionLevel.Off) { Dot(color); return }
    val a by rememberInfiniteTransition().animateFloat(1f, .25f, infiniteRepeatable(tween(520), RepeatMode.Reverse))
    Dot(color, modifier = Modifier.graphicsLayer { alpha = a })
}

// ------------------------------------------------------------------ tiles

/** 104 tall, radius 24: 28 icon, label 15 semibold, one 13 line (design §3.7). [index] ≥ 0 pops it in, staggered, on first show. */
@Composable
private fun Tile(page: MorePage, line: String?, index: Int, onClick: () -> Unit) {
    val c = Theme.colors
    val label = page.label
    val motion = LocalMotion.current
    // Decided once: a later recomposition (index -1 after the first second) must not cancel a pop half-way.
    val appear = remember { Animatable(if (index >= 0 && motion != MotionLevel.Off) 0f else 1f) }
    LaunchedEffect(Unit) { if (appear.value < 1f) { delay(index.coerceAtLeast(0) * 22L); appear.animateTo(1f, Motion.bouncy()) } }
    val shape = RoundedCornerShape(Radius.card.dp)
    Column(
        Modifier.fillMaxWidth().height(104.dp)
            .graphicsLayer { val k = appear.value; alpha = k.coerceIn(0f, 1f); scaleX = .86f + .14f * k; scaleY = .86f + .14f * k }
            .squish(onClick, scale = .96f, label = label)
            .shadow(if (c.dark) 0.dp else 8.dp, shape, ambientColor = c.shadow, spotColor = c.shadow)
            .clip(shape).background(c.surface).padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(page.icon, c.accent, 28.dp)
        Column {
            Txt(label, Theme.type.sub.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            Txt(line.orEmpty(), Theme.type.caption, c.secondary, maxLines = 1)
        }
    }
}

/** The tile's 13sp line: a count, the default bot's model, or a health word. Null = nothing to say. */
@Composable
private fun tileLine(p: MorePage, count: Int?, e: Engine?, home: Bot?): String? {
    val s = S.current
    val m = S.more
    return when (p) {
        MorePage.Skills, MorePage.Tools, MorePage.Plugins -> count?.let(m.on)
        MorePage.Mcp, MorePage.Projects, MorePage.Webhooks -> count?.toString()
        MorePage.Models -> modelLabel(home?.model)?.let(::ltr)
        MorePage.Messaging -> e?.let { if (it.platformsConfigured == 0) s.notConfigured else "${it.platformsConnected}/${it.platformsConfigured} ${s.connected}" }
        MorePage.Status -> e?.let { if (it.running && it.allGood) m.allGood else m.notAllGood }
        else -> null
    }
}

private val COUNTED = listOf(MorePage.Skills, MorePage.Tools, MorePage.Mcp, MorePage.Plugins, MorePage.Projects, MorePage.Webhooks)

private suspend fun Hermes.countFor(p: MorePage, profile: String): Int? = when (p) {
    MorePage.Skills -> skillsOn(profile)
    MorePage.Tools -> toolsetsOn(profile)
    MorePage.Mcp -> mcpCount(profile)
    MorePage.Plugins -> pluginsOn(profile)
    MorePage.Projects -> projectsCount(profile)
    MorePage.Webhooks -> webhooksCount(profile)
    else -> null
}

private val TROUBLE_STATES = setOf("fatal", "startup_failed", "degraded")

/** "12m", "3h 12m", "2d 4h" since an ISO time (Western digits, design §9.6). */
private fun uptime(sinceIso: String?, now: Long, s: Strings): String? {
    val t = sinceIso?.let(::parseIsoMillis) ?: return null
    val min = ((now - t) / 60_000).coerceAtLeast(0)
    return when {
        min < 60 -> s.unit(min, "m")
        min < 1_440 -> s.unit(min / 60, "h") + " " + s.unit(min % 60, "m")
        else -> s.unit(min / 1_440, "d") + " " + s.unit((min % 1_440) / 60, "h")
    }
}

private suspend fun <T> quietly(block: suspend () -> T): T? =
    try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
