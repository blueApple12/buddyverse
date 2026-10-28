package app.buddyverse.ui.more

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.LocalMotion
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.MotionLevel
import app.buddyverse.core.LinkState
import app.buddyverse.core.api.ModelUse
import app.buddyverse.core.api.series
import app.buddyverse.core.nowMillis
import app.buddyverse.design.Card
import app.buddyverse.design.Chip
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PressRow
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.insightsPage
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.MorePage
import app.buddyverse.ui.PageFrame
import app.buddyverse.ui.chat.localOffsetMs
import app.buddyverse.ui.ltr
import app.buddyverse.ui.more.insights.BotPicker
import app.buddyverse.ui.more.insights.ChartAxis
import app.buddyverse.ui.more.insights.DayChart
import app.buddyverse.ui.more.insights.InsightsHero
import app.buddyverse.ui.more.insights.InsightsMessage
import app.buddyverse.ui.more.insights.InsightsModel
import app.buddyverse.ui.more.insights.Metric
import app.buddyverse.ui.more.insights.ModelSheet
import app.buddyverse.ui.more.insights.PERIODS
import app.buddyverse.ui.more.insights.PlanCard
import app.buddyverse.ui.more.insights.ShareBar
import app.buddyverse.ui.more.insights.StatTile
import app.buddyverse.ui.more.insights.compact
import app.buddyverse.ui.more.insights.grouped
import app.buddyverse.ui.more.insights.hopNow
import app.buddyverse.ui.more.insights.money
import app.buddyverse.ui.more.insights.share
import kotlinx.coroutines.launch

/** Rows a list shows before "Show all". */
private const val TOP = 6

/**
 * More ▸ Insights (hermes-api.md §2.6 Insights, usage and analytics): spend, tokens, chats and model calls for one bot or all
 * of them over 7/30/90 days; a day chart you scrub with a finger; per-model cards (tap for every counter and what the model
 * can do); background work, top tools and skills; and the signed-in plan's credit bars. Read-only: Hermes has nothing here
 * to create or delete.
 */
@Composable
fun InsightsPage() {
    val store = LocalStore.current
    val fb = LocalFeedback.current
    val t = S.insightsPage
    val s = S.current
    val m = remember(store) { InsightsModel(store.api, fb) }
    val bots by store.bots.collectAsState()
    val link by store.link.collectAsState()
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<String?>(null) }
    var days by remember { mutableIntStateOf(30) }
    var metric by remember { mutableStateOf(Metric.Cost) }
    var hop by remember { mutableStateOf<BuddyCue?>(null) }
    var open by remember { mutableStateOf<ModelUse?>(null) }
    var allModels by remember { mutableStateOf(false) }
    var allTools by remember { mutableStateOf(false) }
    var allSkills by remember { mutableStateOf(false) }
    val visible = bots.filter { !it.hidden }
    val profiles = picked?.let { listOf(it) } ?: visible.map { it.profile }.ifEmpty { listOf("default") }
    val ready = link is LinkState.Ready

    LaunchedEffect(profiles, days, ready) { if (ready || m.data == null) m.load(profiles, days) }

    val data = m.data
    val today = remember(days) { nowMillis().let { (it + localOffsetMs(it)) / 86_400_000L } }
    val series = remember(data, today) { data?.series(today).orEmpty() }
    var selected by remember { mutableIntStateOf(-1) }
    LaunchedEffect(series.size) { selected = series.lastIndex }
    val grow = remember { Animatable(0f) }
    val motion = LocalMotion.current
    LaunchedEffect(metric, days, picked, data != null) {
        if (data == null) return@LaunchedEffect
        if (motion == MotionLevel.Off) grow.snapTo(1f) else { grow.snapTo(0f); grow.animateTo(1f, Motion.gentle()) }
    }

    val bot = picked?.let { p -> bots.firstOrNull { it.profile == p } }
    val mood = when {
        m.unsupported -> Mood.Sleeping
        data == null && m.failed -> Mood.Error
        data == null || m.loading -> Mood.Thinking
        data.totals.apiCalls == 0L -> Mood.Sleeping
        else -> Mood.Idle
    }
    val headline = data?.let { t.headline(ltr(money(it.totals.cost)), days) } ?: t.period(days)
    val sub = data?.let { d ->
        listOfNotNull(t.chatsShort(ltr(grouped(d.totals.sessions))), d.messages?.let { t.messages(ltr(grouped(it))) }).joinToString(" · ")
    }
    val cue = listOfNotNull(m.cue, hop).maxByOrNull { it.serial }

    Box(Modifier.fillMaxSize()) {
        PageFrame(MorePage.Insights.label, actions = {
            IconButton(Icons.refresh, t.refresh, { scope.launch { m.load(profiles, days, loud = true) } }, enabled = !m.loading)
        }) {
            item(key = "hero") { InsightsHero(bot, visible.take(3), mood, cue, m.talkAt, headline, sub) }
            item(key = "bots") {
                BotPicker(visible, picked, cue) { p -> if (p != picked) { picked = p; hop = hopNow(); allModels = false; allTools = false; allSkills = false } }
            }
            item(key = "periods") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PERIODS.forEach { d -> Chip(t.period(d), d == days, { if (d != days) { days = d; hop = hopNow() } }) }
                }
            }
            when {
                m.unsupported -> item(key = "msg") { InsightsMessage(s.unsupported) }
                data == null && m.failed -> item(key = "msg") { InsightsMessage(s.error, s.retry) { scope.launch { m.load(profiles, days, loud = true) } } }
                data == null -> Unit
                else -> {
                    val tot = data.totals
                    item(key = "tiles") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                StatTile(t.spent, money(tot.cost), if (tot.billed > 0) t.billed(ltr(money(tot.billed))) else null,
                                    metric == Metric.Cost, { metric = Metric.Cost }, Modifier.weight(1f))
                                StatTile(t.tokens, compact(tot.tokens), null, metric == Metric.Tokens, { metric = Metric.Tokens }, Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                StatTile(t.chats, grouped(tot.sessions), null, metric == Metric.Chats, { metric = Metric.Chats }, Modifier.weight(1f))
                                StatTile(t.calls, grouped(tot.apiCalls), null, metric == Metric.Calls, { metric = Metric.Calls }, Modifier.weight(1f))
                            }
                        }
                    }
                    item(key = "chart") {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Txt(t.byDay, Theme.type.caption, Theme.colors.secondary, maxLines = 1)
                            Spacer(Modifier.height(12.dp))
                            DayChart(series, metric, selected, grow.value, { selected = it })
                            ChartAxis(series, metric, selected)
                        }
                    }
                    if (tot.apiCalls == 0L && data.models.isEmpty()) item(key = "empty") { InsightsMessage(t.noUse) }
                    m.plan?.let { p -> item(key = "plan") { PlanCard(p, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) } }

                    if (data.models.isNotEmpty()) {
                        item(key = "models-h") { SectionLabel(t.models) }
                        val rows = if (allModels) data.models else data.models.take(TOP)
                        items(rows, key = { "model:" + it.provider + "/" + it.model }) { mu ->
                            ModelRow(mu, tot.tokens, Modifier.animateItem(fadeInSpec = Motion.fade(), placementSpec = Motion.smooth(), fadeOutSpec = Motion.fade())) { open = mu }
                        }
                        if (data.models.size > TOP) item(key = "models-more") { MoreToggle(allModels) { allModels = !allModels } }
                    }
                    if (data.tasks.isNotEmpty()) {
                        item(key = "tasks-h") { SectionLabel(t.background) }
                        items(data.tasks, key = { "task:" + it.task }) { task ->
                            CountRow(
                                t.task(task.task),
                                listOf(t.tokensShort(ltr(compact(task.use.tokens))), ltr(money(task.use.cost)), task.models.joinToString(", ") { ltr(it) })
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                share(task.use.tokens.toDouble(), data.tasks.maxOf { it.use.tokens }.toDouble()),
                            )
                        }
                    }
                    if (data.tools.isNotEmpty()) {
                        item(key = "tools-h") { SectionLabel(t.tools) }
                        val top = data.tools.maxOf { it.count }.toDouble()
                        items(if (allTools) data.tools else data.tools.take(TOP), key = { "tool:" + it.tool }) { u ->
                            CountRow(ltr(u.tool), t.timesShort(ltr(grouped(u.count))), share(u.count.toDouble(), top), mono = true)
                        }
                        if (data.tools.size > TOP) item(key = "tools-more") { MoreToggle(allTools) { allTools = !allTools } }
                    }
                    if (data.skills.isNotEmpty()) {
                        item(key = "skills-h") { SectionLabel(t.skills) }
                        val top = data.skills.maxOf { it.total }.toDouble()
                        items(if (allSkills) data.skills else data.skills.take(TOP), key = { "skill:" + it.skill }) { u ->
                            CountRow(ltr(u.skill), t.skillUse(ltr(grouped(u.loads)), ltr(grouped(u.edits))), share(u.total.toDouble(), top), mono = true)
                        }
                        if (data.skills.size > TOP) item(key = "skills-more") { MoreToggle(allSkills) { allSkills = !allSkills } }
                    }
                }
            }
        }
        ModelSheet(open, data?.totals?.tokens ?: 0L) { open = null }
    }
}

/** 72dp model row: name (LTR), provider and tokens, cost at the end, its share of the period's tokens under it. */
@Composable
private fun ModelRow(mu: ModelUse, total: Long, modifier: Modifier, onOpen: () -> Unit) {
    val t = S.insightsPage
    val c = Theme.colors
    PressRow(onOpen, modifier, minHeight = 72.dp, label = mu.model) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(mu.model, Theme.type.headline.copy(textDirection = TextDirection.Ltr), modifier = Modifier.weight(1f), maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Txt(ltr(money(mu.use.cost)), Theme.type.callout, maxLines = 1)
            }
            Txt(
                listOf(mu.provider, t.tokensShort(ltr(compact(mu.use.tokens))), t.chatsShort(ltr(grouped(mu.use.sessions)))).filter { it.isNotBlank() }.joinToString(" · "),
                Theme.type.sub, c.secondary, maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            ShareBar(share(mu.use.tokens.toDouble(), total.toDouble()))
        }
    }
}

/** A read-only counter row with a bar relative to the biggest one in its list. */
@Composable
private fun CountRow(name: String, detail: String, fraction: Float, mono: Boolean = false) {
    val c = Theme.colors
    Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Txt(name, if (mono) Theme.type.callout.copy(textDirection = TextDirection.Ltr) else Theme.type.callout, maxLines = 1)
        Txt(detail, Theme.type.sub, c.secondary, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        ShareBar(fraction, c.working.copy(alpha = .7f))
    }
}

@Composable
private fun MoreToggle(expanded: Boolean, onClick: () -> Unit) {
    val t = S.insightsPage
    PressRow(onClick, minHeight = 56.dp, label = if (expanded) t.showLess else t.showAll) {
        Txt(if (expanded) t.showLess else t.showAll, Theme.type.button, Theme.colors.accent, Modifier.weight(1f), maxLines = 1)
    }
}
