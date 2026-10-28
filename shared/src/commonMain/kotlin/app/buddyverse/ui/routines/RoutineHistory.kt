package app.buddyverse.ui.routines

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.core.api.routineError
import app.buddyverse.core.bool
import app.buddyverse.core.double
import app.buddyverse.core.long
import app.buddyverse.core.nowMillis
import app.buddyverse.core.str
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.PressRow
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.Strings
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.ago
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** Run history of one routine (`GET /api/cron/jobs/{id}/runs`); an agent run opens as its chat. Shown while [job] != null. */
@Composable
fun RoutineHistory(job: JsonObject?, onDismiss: () -> Unit, onOpen: (profile: String, sessionId: String) -> Unit) {
    val last = remember { arrayOfNulls<JsonObject>(1) }
    if (job != null) last[0] = job
    val j = last[0]
    val s = S.current
    Sheet(job != null, onDismiss, title = j?.let { "${jobName(it, s)} · ${s.history}" } ?: s.history, heightFraction = .8f) {
        if (j != null) key(j.str("id")) { HistoryBody(j, onOpen) }
    }
}

@Composable
private fun ColumnScope.HistoryBody(job: JsonObject, onOpen: (String, String) -> Unit) {
    val store = LocalStore.current
    val s = S.current
    val bots by store.bots.collectAsState()
    val id = job.str("id") ?: return
    val profile = job.str("profile") ?: "default"
    val spec = ownerSpec(profile, bots)
    var runs by remember { mutableStateOf<List<JsonObject>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    // Refetch when a new run lands; keep polling while one is still going (hermes-api.md §2.5 polling).
    LaunchedEffect(id, job.str("last_run_at")) {
        while (true) {
            try { runs = store.api.cronRuns(id, profile); failed = null } catch (e: CancellationException) { throw e } catch (e: Exception) {
                failed = e.routineError(); if (runs == null) runs = emptyList()
            }
            if (runs.orEmpty().none(::isLive)) break
            delay(4_000)
        }
    }
    val list = runs
    when {
        list == null -> BuddyNote(spec, Mood.Thinking, s.loading, Modifier.weight(1f))
        list.isEmpty() -> BuddyNote(spec, if (failed != null) Mood.Error else Mood.Idle, failed ?: s.noRuns, Modifier.weight(1f))
        else -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(list, key = { it.str("id") ?: it.hashCode().toString() }) { run -> RunRow(run, profile, spec, s, onOpen) }
        }
    }
}

@Composable
private fun RunRow(run: JsonObject, profile: String, spec: BuddySpec, s: Strings, onOpen: (String, String) -> Unit) {
    val c = Theme.colors
    val sid = run.str("id")
    val live = isLive(run)
    val script = run.str("source") == "cron_output"
    // Writable only when it ended or the scheduler still owns it; a stuck one is a zombie (hermes-api.md §2.5).
    val openable = sid != null && !script && (run.double("ended_at") != null || run.bool("scheduler_owned") == true)
    val title = run.str("title").orEmpty()
    val bad = script && (title.startsWith("FAIL", ignoreCase = true) || title.startsWith("ERROR", ignoreCase = true))
    var expanded by remember { mutableStateOf(false) }
    val now = nowMillis()
    val started = run.double("started_at") ?: 0.0
    val ended = run.double("ended_at")
    val meta = listOfNotNull(
        ago(started, now, s).takeIf { it.isNotEmpty() }?.let { if (it == s.justNow) it else s.ago(it) },
        if (live) s.live else ended?.let { duration(it - started, s) },
        run.long("message_count")?.takeIf { it > 0 }?.let { s.messagesCount(it.toInt()) },
    ).joinToString(" · ")
    PressRow({ if (openable) onOpen(profile, sid!!) else { expanded = !expanded } }, minHeight = 72.dp, label = title.ifBlank { meta }) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            when {
                live -> Buddy(spec, Mood.Working, 40.dp, interactive = false)
                bad -> Icon(Icons.error, c.errorText)
                openable -> Icon(Icons.chat, c.secondary)
                else -> Icon(Icons.description, c.secondary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(meta, Theme.type.caption, if (bad) c.errorText else c.secondary, maxLines = 1)
            Txt(run.str("preview")?.takeIf { it.isNotBlank() } ?: title.ifBlank { s.untitled }, Theme.type.callout, maxLines = if (expanded) 12 else 2)
        }
        if (openable) Icon(Icons.chevronRight, c.tertiary, mirror = true)
    }
}

private fun isLive(run: JsonObject): Boolean = run.double("ended_at") == null && run.bool("scheduler_owned") == true
