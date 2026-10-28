package app.buddyverse.ui.routines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Mood
import app.buddyverse.core.api.cronBlueprints
import app.buddyverse.core.api.cronInstantiate
import app.buddyverse.core.api.routineError
import app.buddyverse.core.bool
import app.buddyverse.core.list
import app.buddyverse.core.obj
import app.buddyverse.core.str
import app.buddyverse.design.Chip
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Sheet
import app.buddyverse.design.Sound
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.routines
import app.buddyverse.ui.LocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Template gallery (`/api/cron/blueprints`): pick one, fill its few fields, create. Shown while [profile] != null. */
@Composable
fun RoutineTemplates(profile: String?, onDismiss: () -> Unit, onCreated: (JsonObject?) -> Unit) {
    val last = remember { arrayOfNulls<String>(1) }
    if (profile != null) last[0] = profile
    val p = last[0]
    Sheet(profile != null, onDismiss, title = S.routines.templates, heightFraction = .9f) {
        if (p != null) key(p) { TemplatesBody(p, onCreated) }
    }
}

@Composable
private fun ColumnScope.TemplatesBody(profile: String, onCreated: (JsonObject?) -> Unit) {
    val store = LocalStore.current
    val s = S.current
    val c = Theme.colors
    val bots by store.bots.collectAsState()
    val spec = ownerSpec(profile, bots)
    var list by remember { mutableStateOf<List<JsonObject>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(profile) {
        list = try { store.api.cronBlueprints(profile) } catch (e: CancellationException) { throw e } catch (e: Exception) { failed = e.routineError(); emptyList() }
    }
    val bp = picked
    val l = list
    when {
        bp != null -> TemplateForm(bp, profile, onBack = { picked = null }, onCreated = onCreated)
        l == null -> BuddyNote(spec, Mood.Thinking, s.loading, Modifier.weight(1f))
        l.isEmpty() -> BuddyNote(spec, if (failed != null) Mood.Error else Mood.Idle, failed ?: s.nothingHere, Modifier.weight(1f))
        else -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(l) { b ->
                PressRow({ picked = b }, minHeight = 72.dp, label = b.str("title")) {
                    Column(Modifier.weight(1f)) {
                        Txt(b.str("title") ?: b.str("key").orEmpty(), Theme.type.headline, maxLines = 1)
                        b.str("description")?.takeIf { it.isNotBlank() }?.let { Txt(it, Theme.type.caption, c.secondary, maxLines = 2) }
                    }
                    Icon(Icons.chevronRight, c.tertiary, mirror = true)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.TemplateForm(bp: JsonObject, profile: String, onBack: () -> Unit, onCreated: (JsonObject?) -> Unit) {
    val store = LocalStore.current
    val s = S.current
    val c = Theme.colors
    val fb = LocalFeedback.current
    val r = S.routines
    val bots by store.bots.collectAsState()
    val fields = remember(bp) { bp.list("fields").mapNotNull { it.obj }.filter { it.str("name") != null } }
    val values = remember(bp) {
        mutableStateMapOf<String, String>().apply {
            fields.forEach { put(it.str("name")!!, it.str("default")?.takeIf { d -> d.isNotBlank() } ?: if (it.str("type") == "time") "09:00" else "") }
        }
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val ready = !saving && fields.all { it.bool("optional") == true || !values[it.str("name")!!].isNullOrBlank() }

    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(Icons.arrowBack, s.back, onBack, mirror = true)
        Txt(bp.str("title") ?: bp.str("key").orEmpty(), Theme.type.headline, modifier = Modifier.weight(1f), maxLines = 1)
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        bp.str("description")?.takeIf { it.isNotBlank() }?.let { Txt(it, Theme.type.callout, c.secondary) }
        fields.forEach { f ->
            val n = f.str("name")!!
            val label = f.str("label") ?: n
            Label(label)
            f.str("help")?.takeIf { it.isNotBlank() }?.let { Txt(it, Theme.type.caption, c.secondary, Modifier.padding(bottom = 6.dp), maxLines = 3) }
            val type = f.str("type")
            val options = f.list("options").mapNotNull { (it as? JsonPrimitive)?.content ?: it.obj.str("value") }
            when {
                // `weekdays` options are the server's WEEKDAY_PRESETS keys; `deliver` options are delivery tokens.
                (type == "enum" || type == "weekdays") && options.isNotEmpty() -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { o ->
                        val text = when {
                            type == "weekdays" -> when (o) { "everyday" -> s.everyDay; "weekdays" -> s.weekdays; "weekends" -> r.weekends; else -> o }
                            n == "deliver" -> deliverLabel(o, null, profile, bots, r)
                            else -> o
                        }
                        Chip(text, values[n] == o, { values[n] = o })
                    }
                }
                f.str("type") == "time" -> {
                    val (h, m) = wallTime("2000-01-01T${values[n].orEmpty().padStart(5, '0')}") ?: (9 to 0)
                    TimeWheel(h, m, { hh, mm -> values[n] = hhmm(hh, mm) })
                }
                else -> Field(values[n].orEmpty(), { values[n] = it }, label)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    error?.let { Txt(it, Theme.type.caption, c.errorText, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), maxLines = 3) }
    PillButton(s.create, {
        saving = true; error = null
        store.scope.launch {
            try {
                val job = store.api.cronInstantiate(profile, bp.str("key").orEmpty(), values.toMap())
                store.refreshRoutines()
                fb.haptic(Haptic.Confirm); fb.sound(Sound.Pop)
                onCreated(job)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = e.routineError(); fb.haptic(Haptic.Reject)
            }
            saving = false
        }
    }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), enabled = ready)
}
