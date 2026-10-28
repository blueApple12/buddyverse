package app.buddyverse.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.Room
import app.buddyverse.core.api.RoomCaps
import app.buddyverse.core.api.createRoom
import app.buddyverse.core.api.groupsCapabilities
import app.buddyverse.core.api.roomAuthority
import app.buddyverse.core.api.roomReason
import app.buddyverse.core.nowMillis
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Sheet
import app.buddyverse.design.Sound
import app.buddyverse.design.Field
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.room
import app.buddyverse.ui.LocalNav
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.Screen
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.bot.openCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Create a group chat (hermes-api.md §2.4 "Create a group chat"): pick 2–6 bots, optional name; opens the room when made.
 * Shown while [open]; place it last in a full-size Box. Called from Bots home as `NewRoomSheet(open) { close }`: keep [onDismiss]
 * last so that trailing lambda still lands on it (new parameters have defaults and sit before it).
 * [name] / [preselect] prefill the form (the Project screen passes its name and agents); [onCreated] replaces "open the room".
 */
@Composable
fun NewRoomSheet(
    open: Boolean,
    name: String = "",
    preselect: List<String> = emptyList(),
    onCreated: ((Room) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val generation = openCount(open)
    Sheet(open, onDismiss, title = S.room.newRoom, heightFraction = .92f) {
        key(generation) { NewRoomBody(name, preselect, onDismiss, onCreated) }
    }
}

@Composable
private fun ColumnScope.NewRoomBody(name: String, preselect: List<String>, onDismiss: () -> Unit, onCreated: ((Room) -> Unit)?) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val fb = LocalFeedback.current
    val toasts = LocalToasts.current
    val s = S.current
    val sr = S.room
    val c = Theme.colors
    val bots by store.bots.collectAsState()
    var title by remember { mutableStateOf(name) }
    val picked = remember { mutableStateListOf<String>().apply { addAll(preselect.distinct().take(6)) } }
    val cues = remember { mutableStateMapOf<String, BuddyCue>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // A failed probe offers every bot; the server still names a refused one on create.
    val caps by produceState<RoomCaps?>(null) {
        value = try { store.api.groupsCapabilities(store.roomAuthority) } catch (e: CancellationException) { throw e } catch (_: Exception) { RoomCaps(true, null) }
    }
    LaunchedEffect(caps) { caps?.seatable?.let { ok -> picked.removeAll { it !in ok } } }

    val rows = bots.filter { !it.hidden || it.profile in preselect }
    fun seatable(p: String) = caps?.seatable?.contains(p) ?: true
    val canonical = caps?.canonical != false

    fun toggle(p: String) {
        when {
            p in picked -> { picked.remove(p); fb.haptic(Haptic.Tick) }
            !seatable(p) -> { fb.haptic(Haptic.Reject); toasts.show(ToastData(sr.cantJoin)) }
            picked.size >= 6 -> { fb.haptic(Haptic.Reject); toasts.show(ToastData(sr.upToSix)) }
            else -> { picked.add(p); cues[p] = BuddyCue(nowMillis(), OneShot.Hop); fb.haptic(Haptic.Tick) }
        }
    }

    fun create() {
        if (busy || picked.size !in 2..6) return
        busy = true; error = null
        store.scope.launch {
            try {
                val room = store.createRoom(title.trim(), picked.toList())
                fb.sound(Sound.Pop); fb.haptic(Haptic.Confirm)
                onDismiss()
                if (onCreated != null) onCreated(room) else nav.push(Screen.GroupRoom(room))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = e.roomReason(); fb.haptic(Haptic.Reject)
            } finally { busy = false }
        }
    }

    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        item(key = "name") { Field(title, { title = it.take(200) }, sr.roomName, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        item(key = "count") {
            val text = when {
                !canonical -> s.unsupported
                rows.count { seatable(it.profile) } < 2 && caps != null -> sr.noSeatable
                else -> sr.picked(picked.size)
            }
            Txt(text, Theme.type.caption, c.secondary, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), maxLines = 2)
        }
        items(rows, key = { it.profile }) { b ->
            val on = b.profile in picked
            val ok = seatable(b.profile)
            PressRow({ toggle(b.profile) }, Modifier.graphicsLayer { alpha = if (ok) 1f else .45f }, minHeight = 64.dp, label = b.name) {
                Buddy(b.spec, if (on) Mood.Listening else Mood.Idle, 40.dp, cue = cues[b.profile], interactive = false)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Txt(b.name, Theme.type.headline, maxLines = 1)
                    val sub = if (!ok) sr.cantJoin else b.description
                    if (sub.isNotBlank()) Txt(sub, Theme.type.sub, c.secondary, maxLines = 1)
                }
                if (on) Icon(Icons.checkCircleFilled, c.accent, 28.dp)
                else Box(Modifier.size(28.dp).padding(2.dp).clip(CircleShape).border(2.dp, c.hairline, CircleShape).background(c.surface))
            }
        }
    }
    error?.let { Txt(it, Theme.type.sub, c.errorText, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), maxLines = 3) }
    PillButton(
        if (busy) sr.creating else s.create, ::create, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        enabled = !busy && canonical && picked.size in 2..6, haptic = Haptic.Confirm,
    )
}
