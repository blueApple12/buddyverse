package app.buddyverse.ui.routines

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.Bot
import app.buddyverse.core.api.cronDeliveryTargets
import app.buddyverse.core.api.routineError
import app.buddyverse.core.api.saveRoutineFull
import app.buddyverse.core.bool
import app.buddyverse.core.list
import app.buddyverse.core.nowMillis
import app.buddyverse.core.str
import app.buddyverse.design.Chip
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.Sheet
import app.buddyverse.design.Sound
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.RoutinesStrings
import app.buddyverse.i18n.S
import app.buddyverse.i18n.routines
import app.buddyverse.ui.LocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/** One opening of the editor: [job] null = a new routine for [profile]. A new [n] starts a fresh draft. */
data class EditTarget(val job: JsonObject?, val profile: String, val n: Long)

/** Routine editor sheet (design §3.6): What · When (preset chips + time wheel, cron under Advanced) · Who (buddy tiles). */
@Composable
fun RoutineEditor(
    target: EditTarget?,
    onDismiss: () -> Unit,
    onSaved: (JsonObject?) -> Unit,
    onHistory: (JsonObject) -> Unit,
    onDelete: (JsonObject) -> Unit,
    onTemplates: (String) -> Unit,
) {
    val last = remember { arrayOfNulls<EditTarget>(1) }
    if (target != null) last[0] = target
    val t = last[0]
    val s = S.current
    Sheet(target != null, onDismiss, title = t?.job?.let { jobName(it, s) } ?: s.newRoutine, heightFraction = .94f) {
        if (t != null) key(t.n) { EditorBody(t, onSaved, onHistory, onDelete, onTemplates) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.EditorBody(
    t: EditTarget,
    onSaved: (JsonObject?) -> Unit,
    onHistory: (JsonObject) -> Unit,
    onDelete: (JsonObject) -> Unit,
    onTemplates: (String) -> Unit,
) {
    val store = LocalStore.current
    val s = S.current
    val r = S.routines
    val c = Theme.colors
    val fb = LocalFeedback.current
    val job = t.job
    val routines by store.routines.collectAsState()
    val bots by store.bots.collectAsState()
    // The PC's UTC offset from its jobs; with none yet, the phone's own (they share a time zone almost always).
    val offset = remember(routines.jobs) { pcOffsetMinutes(routines.jobs) ?: (app.buddyverse.ui.chat.localOffsetMs(nowMillis()) / 60_000).toInt() }

    val initialWhen = remember { parseWhen(job) }
    val initialDeliver = remember { job.str("deliver")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() }
    val initialSkills = remember { job.list("skills").mapNotNull { (it as? JsonPrimitive)?.content }.toSet() }
    val initialModel = remember { job.str("model").orEmpty() }
    var name by remember { mutableStateOf(job.str("name").orEmpty()) }
    var prompt by remember { mutableStateOf(job.str("prompt").orEmpty()) }
    var profile by remember { mutableStateOf(job.str("profile") ?: t.profile) }
    var w by remember { mutableStateOf(initialWhen) }
    var deliver by remember { mutableStateOf(initialDeliver ?: emptySet()) }
    var deliverTouched by remember { mutableStateOf(job != null) }
    var skills by remember { mutableStateOf(initialSkills) }
    var model by remember { mutableStateOf(initialModel) }
    var advanced by remember { mutableStateOf(initialWhen.kind == Kind.Custom) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var skillQuery by remember { mutableStateOf("") }
    var targets by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var allSkills by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(profile) {
        targets = quiet { store.api.cronDeliveryTargets(profile) }.orEmpty()
        if (!deliverTouched) deliver = setOf(defaultDeliver(targets, profile))
        allSkills = quiet { store.api.skills(profile).mapNotNull { it.str("name") } }.orEmpty()
    }

    val canSave = !saving && w.valid && (prompt.isNotBlank() || skills.isNotEmpty())
    fun save() {
        if (!canSave) return
        saving = true; error = null
        val schedule = w.toSchedule(nowMillis(), offset ?: 0)
        val fields = buildMap<String, Any?> {
            if (job == null) {
                put("prompt", prompt.trim()); put("schedule", schedule)
                if (name.isNotBlank()) put("name", name.trim())
                put("deliver", deliver.ifEmpty { setOf("local") }.joinToString(","))
                if (skills.isNotEmpty()) put("skills", skills.toList())
                if (model.isNotBlank()) put("model", model.trim())
            } else {
                put("name", name.trim()); put("prompt", prompt.trim())
                // Re-sending an unchanged schedule re-parses it: a past one-shot would be refused (400).
                if (w != initialWhen) put("schedule", schedule)
                if (deliver.isNotEmpty() && deliver != initialDeliver) put("deliver", deliver.joinToString(","))
                if (skills != initialSkills) put("skills", skills.toList())
                if (model.trim() != initialModel) put("model", model.trim())
            }
        }
        store.scope.launch {
            val res = store.saveRoutineFull(job, profile, fields)
            saving = false
            res.onSuccess { fb.haptic(Haptic.Confirm); fb.sound(Sound.Pop); onSaved(it) }
                .onFailure { error = it.routineError(); fb.haptic(Haptic.Reject); fb.sound(Sound.Bonk) }
        }
    }

    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        if (job == null) ActionRow(Icons.autoAwesome, r.fromTemplate, c.accent) { onTemplates(profile) }
        // Why the last run failed, in the server's words (the card only says "last run failed").
        val lastError = job.str("last_error") ?: job.str("last_delivery_error")
        if (job.str("last_status").let { it != null && it != "ok" } && lastError != null)
            Txt("${s.lastFailed}: $lastError", Theme.type.caption, c.errorText, Modifier.padding(top = 4.dp), maxLines = 4)

        Label(s.what)
        Field(name, { name = it }, s.routineName)
        Spacer(Modifier.height(8.dp))
        Field(prompt, { prompt = it }, s.routinePrompt, singleLine = false, minHeight = 112.dp)

        Label(s.whenLabel)
        val morning = w.kind == Kind.Daily && w.hour in 4..14
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(s.everyMorning, morning, { w = When(Kind.Daily, 8, 0) })
            Chip(s.everyEvening, w.kind == Kind.Daily && !morning, { w = When(Kind.Daily, 20, 0) })
            Chip(s.weekdays, w.kind == Kind.Weekdays, { w = w.copy(kind = Kind.Weekdays) })
            Chip(s.everyHour, w.kind == Kind.Hourly, { w = w.copy(kind = Kind.Hourly) })
            Chip(s.once, w.kind == Kind.Once, { w = w.copy(kind = Kind.Once) })
        }
        AnimatedVisibility(w.kind == Kind.Daily || w.kind == Kind.Weekdays || w.kind == Kind.Once) {
            TimeWheel(w.hour, w.minute, { h, m -> w = w.copy(hour = h, minute = m) }, Modifier.padding(top = 8.dp))
        }

        Label(s.who)
        WhoPicker(bots, profile, locked = job != null, mood = when { saving -> Mood.Working; error != null -> Mood.Error; else -> Mood.Idle }) { profile = it }

        val turn by animateFloatAsState(if (advanced) 180f else 0f, Motion.snappy())
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp).squish({ advanced = !advanced }, scale = .97f, haptic = Haptic.Tick, label = s.advanced),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(s.advanced, Theme.type.headline, modifier = Modifier.weight(1f))
            Icon(Icons.expandMore, c.secondary, modifier = Modifier.graphicsLayer { rotationZ = turn })
        }
        AnimatedVisibility(advanced) {
            Column {
                Field(if (w.kind == Kind.Custom) w.text else w.toSchedule(nowMillis(), offset ?: 0), { w = When(Kind.Custom, w.hour, w.minute, it) }, s.cronExpr, mono = true)

                Label(r.sendTo)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val listed = targets.filter { it.bool("home_target_set") != false }.mapNotNull { tg -> tg.str("id")?.let { it to tg.str("name") } }
                    val extra = deliver.filter { d -> listed.none { it.first == d } }.map { it to null }
                    (listed + extra).forEach { (id, serverName) ->
                        val on = id in deliver
                        Chip(deliverLabel(id, serverName, profile, bots, r), on, { deliverTouched = true; deliver = if (on) deliver - id else deliver + id })
                    }
                }

                if (allSkills.isNotEmpty() || skills.isNotEmpty()) {
                    Label(s.skills)
                    if (allSkills.size > 12) { Field(skillQuery, { skillQuery = it }, s.search); Spacer(Modifier.height(8.dp)) }
                    val shown = (skills.toList() + allSkills.filter { it !in skills && (skillQuery.isBlank() || it.contains(skillQuery.trim(), ignoreCase = true)) }).take(40)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        shown.forEach { sk ->
                            val on = sk in skills
                            Chip(sk, on, { skills = if (on) skills - sk else skills + sk })
                        }
                    }
                }

                Label(r.model)
                Field(model, { model = it }, r.modelDefault)
            }
        }

        if (job != null) {
            Spacer(Modifier.height(12.dp))
            ActionRow(Icons.history, s.history, c.text) { onHistory(job) }
            if (!confirmDelete) ActionRow(Icons.delete, s.delete, c.errorText) { confirmDelete = true }
            else ActionRow(Icons.deleteFilled, s.confirmDeleteRoutine, c.errorText, haptic = Haptic.Confirm) { onDelete(job) }
        }
        Spacer(Modifier.height(16.dp))
    }
    error?.let { Txt(it, Theme.type.caption, c.errorText, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), maxLines = 3) }
    PillButton(if (job == null) s.create else s.save, ::save, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), enabled = canSave)
}

/** Small caps section label without the list inset (the sheet column is already padded). */
@Composable
internal fun Label(text: String) =
    Txt(text.uppercase(), Theme.type.caps, Theme.colors.secondary, Modifier.padding(top = 18.dp, bottom = 8.dp), maxLines = 1)

/** A 56dp full-width row action inside a sheet (icon + words; the whole row is the target). */
@Composable
internal fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: androidx.compose.ui.graphics.Color, haptic: Haptic = Haptic.Press, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).squish(onClick, scale = .97f, haptic = haptic, label = text),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, tint)
        Spacer(Modifier.width(14.dp))
        Txt(text, Theme.type.button, tint, Modifier.weight(1f), maxLines = 1)
    }
}

/** A call whose failure just means "nothing to show" (cancellation still propagates). */
internal suspend fun <T> quiet(block: suspend () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

private fun defaultDeliver(targets: List<JsonObject>, profile: String): String {
    val ids = targets.mapNotNull { it.str("id") }
    return ids.firstOrNull { it == "bot-chat:$profile" } ?: ids.firstOrNull { it == "bot-chat" } ?: "local"
}

internal fun deliverLabel(id: String, serverName: String?, profile: String, bots: List<Bot>, r: RoutinesStrings): String = when {
    id == "local" -> r.justSave
    id == "origin" -> r.homeChannel
    id == "bot-chat" || id == "bot-chat:$profile" -> r.botChat
    id.startsWith("bot-chat:") -> id.substringAfter(':').let { p -> bots.firstOrNull { it.profile == p }?.name ?: p }
    else -> serverName ?: id
}

/** Buddy picker, 64dp tiles (design §3.6 "Who"). Locked when editing: a job can't move to another bot. */
@Composable
private fun WhoPicker(bots: List<Bot>, selected: String, locked: Boolean, mood: Mood, onPick: (String) -> Unit) {
    val c = Theme.colors
    val shown = (if (locked) bots.filter { it.profile == selected } else bots.filter { !it.hidden || it.profile == selected })
        .map { it.profile to it.name }.ifEmpty { listOf(selected to selected) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { (p, name) ->
            key(p) {
                val on = p == selected
                var cue by remember { mutableStateOf<BuddyCue?>(null) }
                Column(
                    Modifier.width(72.dp).squish({ onPick(p); cue = BuddyCue(nowMillis(), OneShot.Hop) }, haptic = Haptic.Tick, enabled = !locked, label = name),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val shape = RoundedCornerShape(20.dp)
                    Box(
                        Modifier.size(64.dp).clip(shape).background(if (on) c.accent.copy(alpha = .14f) else c.surface2)
                            .then(if (on) Modifier.border(2.dp, c.accent, shape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { Buddy(ownerSpec(p, bots), if (on) mood else Mood.Idle, 44.dp, cue = cue, interactive = false) }
                    Txt(name, Theme.type.micro, if (on) c.text else c.secondary, Modifier.padding(top = 4.dp), maxLines = 1)
                }
            }
        }
    }
}

/** Hour : minute wheels, 56dp rows, snapping, a tick per step (design §3.6). Digits stay left-to-right in Hebrew. */
@Composable
internal fun TimeWheel(hour: Int, minute: Int, onChange: (Int, Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val change by rememberUpdatedState(onChange)
    val h by rememberUpdatedState(hour)
    val m by rememberUpdatedState(minute)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier.fillMaxWidth().height(168.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp)).background(c.surface2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Wheel(24, hour) { change(it, m) }
                Txt(":", Theme.type.title, c.text, Modifier.padding(horizontal = 4.dp))
                Wheel(60, minute) { change(h, it) }
            }
        }
    }
}

@Composable
private fun Wheel(count: Int, value: Int, onChange: (Int) -> Unit) {
    val fb = LocalFeedback.current
    val c = Theme.colors
    val state = rememberLazyListState(initialFirstVisibleItemIndex = value.coerceIn(0, count - 1))
    val fling = rememberSnapFlingBehavior(state, SnapPosition.Center)
    val centered by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index ?: -1
        }
    }
    val latest by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    var programmatic by remember { mutableStateOf(false) }
    // The finger moved the wheel: report it (scrolls we start ourselves don't count).
    LaunchedEffect(centered) {
        if (centered >= 0 && !programmatic && centered != latest) { fb.haptic(Haptic.Tick); change(centered) }
    }
    // Someone else set the value (a preset chip): roll there.
    LaunchedEffect(value) {
        if (centered >= 0 && value != centered && !state.isScrollInProgress) {
            programmatic = true
            try { state.animateScrollToItem(value.coerceIn(0, count - 1)) } finally { programmatic = false }
        }
    }
    LazyColumn(Modifier.width(72.dp).height(168.dp), state = state, flingBehavior = fling, contentPadding = PaddingValues(vertical = 56.dp)) {
        items(count) { i ->
            val on = i == centered
            Box(Modifier.fillMaxWidth().height(56.dp).squish({ change(i) }, scale = .9f, haptic = null, label = i.toString()), contentAlignment = Alignment.Center) {
                Txt(i.toString().padStart(2, '0'), if (on) Theme.type.title else Theme.type.headline, if (on) c.text else c.tertiary, maxLines = 1)
            }
        }
    }
}
