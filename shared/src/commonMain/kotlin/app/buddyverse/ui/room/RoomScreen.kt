package app.buddyverse.ui.room

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.BuddyGroup
import app.buddyverse.core.Room
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Haptic
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.room
import app.buddyverse.ui.LocalNav
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.bot.BotProfileSheet
import app.buddyverse.ui.chat.MenuItem
import app.buddyverse.ui.chat.RenameSheet

private enum class RoomOpen { None, Options, Rename, Disband }

/** A group room (Hermes `groups.*`, hermes-api.md §2.4 Group chats): live header (team huddle, name, who works) + [RoomBody]. */
@Composable
fun RoomScreen(room: Room) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val toasts = LocalToasts.current
    val s = S.current
    val sr = S.room
    val c = Theme.colors
    val model = rememberRoomModel(room)
    val bots by store.bots.collectAsState()
    var open by remember { mutableStateOf(RoomOpen.None) }
    var profile by remember { mutableStateOf<String?>(null) }

    val names = model.seats.map { st -> seatName(st.memberId, st, null) { p -> bots.firstOrNull { it.profile == p }?.name } }
    val title = model.room.name.ifBlank { names.joinToString(", ") }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(Icons.arrowBack, s.back, { nav.pop() }, mirror = true)
                BuddyGroup(model.seats.map { specOf(it.profile, bots) }, model.seats.map { model.moodOf(it.memberId, emptySet(), false) }, 44.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f).squish({ open = RoomOpen.Options }, scale = .97f, label = sr.options)) {
                    Txt(title, Theme.type.headline, c.text, maxLines = 1)
                    Crossfade(model.working, animationSpec = tween(200), label = "sub") { busy ->
                        Txt(if (busy) sr.teamWorking else s.groupOf(model.seats.size), Theme.type.caption, if (busy) c.working else c.secondary, maxLines = 1)
                    }
                }
                IconButton(Icons.moreHoriz, sr.options, { open = RoomOpen.Options }, enabled = !model.gone)
            }
            RoomBody(room, Modifier.weight(1f), model = model, onProfile = { profile = it })
        }

        Sheet(open == RoomOpen.Options, { open = RoomOpen.None }, title = sr.options) {
            MenuItem(Icons.edit, sr.renameRoom) { open = RoomOpen.Rename }
            if (model.working) MenuItem(Icons.stop, s.stop) { open = RoomOpen.None; model.stop() }
            MenuItem(Icons.delete, sr.disband, c.errorText) { open = RoomOpen.Disband }
        }
        RenameSheet(open == RoomOpen.Rename, model.room.name, { open = RoomOpen.None }) { t -> open = RoomOpen.None; model.rename(t) }
        Sheet(open == RoomOpen.Disband, { open = RoomOpen.None }, title = sr.disband) {
            Txt(sr.disbandAsk, Theme.type.body, c.text, Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(sr.disband, {
                    open = RoomOpen.None
                    model.disband { toasts.show(ToastData(sr.disbanded)); nav.pop() }
                }, Modifier.weight(1f), kind = ButtonKind.Danger, haptic = Haptic.Reject)
                PillButton(s.cancel, { open = RoomOpen.None }, Modifier.weight(1f), kind = ButtonKind.Quiet)
            }
        }
        BotProfileSheet(profile) { profile = null }
    }
}
