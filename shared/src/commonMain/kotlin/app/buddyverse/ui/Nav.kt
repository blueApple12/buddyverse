package app.buddyverse.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.ChatTarget
import app.buddyverse.core.Room

enum class Tab { Bots, Chats, Routines, More }

/** Every More page, in hub order. Each has its own file `ui/more/<Name>Page.kt`; label and icon live in `ui/more/MoreDetail.kt`. */
enum class MorePage {
    Skills, Tools, Mcp, Connectors, Plugins, Memory, Projects, Models, Messaging, Kanban, Webhooks,
    Processes, Files, Checkpoints, Artifacts, Insights, Status, Settings, Gallery,
}

sealed interface Screen {
    data class Chat(val target: ChatTarget) : Screen
    data class GroupRoom(val room: Room) : Screen
    data class More(val page: MorePage) : Screen
    /** A Hermes project (`projects.list` row) of [profile]'s projects.db. */
    data class Project(val profile: String, val projectId: String) : Screen
}

/** One entry on the push stack; [leaving] runs its exit animation before removal. */
@Stable
class Entry(val screen: Screen, val id: Long, val instant: Boolean = false) {
    var leaving by mutableStateOf(false)
}

/** Tabs plus a push stack of full screens (design §2). */
@Stable
class Nav {
    var tab by mutableStateOf(Tab.Bots)
    /** Bumps when the active tab is tapped again: scroll to top (and on Bots, a third tap = wave). */
    var reselect by mutableStateOf(0)
    val stack = mutableStateListOf<Entry>()
    private var next = 0L
    /** A wide window shows pushed screens beside the list (design §2.2); set by the layout. */
    var wide = false
    /** The games panel (ui/play/GamesDock): docked beside the screens on a wide window, a floating card on a phone. */
    var play by mutableStateOf(false)
    /** The games card is folded to a pill (its game paused). */
    var playFolded by mutableStateOf(false)
    /** Play icon: opens (or unfolds) the games; a second press closes them. */
    fun togglePlay() { if (play && !playFolded) play = false else { play = true; playFolded = false } }
    /** Ctrl+K: the [ChatTarget.key] whose chat should open its command palette (that chat clears it). */
    var palette by mutableStateOf<String?>(null)
    /** "Connect a model" (Settings, first-run setup): More ▸ Models opens its provider list once, then clears this. */
    var connectModel by mutableStateOf(false)

    fun push(s: Screen) {
        // ponytail: on a wide window every list click pushes into the detail pane; keep the newest 4 so hidden chats don't pile up.
        if (wide) while (stack.count { !it.leaving } >= 4) stack.removeAt(stack.indexOfFirst { !it.leaving })
        stack += Entry(s, next++)
    }
    fun pop() { stack.lastOrNull { !it.leaving }?.leaving = true }
    /** Swap the top screen in place (one step, shown at once: no frame of what is under it). */
    fun replaceTop(s: Screen) {
        val old = stack.lastOrNull { !it.leaving }
        val e = Entry(s, next++, instant = old != null)
        if (old == null) stack += e else stack[stack.indexOf(old)] = e
    }
    fun removed(e: Entry) { stack.remove(e) }
    val covered: Boolean get() = stack.any { !it.leaving }
    /** The tab's list is off screen: covered on a phone. A wide window keeps it visible beside the pushed screens. */
    val listHidden: Boolean get() = covered && !wide

    fun select(t: Tab) { if (t == tab) reselect++ else { tab = t; reselect = 0 } }
}

/** Undo toast with an optional buddy that plays a one-shot in it (design §3.4). */
data class ToastData(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null, val buddy: BuddySpec? = null, val shot: OneShot? = null, val id: Long = 0)

@Stable
class Toasts {
    var current by mutableStateOf<ToastData?>(null)
    private var n = 0L
    fun show(t: ToastData) { current = t.copy(id = ++n) }
    fun dismiss(id: Long) { if (current?.id == id) current = null }
}

val LocalNav = staticCompositionLocalOf { Nav() }
val LocalToasts = staticCompositionLocalOf { Toasts() }
