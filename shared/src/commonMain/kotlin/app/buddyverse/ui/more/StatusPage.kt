package app.buddyverse.ui.more

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.OneShot
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.SectionLabel
import app.buddyverse.i18n.S
import app.buddyverse.i18n.statusPage
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.MorePage
import app.buddyverse.ui.PageFrame
import app.buddyverse.ui.ltr
import app.buddyverse.ui.more.status.Act
import app.buddyverse.ui.more.status.ActionSheet
import app.buddyverse.ui.more.status.AttentionCard
import app.buddyverse.ui.more.status.ConfirmSheet
import app.buddyverse.ui.more.status.HeroCard
import app.buddyverse.ui.more.status.LogsModel
import app.buddyverse.ui.more.status.LogsSheet
import app.buddyverse.ui.more.status.Meter
import app.buddyverse.ui.more.status.NavRow
import app.buddyverse.ui.more.status.PartRow
import app.buddyverse.ui.more.status.StatusModel
import app.buddyverse.ui.more.status.UpdatesCard
import app.buddyverse.ui.more.status.bytes
import app.buddyverse.ui.more.status.detail
import app.buddyverse.ui.more.status.mood
import app.buddyverse.ui.more.status.span
import app.buddyverse.ui.more.status.warnings
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the confirm sheet is asking about. */
private enum class Ask { Restart, Update, Migrate }

/**
 * More ▸ PC status (hermes-api.md §2.6 Gateway status, logs, background actions; §2.3 Updates): the engine as a living buddy
 * with uptime, version and who's working; what needs a look (with the settings-file fix); updates (check, what's new, update
 * with live output, last receipt); each part and messaging app; the PC's processor, memory and disk; health and security
 * checks; and the logs with level, search and follow. Start and restart ask first and never run on their own.
 */
@Composable
fun StatusPage() {
    val store = LocalStore.current
    val fb = LocalFeedback.current
    val t = S.statusPage
    val u = S.current
    val m = remember(store) { StatusModel(store, fb) }
    val logs = remember(store) { LogsModel(store.api) }
    val scope = rememberCoroutineScope()
    val pc = remember { BuddySpec("pc-status", "pc", BuddyShape.Tablet, BuddyColors.palette[7]) }
    var ask by remember { mutableStateOf<Ask?>(null) }
    var output by remember { mutableStateOf<String?>(null) }
    var showLogs by remember { mutableStateOf(false) }

    // /api/status has no live event (hermes-api.md §2.6): poll while the page is open.
    LaunchedEffect(m) {
        m.loadUpdates(false)
        while (isActive) { m.refresh(); delay(5_000) }
    }

    val h = m.health
    val restart = m.runs[Act.RESTART]
    val start = m.runs[Act.START]
    val update = m.runs[Act.UPDATE]
    val migrate = m.runs[Act.MIGRATE]
    val mood = h.mood(m.reachable, m.acting)

    Box(Modifier.fillMaxSize()) {
        PageFrame(MorePage.Status.label, actions = { IconButton(Icons.refresh, t.refresh, { scope.launch { m.refresh(); m.react(OneShot.Nod) } }) }) {
            item(key = "hero") {
                HeroCard(pc, h, m.reachable, mood, m.cue, m.talkAt, restart, start,
                    onStart = { m.start(Act.START) }, onRestart = { ask = Ask.Restart }, onBuddy = { m.react(OneShot.Giggle) })
            }
            if (h != null && m.reachable) {
                val warn = h.warnings(t, u)
                if (warn.isNotEmpty() || h.configBehind) item(key = "attention") {
                    AttentionCard(warn, h.configBehind, migrate?.running == true) { ask = Ask.Migrate }
                }
            }
            item(key = "updates") {
                UpdatesCard(m.update, m.checking, m.updateError, m.receipt, update,
                    onCheck = { m.loadUpdates(true) }, onUpdate = { ask = Ask.Update }, onOpenRun = { output = Act.UPDATE })
            }
            if (h != null && m.reachable) {
                val parts = h.components.entries.toList()
                if (parts.isNotEmpty()) {
                    item(key = "parts-h") { SectionLabel(t.parts) }
                    items(parts, key = { "part:" + it.key }) { (k, v) -> PartRow(t.part(k), v.status ?: v.state, v.detail(k, t)) }
                }
                val platforms = h.platforms.entries.filter { it.value.state != "disabled" }
                if (platforms.isNotEmpty()) {
                    item(key = "plat-h") { SectionLabel(t.platforms) }
                    items(platforms, key = { "plat:" + it.key }) { (k, v) ->
                        PartRow(k.replace('_', ' ').replaceFirstChar { it.uppercase() }, v.state ?: v.status,
                            listOfNotNull(v.state?.let(t.platformState), v.reason).joinToString(" · "))
                    }
                }
            }
            m.stats?.let { st ->
                item(key = "pc-h") { SectionLabel(listOfNotNull(t.machine, st.hostname, st.osName).joinToString(" · ")) }
                item(key = "cpu") {
                    Meter(t.cpu, listOfNotNull(st.cpuPercent?.let { "${it.toInt()}%" }, st.cpuCount?.let(t.cores)).joinToString(" · "),
                        st.cpuPercent?.let { (it / 100).toFloat() })
                }
                st.memory?.let { mem ->
                    item(key = "ram") {
                        Meter(t.ram, if (mem.used != null && mem.total != null) t.usedOf(bytes(mem.used), bytes(mem.total)) else "",
                            mem.percent?.let { (it / 100).toFloat() })
                    }
                }
                st.disk?.let { d ->
                    item(key = "disk") {
                        Meter(t.disk, if (d.used != null && d.total != null) t.usedOf(bytes(d.used), bytes(d.total)) else "",
                            d.percent?.let { (it / 100).toFloat() })
                    }
                }
                h?.memory?.gatewayRssMb?.let { mb -> item(key = "rss") { Meter(t.engineRam, "${mb.toLong()} MB", null) } }
                st.uptimeSeconds?.let { up -> item(key = "pcup") { Meter(t.pcUptime, span(up, u), null) } }
            }
            item(key = "checks-h") { SectionLabel(t.checks) }
            item(key = "doctor") {
                NavRow(Icons.monitorHeart, t.doctor, m.runs[Act.DOCTOR]?.summary(t), { m.peek(Act.DOCTOR); output = Act.DOCTOR })
            }
            item(key = "audit") {
                NavRow(Icons.lock, t.audit, m.runs[Act.AUDIT]?.summary(t), { m.peek(Act.AUDIT); output = Act.AUDIT })
            }
            item(key = "logs") { NavRow(Icons.description, t.logs, null, { showLogs = true }) }
        }

        val names = mapOf(Act.DOCTOR to t.doctor, Act.AUDIT to t.audit, Act.UPDATE to t.updates, Act.RESTART to t.restart,
            Act.START to t.start, Act.MIGRATE to t.migrate)
        ActionSheet(output?.let { m.run(it) }, output?.let { names[it] }.orEmpty(), canRun = output == Act.DOCTOR || output == Act.AUDIT,
            onRun = { output?.let(m::start) }, onDismiss = { output = null })
        LogsSheet(showLogs, logs) { showLogs = false }

        val shared = h?.sharedWith.orEmpty()
        val restartLines = listOfNotNull(
            if (shared.isNotEmpty()) t.restartShared(shared.joinToString(", ") { p -> store.bots.value.firstOrNull { it.profile == p }?.name ?: ltr(p) })
            else t.restartAll,
            h?.activeAgents?.takeIf { it > 0 }?.let(t.restartBusy),
        )
        ConfirmSheet(ask == Ask.Restart, t.restartTitle, restartLines, t.restartNow, danger = true,
            onConfirm = { m.start(Act.RESTART) }, onDismiss = { if (ask == Ask.Restart) ask = null })
        ConfirmSheet(ask == Ask.Update, t.updateTitle, listOf(t.updateBody), t.updateNow, danger = true,
            onConfirm = { m.start(Act.UPDATE); output = Act.UPDATE }, onDismiss = { if (ask == Ask.Update) ask = null })
        ConfirmSheet(ask == Ask.Migrate, t.migrateTitle, listOf(t.migrateBody), t.migrate, danger = false,
            onConfirm = { m.start(Act.MIGRATE); output = Act.MIGRATE }, onDismiss = { if (ask == Ask.Migrate) ask = null })
    }
}

/** A check row's detail: what it said last time this page ran or peeked it. */
private fun app.buddyverse.ui.more.status.ActionRun.summary(t: app.buddyverse.i18n.StatusPageStrings): String? = when {
    running -> t.runningNow
    error != null -> error
    exitCode == 0 -> t.finished
    exitCode != null -> t.failedCode(exitCode ?: 0)
    else -> null
}
