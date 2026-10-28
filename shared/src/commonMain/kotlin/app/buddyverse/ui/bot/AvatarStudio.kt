package app.buddyverse.ui.bot

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.Bot
import app.buddyverse.core.ChatTarget
import app.buddyverse.core.api.autoDescribe
import app.buddyverse.core.api.configureBot
import app.buddyverse.core.api.initBot
import app.buddyverse.core.api.saveBotMeta
import app.buddyverse.core.nowMillis
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Chip
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Sheet
import app.buddyverse.design.Sound
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.bot
import app.buddyverse.i18n.gallery
import app.buddyverse.ui.LocalNav
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.Screen
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.ltr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Avatar studio (design §3.8): create a bot when [profile] is null, else edit that bot's look, name and role.
 * Shown while [open]; place it last in a full-size Box. Called from Bots home (+ / Edit look) and the profile sheet: keep this signature.
 */
@Composable
fun AvatarStudio(open: Boolean, profile: String?, onDismiss: () -> Unit) {
    // Keep showing the same bot while the sheet slides away, even if the caller clears [profile].
    val held = remember { arrayOfNulls<String>(1) }
    if (open) held[0] = profile
    val shown = held[0]
    val bots by LocalStore.current.bots.collectAsState()
    val generation = openCount(open)
    Sheet(open, onDismiss, heightFraction = .94f) {
        key(generation, shown) { StudioBody(shown, shown?.let { p -> bots.firstOrNull { it.profile == p }?.edited() }, onDismiss) }
    }
}

@Composable
private fun ColumnScope.StudioBody(profile: String?, bot: Bot?, onDismiss: () -> Unit) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val toasts = LocalToasts.current
    val fb = LocalFeedback.current
    val s = S.current
    val b = S.bot
    val c = Theme.colors
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(bot?.name ?: profile.orEmpty()) }
    var role by remember { mutableStateOf(bot?.description.orEmpty()) }
    // The look we started from: the bot's own, a roster-less default for its name, or a random one for a new bot.
    val start = remember {
        bot?.spec ?: if (profile != null) BuddySpec(profile, profile, BuddyShape.parse(null, profile), BuddyColors.forName(profile))
        else BuddySpec("", "", BuddyShape.entries.random(), BuddyColors.palette[2 + Random.nextInt(9)])
    }
    var shape by remember { mutableStateOf(start.shape) }
    var color by remember { mutableStateOf(start.color) }
    var preview by remember { mutableStateOf(Mood.Idle) }
    var busy by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val cues = remember { Cues() }
    val pop = remember { Animatable(1f) }
    val shownColor by animateColorAsState(color, Motion.morph())
    val alive = remember { booleanArrayOf(true) }
    DisposableEffect(Unit) { onDispose { alive[0] = false } }

    // Talk preview: feed the mouth like a streaming reply would (design §4.4).
    var talkAt by remember { mutableLongStateOf(0L) }
    var talkChars by remember { mutableIntStateOf(0) }
    LaunchedEffect(preview) {
        if (preview == Mood.Talking) while (true) { talkChars = Random.nextInt(3, 14); talkAt = nowMillis(); delay(Random.nextLong(110, 200)) }
    }

    val taken = store.bots.value.map { it.profile }.toSet()
    val title = name.trim()
    val spec = BuddySpec(profile ?: "studio-new", title.ifEmpty { b.namePlaceholder }, shape, shownColor)

    fun bounce() { scope.launch { pop.snapTo(.82f); pop.animateTo(1f, Motion.bouncy()) } }
    fun pickShape(next: BuddyShape) {
        if (next == shape) { cues.fire(OneShot.Giggle); return }
        shape = next; cues.fire(OneShot.Hop); bounce()
    }
    fun pickColor(next: Color) {
        if (next == color) { cues.fire(OneShot.Giggle); return }
        color = next; cues.fire(OneShot.Wiggle)
    }
    fun surprise() {
        shape = BuddyShape.entries.filter { it != shape }.random()
        color = BuddyColors.palette.filter { it != color }.random()
        cues.fire(OneShot.Arrive)
    }

    // The buddy thinks while the server's LLM writes the role; the server keeps what it wrote.
    fun autoRole(p: String) {
        if (writing) return
        writing = true
        scope.launch {
            val text = try { store.api.autoDescribe(p) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            writing = false
            if (text != null) { role = text.take(300); cues.fire(OneShot.Learned) } else { fb.haptic(Haptic.Reject); cues.fire(OneShot.Startle) }
        }
    }

    fun create() {
        if (title.isEmpty()) { fb.haptic(Haptic.Reject); cues.fire(OneShot.Startle); return }
        val handle = handleFor(title, taken)
        val what = role.trim()
        busy = true; error = null
        val made = b.made(title)
        store.scope.launch {
            val err = store.createBot(handle, what)
            if (err != null) {
                busy = false; error = err
                fb.haptic(Haptic.Reject); cues.fire(OneShot.Startle)
                return@launch
            }
            runCatching {
                store.api.initBot(handle, look(title, shape, color) + ("created" to nowMillis()), defaultSoul(title, handle, what))
            }
            runCatching { store.refreshRoster() }
            cues.fire(OneShot.Done); fb.haptic(Haptic.Confirm); fb.sound(Sound.Bloop)
            delay(700) // let the confetti land before the chat slides in
            if (alive[0]) { onDismiss(); nav.push(Screen.Chat(ChatTarget(handle))) }
            else toasts.show(ToastData(made, buddy = BuddySpec(handle, title, shape, color), shot = OneShot.Arrive))
        }
    }

    fun save(p: String) {
        val patch = buildMap<String, Any?> {
            if (title.isNotEmpty() && title != bot?.name) put("title", title)
            if (shape != start.shape || color != start.color) {
                put("shape", shape.wire()); put("color", color.css()); put("imageKind", "shape"); put("custom", true)
            }
        }
        // Roster not loaded yet: an empty field means "unknown", not "clear it".
        val desc = role.trim().takeIf { if (bot == null) it.isNotEmpty() else it != bot.description.trim() }
        onDismiss()
        if (patch.isEmpty() && desc == null) return
        // Optimistic: every screen that draws the bot through [edited] shows the new look now; the roster catches up after the write.
        BotEdits.pending[p] = BotEdit(title.ifEmpty { bot?.name ?: p }, desc ?: bot?.description.orEmpty(), shape, color)
        val failed = s.error
        store.scope.launch {
            val ok = try {
                (patch.isEmpty() || store.api.saveBotMeta(p, patch)) and (desc == null || store.api.configureBot(p, mapOf("description" to desc)))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            runCatching { store.refreshRoster() }
            BotEdits.pending.remove(p) // the server's truth from here on (a failed write snaps back)
            if (!ok) toasts.show(ToastData(failed))
        }
    }

    // Header: × · title · Done (48 each, design §3.8).
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(Icons.close, s.close, onDismiss)
        Txt(if (profile == null) s.newBot else b.editLook, Theme.type.headline, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1)
        PillButton(
            if (busy) b.creating else if (profile == null) s.create else s.done,
            { if (!busy) { if (profile == null) create() else save(profile) } },
            height = 48.dp, haptic = Haptic.Confirm,
        )
    }

    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(184.dp), contentAlignment = Alignment.Center) {
            Buddy(
                spec, if (busy || writing) Mood.Thinking else preview, 160.dp,
                Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value },
                cue = cues.cue, talkAt = talkAt, talkChars = talkChars, onTap = cues::next,
            )
        }
        Txt(title.ifEmpty { b.namePlaceholder }, Theme.type.display.copy(textAlign = TextAlign.Center), if (title.isEmpty()) c.tertiary else c.text,
            Modifier.fillMaxWidth().padding(horizontal = 20.dp), maxLines = 1)
        Txt(ltr("@" + (profile ?: handleFor(title, taken))), Theme.type.caption, c.secondary, maxLines = 1)
        Spacer(Modifier.height(8.dp))

        // State preview chips: the buddy plays each mood (design §3.8).
        val moods = listOf(
            Mood.Idle to b.moodIdle, Mood.Thinking to b.moodThink, Mood.Working to b.moodWork, Mood.Talking to b.moodTalk,
            Mood.NeedsYou to b.moodNeed, Mood.Error to b.moodOops, Mood.Sleeping to b.moodSleep,
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            moods.forEach { (m, label) -> Chip(label, preview == m, { preview = m }) }
        }

        SectionLabel(b.shape, Modifier.fillMaxWidth())
        BuddyShape.entries.chunked(4).forEach { row ->
            Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { sh ->
                    val on = sh == shape
                    val tile = RoundedCornerShape(20.dp)
                    Box(
                        Modifier.size(64.dp).squish({ pickShape(sh) }, haptic = Haptic.Tick, label = S.gallery.shape(sh)).clip(tile)
                            .background(if (on) c.accent.copy(alpha = .16f) else c.surface2)
                            .then(if (on) Modifier.border(2.dp, c.accent, tile) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { Buddy(BuddySpec("studio-${sh.name}", S.gallery.shape(sh), sh, shownColor), Mood.Idle, 48.dp, interactive = false) }
                }
            }
        }

        SectionLabel(b.color, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BuddyColors.palette.forEachIndexed { i, col ->
                val on = col == color
                Box(
                    Modifier.size(48.dp).squish({ pickColor(col) }, haptic = Haptic.Tick, label = BuddyColors.names[i]).clip(CircleShape).background(col)
                        .then(
                            when {
                                on -> Modifier.border(3.dp, c.text, CircleShape)
                                col.luminance() > .7f || col.luminance() < .05f -> Modifier.border(1.dp, c.tertiary.copy(alpha = .5f), CircleShape)
                                else -> Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) { if (on) Icon(Icons.check, BuddyColors.eyeOn(col), 22.dp) }
            }
        }

        SectionLabel(b.name, Modifier.fillMaxWidth())
        Field(name, { name = it.take(48); error = null }, b.namePlaceholder, Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(b.role, Modifier.weight(1f))
            if (profile != null) IconButton(Icons.autoAwesome, b.writeRole, { autoRole(profile) }, enabled = !writing, tint = if (writing) c.tertiary else c.accent)
        }
        Field(role, { role = it.take(300) }, b.rolePlaceholder, Modifier.padding(horizontal = 16.dp), singleLine = false)
        Spacer(Modifier.height(16.dp))
        PillButton(b.surpriseMe, ::surprise, height = 48.dp, kind = ButtonKind.Quiet, icon = Icons.casino)
        error?.let { Txt(it, Theme.type.sub.copy(textAlign = TextAlign.Center), c.errorText, Modifier.fillMaxWidth().padding(20.dp)) }
        Spacer(Modifier.height(24.dp))
    }
}
