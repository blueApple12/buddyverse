package app.buddyverse.ui.routines

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.Bot
import app.buddyverse.core.ChatTarget
import app.buddyverse.core.bool
import app.buddyverse.core.nowMillis
import app.buddyverse.core.str
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Card
import app.buddyverse.design.Chip
import app.buddyverse.design.Dot
import app.buddyverse.design.Haptic
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.PillButton
import app.buddyverse.design.Sound
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.Strings
import app.buddyverse.i18n.routines
import app.buddyverse.ui.LocalNav
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.Screen
import app.buddyverse.ui.ScreenHeader
import app.buddyverse.ui.ago
import app.buddyverse.ui.parseIsoMillis
import app.buddyverse.ui.untilIso
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Routines tab (design §3.6, Hermes cron): living cards with switch and run now, editor sheet, history, templates. */
@Composable
fun RoutinesScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val s = S.current
    val fb = LocalFeedback.current
    val st by store.routines.collectAsState()
    val bots by store.bots.collectAsState()
    var filter by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var historyId by remember { mutableStateOf<String?>(null) }
    var templatesFor by remember { mutableStateOf<String?>(null) }
    var openings by remember { mutableLongStateOf(0L) }
    // One-shot reactions per job id. A cue counts only while fresh, so a card scrolled back into view doesn't replay it.
    val cues = remember { mutableStateMapOf<String, BuddyCue>() }
    val lastCue = remember { longArrayOf(0L) }
    fun cue(id: String?, shot: OneShot) {
        if (id == null) return
        val t = maxOf(nowMillis(), lastCue[0] + 1); lastCue[0] = t
        cues[id] = BuddyCue(t, shot)
    }
    val now by produceState(nowMillis()) { while (true) { delay(30_000); value = nowMillis() } }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { store.refreshRoutines() }
    // While anything runs, watch it finish (hermes-api.md §2.5 polls fast during a run; cron.changed covers the rest).
    val busy = st.running.isNotEmpty() || st.jobs.any { it.str("state") == "running" }
    LaunchedEffect(busy) { while (busy) { delay(4_000); store.refreshRoutines() } }
    LaunchedEffect(nav.reselect) { if (nav.reselect > 0) listState.animateScrollToItem(0) }

    val profiles = remember(st.jobs) { st.jobs.mapNotNull { it.str("profile") }.distinct() }
    val scope = filter?.takeIf { it in profiles }
    val shown = remember(st.jobs, scope) {
        st.jobs.filter { scope == null || it.str("profile") == scope }
            .sortedWith(compareByDescending<JsonObject> { isOn(it) }.thenBy { parseIsoMillis(it.str("next_run_at").orEmpty()) ?: Long.MAX_VALUE })
    }
    val newProfile = scope ?: bots.firstOrNull { it.isDefault }?.profile ?: bots.firstOrNull()?.profile ?: "default"

    // A tab screen sits under the floating tab bar: lift the whole screen above it while a sheet is up (and while it slides away).
    val anySheet = editing != null || historyId != null || templatesFor != null
    var raised by remember { mutableStateOf(false) }
    LaunchedEffect(anySheet) { if (anySheet) raised = true else { delay(450); raised = false } }

    Box(Modifier.fillMaxSize().zIndex(if (raised || anySheet) 1f else 0f)) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(s.tabRoutines)
            if (profiles.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(s.all, scope == null, { filter = null })
                    profiles.forEach { p -> Chip(bots.firstOrNull { it.profile == p }?.name ?: p, scope == p, { filter = p }) }
                }
            }
            if (shown.isEmpty()) {
                val brain = ownerSpec(bots.firstOrNull { it.isDefault }?.profile ?: "default", bots)
                when {
                    !st.loaded -> BuddyNote(brain, Mood.Thinking, s.loading, Modifier.weight(1f), 120.dp)
                    st.error != null -> BuddyNote(brain, Mood.Error, s.error, Modifier.weight(1f), 120.dp) {
                        PillButton(s.retry, { store.refreshRoutines() }, kind = ButtonKind.Quiet)
                    }
                    else -> BuddyNote(brain, Mood.Idle, s.noRoutines, Modifier.weight(1f), 120.dp)
                }
            } else {
                val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 76.dp + 56.dp + 28.dp
                LazyColumn(
                    Modifier.weight(1f), state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottom),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(shown, key = { it.str("id").orEmpty() }) { job ->
                        val id = job.str("id").orEmpty()
                        val profile = job.str("profile") ?: "default"
                        RoutineCard(
                            job = job,
                            // Own seed per card: two routines of one bot never breathe in sync.
                            spec = ownerSpec(profile, bots).let { it.copy(id = "${it.id}/$id") },
                            owner = if (profiles.size > 1 && scope == null) bots.firstOrNull { it.profile == profile }?.name ?: profile else null,
                            running = id in st.running || job.str("state") == "running",
                            now = now,
                            cue = cues[id]?.takeIf { nowMillis() - it.serial < 4_000 },
                            onCue = { cue(id, it) },
                            onOpen = { editing = EditTarget(job, profile, ++openings) },
                            onHistory = { historyId = id },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }

        PillButton(
            s.newRoutine, { editing = EditTarget(null, newProfile, ++openings) },
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 88.dp)
                .shadow(14.dp, CircleShape, ambientColor = Theme.colors.shadow, spotColor = Theme.colors.shadow),
            icon = Icons.add,
        )

        RoutineEditor(
            editing,
            onDismiss = { editing = null },
            onSaved = { saved ->
                val id = saved.str("id")
                val known = st.jobs.any { it.str("id") == id }
                editing = null
                cue(id, if (known) OneShot.Nod else OneShot.Arrive)
            },
            onHistory = { j -> editing = null; historyId = j.str("id") },
            onDelete = { j ->
                editing = null
                cue(j.str("id"), OneShot.Leave)
                fb.haptic(Haptic.Confirm)
                // Let the buddy shrink away first; the store scope finishes the delete even if this screen goes.
                store.scope.launch { delay(900); store.deleteRoutine(j) }
            },
            onTemplates = { p -> editing = null; templatesFor = p },
        )
        RoutineHistory(
            st.jobs.firstOrNull { it.str("id") == historyId },
            onDismiss = { historyId = null },
            onOpen = { p, sid -> historyId = null; nav.push(Screen.Chat(ChatTarget(p, sid, botChat = false))) },
        )
        RoutineTemplates(templatesFor, onDismiss = { templatesFor = null }, onCreated = { job -> templatesFor = null; cue(job.str("id"), OneShot.Arrive) })
    }
}

/** One routine: owner buddy 44, name, schedule in words, status, run now 48, switch (design §3.6 card 88). */
@Composable
private fun RoutineCard(
    job: JsonObject,
    spec: BuddySpec,
    owner: String?,
    running: Boolean,
    now: Long,
    cue: BuddyCue?,
    onCue: (OneShot) -> Unit,
    onOpen: () -> Unit,
    onHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val store = LocalStore.current
    val s = S.current
    val r = S.routines
    val c = Theme.colors
    val fb = LocalFeedback.current
    val name = jobName(job, s)
    val on = isOn(job)
    val status = job.str("last_status")
    val failed = status != null && status != "ok"
    val mood = when {
        running -> Mood.Working
        !on -> Mood.Sleeping
        failed -> Mood.Error
        else -> Mood.Idle
    }
    // A run that lands while the card is on screen: a happy hop with confetti, or a bonk.
    val lastRun = job.str("last_run_at")
    val seen = remember { arrayOf(lastRun) }
    LaunchedEffect(lastRun) {
        if (lastRun != seen[0]) {
            seen[0] = lastRun
            if (status == "ok") { onCue(OneShot.Done); fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm) }
            else { onCue(OneShot.Dizzy); fb.sound(Sound.Bonk); fb.haptic(Haptic.Reject) }
        }
    }
    val next = if (on && !running) untilIso(job.str("next_run_at"), now, S.current)?.let(r.nextIn) else null
    // Desktop's NEXT_RUN_OVERDUE_GRACE_MS: a next run 15 min in the past means the PC's scheduler isn't ticking.
    val overdue = on && !running && (job.str("next_run_at")?.let(::parseIsoMillis)?.let { now - it > 15 * 60_000L } ?: false)
    val state: Pair<String, androidx.compose.ui.graphics.Color>? = when {
        running -> s.running to c.working
        !on -> s.paused to c.tertiary
        overdue -> r.overdue to c.needsYouText
        failed -> s.lastFailed to c.errorText
        status == "ok" && lastRun != null -> {
            val a = parseIsoMillis(lastRun)?.let { ago(it / 1000.0, now, s) }.orEmpty()
            (if (a.isEmpty()) s.lastOk else "${s.lastOk} · ${if (a == s.justNow) a else s.ago(a)}") to c.success
        }
        else -> null
    }
    Card(modifier.fillMaxWidth().squish(onOpen, scale = .97f, onLongPress = onHistory, label = name), padding = 0.dp) {
        Row(Modifier.heightIn(min = 88.dp).padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Buddy(spec, mood, 44.dp, cue = cue)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(name, Theme.type.headline, maxLines = 1)
                Txt(listOfNotNull(owner, routineWords(job, s, r), next).joinToString(" · "), Theme.type.caption, c.secondary, maxLines = 1)
                state?.let { (text, color) ->
                    Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(color, 6.dp)
                        Spacer(Modifier.width(6.dp))
                        Txt(text, Theme.type.caption, color, maxLines = 1)
                    }
                }
            }
            IconButton(Icons.playArrowFilled, s.runNow, { onCue(OneShot.Hop); fb.sound(Sound.Pop); store.runRoutine(job) }, tint = c.accent, enabled = !running)
            Toggle(on, { v -> onCue(if (v) OneShot.Stretch else OneShot.Yawn); store.toggleRoutine(job, v) }, name)
        }
    }
}

/** A buddy with a line under it: loading, empty and error states (no spinners, design rule 3). */
@Composable
internal fun BuddyNote(spec: BuddySpec, mood: Mood, text: String, modifier: Modifier = Modifier, size: Dp = 88.dp, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Buddy(spec, mood, size)
        Spacer(Modifier.height(14.dp))
        Txt(text, Theme.type.headline.copy(textAlign = TextAlign.Center), Theme.colors.secondary, maxLines = 4)
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

/** The bot that owns a routine, or a stand-in buddy seeded by its profile name. */
internal fun ownerSpec(profile: String, bots: List<Bot>): BuddySpec =
    bots.firstOrNull { it.profile == profile }?.spec ?: BuddySpec(profile, profile, BuddyShape.parse(null, profile), BuddyColors.forName(profile))

internal fun jobName(job: JsonObject, s: Strings): String =
    job.str("name")?.takeIf { it.isNotBlank() }
        ?: job.str("prompt")?.lineSequence()?.firstOrNull()?.take(50)?.takeIf { it.isNotBlank() }
        ?: s.untitled

/** Scheduled and not paused (a finished one-shot counts as off). */
internal fun isOn(job: JsonObject): Boolean = job.bool("enabled") != false && job.str("state") != "paused" && job.str("state") != "completed"
