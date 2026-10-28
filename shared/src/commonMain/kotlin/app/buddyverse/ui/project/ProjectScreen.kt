package app.buddyverse.ui.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.BuddyGroup
import app.buddyverse.buddy.Mood
import app.buddyverse.core.BotStatus
import app.buddyverse.core.ChatTarget
import app.buddyverse.core.Room
import app.buddyverse.core.api.LiveSubagent
import app.buddyverse.core.api.Project
import app.buddyverse.core.api.liveSubagents
import app.buddyverse.core.api.withHelperChat
import app.buddyverse.core.api.projectAgents
import app.buddyverse.core.api.projectGet
import app.buddyverse.core.api.projectKey
import app.buddyverse.core.api.projectList
import app.buddyverse.core.api.projectRoom
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.project
import app.buddyverse.ui.LocalNav
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.Screen
import app.buddyverse.ui.bot.BotProfileSheet
import app.buddyverse.ui.bot.mood
import app.buddyverse.ui.chat.Spinner
import app.buddyverse.ui.ltr
import app.buddyverse.ui.room.NewRoomSheet
import app.buddyverse.ui.room.RoomBody
import app.buddyverse.ui.room.specOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Last path segment: `C:\code\mods\` → `mods`. */
private fun folderName(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

/**
 * A project (design §3.3): header with its folder and the info sheet; its conversation is a group room of its agents (the room
 * named like the project). Until that room exists the screen lists the agents (each opens its chat) and starts the room.
 * Helpers (subagents) of the agents are always on screen while they work, and each opens to be watched.
 */
@Composable
fun ProjectScreen(profile: String, projectId: String) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val s = S.current
    val sp = S.project
    val c = Theme.colors
    val bots by store.bots.collectAsState()
    val rooms by store.rooms.collectAsState()

    var project by remember(profile, projectId) { mutableStateOf<Project?>(null) }
    var missing by remember(profile, projectId) { mutableStateOf(false) }
    LaunchedEffect(profile, projectId) {
        store.refreshRooms()
        val found = try { store.api.projectGet(profile, projectId) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            ?: try { store.api.projectList(profile).firstOrNull { it.id == projectId } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        project = found
        missing = found == null
    }
    val p = project

    // Who works here: the owner plus every bot whose projects.db holds the same folder (the Bots home stage's rule).
    val agents by produceState(listOf(profile), p?.let(::projectKey)) {
        val pr = p ?: return@produceState
        value = try { store.projectAgents(pr) } catch (e: CancellationException) { throw e } catch (_: Exception) { listOf(pr.profile) }
    }
    // Their live helpers (delegate_task children), polled while any of them works.
    val busy = agents.filter { a -> bots.firstOrNull { it.profile == a }?.status.let { it != null && it != BotStatus.Idle } }
    val helpers by produceState(emptyMap<String, List<LiveSubagent>>(), busy) {
        if (busy.isEmpty()) { value = emptyMap(); return@produceState }
        while (true) {
            value = busy.associateWith { a -> try { store.api.liveSubagents(a) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() } }
            delay(5_000)
        }
    }

    var made by remember(profile, projectId) { mutableStateOf<Room?>(null) }
    val room = p?.let { pr -> projectRoom(rooms, pr) } ?: made
    var info by remember { mutableStateOf(false) }
    var newRoom by remember { mutableStateOf(false) }
    var profileSheet by remember { mutableStateOf<String?>(null) }

    fun watch(a: String, sa: LiveSubagent) = store.withHelperChat(a, sa) { child -> nav.push(Screen.Chat(helperTarget(bots.firstOrNull { it.profile == a }, a, sa, child))) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            // Header (64): back, folder in the project's color, name + folder, info.
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(Icons.arrowBack, s.back, { nav.pop() }, mirror = true)
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(Icons.folderFilled, projectTint(p?.color), 30.dp) }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f).squish({ info = true }, scale = .97f, enabled = p != null, label = sp.info)) {
                    Txt(p?.name?.ifBlank { null } ?: p?.primaryPath?.let(::folderName) ?: projectId, Theme.type.headline, c.text, maxLines = 1)
                    p?.primaryPath?.let { Txt(ltr(it), Theme.type.caption, c.secondary, maxLines = 1) }
                }
                IconButton(Icons.info, sp.info, { info = true }, enabled = p != null)
            }
            HelpersStrip(helpers, bots, ::watch)

            when {
                missing -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Txt(sp.notFound, Theme.type.callout.copy(textAlign = TextAlign.Center), c.secondary)
                }
                p == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Spinner(28.dp, c.tertiary) }
                room != null -> RoomBody(room, Modifier.weight(1f), onProfile = { profileSheet = it })
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
                    item(key = "crew") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            BuddyGroup(agents.map { specOf(it, bots) }, agents.map { a -> bots.firstOrNull { it.profile == a }?.status?.mood() ?: Mood.Idle }, 112.dp)
                            Spacer(Modifier.height(12.dp))
                            Txt(sp.noRoom, Theme.type.callout, c.secondary, maxLines = 1)
                            Spacer(Modifier.height(12.dp))
                            PillButton(sp.startRoom, { newRoom = true }, icon = Icons.group)
                        }
                    }
                    item(key = "agents-h") { SectionLabel(sp.agents) }
                    items(agents, key = { it }) { a ->
                        AgentRow(a, bots, helpers[a].orEmpty(), onOpen = { nav.push(Screen.Chat(ChatTarget(a))) }) { sa -> watch(a, sa) }
                    }
                }
            }
        }

        ProjectInfoSheet(info, p, room, agents, helpers, onDismiss = { info = false }, onChanged = { project = it }, onGone = { nav.pop() })
        NewRoomSheet(newRoom, name = p?.name.orEmpty(), preselect = agents, onCreated = { made = it }) { newRoom = false }
        BotProfileSheet(profileSheet) { profileSheet = null }
    }
}
