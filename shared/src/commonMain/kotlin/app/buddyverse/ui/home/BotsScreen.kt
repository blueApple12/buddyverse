package app.buddyverse.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.Bot
import app.buddyverse.core.BotStatus
import app.buddyverse.core.ChatController
import app.buddyverse.core.ChatTarget
import app.buddyverse.core.LinkState
import app.buddyverse.core.api.HomeLocal
import app.buddyverse.core.api.LiveSubagent
import app.buddyverse.core.api.Project
import app.buddyverse.core.api.deleteBot
import app.buddyverse.core.api.hideBot
import app.buddyverse.core.api.liveSubagents
import app.buddyverse.core.api.openHelperChat
import app.buddyverse.core.api.markBotRead
import app.buddyverse.core.api.pinBot
import app.buddyverse.core.api.projectsOf
import app.buddyverse.core.chat.ChatState
import app.buddyverse.core.nowMillis
import app.buddyverse.design.Dot
import app.buddyverse.design.Haptic
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.APP_NAME
import app.buddyverse.i18n.S
import app.buddyverse.i18n.home
import app.buddyverse.ui.LocalNav
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.MorePage
import app.buddyverse.ui.Screen
import app.buddyverse.ui.ScreenHeader
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.bot.AvatarStudio
import app.buddyverse.ui.bot.BotProfileSheet
import app.buddyverse.ui.room.NewRoomSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample

private val placeholder = BuddySpec("home", APP_NAME, BuddyShape.Squircle, BuddyColors.palette[8])

/**
 * Bots tab (design §3.1): the living stage (brain, its agents and their sub-agents, live subagents), needs-you cards answered inline,
 * then the roster of bots and group chats with live line 2. Scrolling collapses the stage into the team strip; a third
 * tap on the tab makes everyone hop in a wave; the team sleeps when the PC is off and wakes one by one when it answers.
 */
@Composable
fun BotsScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val toasts = LocalToasts.current
    val fb = LocalFeedback.current
    val s = S.current
    val h = S.home
    val c = Theme.colors

    val polled by store.bots.collectAsState()
    val loaded by store.botsLoaded.collectAsState()
    val rooms by store.rooms.collectAsState()
    val link by store.link.collectAsState()
    val pulse by store.chatPulse.collectAsState()
    val ready = link is LinkState.Ready
    val down = link is LinkState.Down
    val now by produceState(nowMillis()) { while (true) { delay(30_000); value = nowMillis() } }

    val all = applyLocal(polled, HomeLocal.pinned, HomeLocal.hidden, HomeLocal.gone)
    var showHidden by remember { mutableStateOf(false) }
    val shown = all.filter { !it.hidden }
    val hiddenCount = all.size - shown.size
    val order = remember(shown) { shown.withIndex().associate { it.value.profile to it.index } }

    // Live chats: busy bots' Bot Chats (and a waiting bot's live sessions) plus any chat already open for a bot.
    var controllers by remember { mutableStateOf(emptyList<ChatController>()) }
    LaunchedEffect(all, pulse) {
        val open = store.openChats().associateBy { it.target.key }
        val wanted = liveTargets(all).map { t -> open[t.key] ?: store.chat(t) }
        val mine = all.mapTo(HashSet()) { it.profile }
        controllers = (wanted + open.values.filter { it.target.profile in mine }).distinct()
    }
    // Streaming deltas arrive ~30×/s; the home needs ~10×/s (row tails, talking buddies), so the whole screen recomposes less.
    val stateFlow: Flow<List<Pair<ChatTarget, ChatState>>> = remember(controllers) {
        if (controllers.isEmpty()) flowOf(emptyList())
        else @OptIn(kotlinx.coroutines.FlowPreview::class) combine(controllers.map { ctl -> ctl.state.map { ctl.target to it } }) { it.toList() }.sample(100)
    }
    val states by stateFlow.collectAsState(emptyList())

    // Live subagents of every shown bot: delegate_task children run detached, so their parent is often already Idle.
    val watched = remember(shown) { shown.map { it.profile } }
    val subagents by produceState(emptyMap<String, List<LiveSubagent>>(), watched, ready) {
        if (!ready || watched.isEmpty()) { value = emptyMap(); return@produceState }
        while (true) {
            val raw = watched.associateWith { p ->
                try { store.api.liveSubagents(p) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            }
            // The shared engine answers every profile with the same list: give each helper to the bot that owns its session.
            val sessions = store.sessions.value
            val owners = buildMap {
                (sessions.rows + sessions.archived + sessions.background).forEach { put(it.id, it.profile) }
                store.bots.value.forEach { b -> b.liveSessions.forEach { put(it, b.profile) }; b.botChatId?.let { put(it, b.profile) } }
                store.openChats().forEach { c -> c.sessionId?.let { put(it, c.target.profile) } }
            }
            value = attributeHelpers(raw, owners, store.bots.value.firstOrNull { it.isDefault }?.profile)
            delay(5_000)
        }
    }

    // Projects place the agents (every bot's own projects.db; archived ones dropped): a project is just an agent, nested by folder.
    val profiles = remember(shown) { shown.map { it.profile } }
    val rawProjects by produceState(emptyMap<String, List<Project>>(), profiles, ready) {
        if (!ready || profiles.isEmpty()) return@produceState
        while (true) { value = store.api.projectsOf(profiles); delay(60_000) }
    }
    val parents = remember(shown, rawProjects) { agentParents(shown, rawProjects) }

    val live = all.associate { b -> b.profile to liveOf(b, states, s, h, HomeLocal.readAt[b.profile], subagents[b.profile]?.size ?: 0, now) }
    val asks = asksOf(all, states)
    val byKey = controllers.associateBy { it.target.key }
    val roster = rosterOf(if (showHidden) all else shown, rooms)

    // Unread watermark: seeded on first sight, kept current while that bot's chat is on top.
    val topChat = (nav.stack.lastOrNull { !it.leaving }?.screen as? Screen.Chat)?.target?.profile
    LaunchedEffect(all, topChat) {
        for (b in all) if (b.profile !in HomeLocal.readAt || b.profile == topChat) HomeLocal.readAt[b.profile] = b.lastActive
    }

    // Offline until the PC answers and the roster is in, then wake one by one; asleep while the engine is off.
    var wakeAt by remember { mutableLongStateOf(if (ready && loaded) 0L else -1L) }
    LaunchedEffect(ready, loaded) { wakeAt = if (!ready || !loaded) -1L else if (wakeAt < 0L) nowMillis() else wakeAt }
    var ripple by remember { mutableStateOf<Ripple?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(nav.reselect) {
        // Second tap scrolls to the top; the third and on make everyone hop in a wave, 40 ms apart (design §2.1). Tap is the
        // full hop with a happy face; OneShot.Hop is only the small needs-you bob.
        if (nav.reselect >= 2) ripple = Ripple(Cues.next(), OneShot.Tap, 40L, nowMillis())
        if (nav.reselect >= 1 && list.layoutInfo.totalItemsCount > 0) list.animateScrollToItem(0)
    }

    var menuFor by remember { mutableStateOf<String?>(null) }
    var deleteFor by remember { mutableStateOf<String?>(null) }
    var profileFor by remember { mutableStateOf<String?>(null) }
    var studio by remember { mutableStateOf(false) }
    var studioFor by remember { mutableStateOf<String?>(null) }
    var newRoom by remember { mutableStateOf(false) }
    val failed = { toasts.show(ToastData(s.error)) }

    fun openChat(b: Bot, target: ChatTarget = ChatTarget(b.profile)) {
        HomeLocal.readAt[b.profile] = b.lastActive
        nav.push(Screen.Chat(target))
    }
    fun hide(b: Bot, on: Boolean) {
        store.hideBot(b.profile, on, failed)
        if (on) toasts.show(ToastData(h.botHidden(b.name), s.undo, { store.hideBot(b.profile, false, failed) }, b.spec, OneShot.Wave))
    }
    fun newBot() { studioFor = null; studio = true }
    fun openHelper(b: Bot, sa: LiveSubagent?) {
        menuFor = null
        if (sa == null) openChat(b, helperTarget(b, null))
        else store.openHelperChat(b.profile, sa, { child -> openChat(b, helperTarget(b, sa.ownerSession, child)) }) { toasts.show(ToastData(h.helperGone)) }
    }
    fun answer(a: Ask, value: String) {
        val p = a.prompt ?: return
        (byKey[a.target.key] ?: store.chat(a.target)).answer(p, value)
    }
    fun act(b: Bot, a: MenuAction) {
        menuFor = null
        when (a) {
            MenuAction.Message -> openChat(b)
            MenuAction.Profile -> profileFor = b.profile
            MenuAction.Pin -> { fb.haptic(Haptic.Tick); store.pinBot(b.profile, !b.pinned, failed) }
            MenuAction.EditLook -> { studioFor = b.profile; studio = true }
            MenuAction.NewSession -> nav.push(Screen.Chat(store.newChatTarget(b.profile)))
            MenuAction.MarkRead -> store.markBotRead(b)
            MenuAction.Hide -> hide(b, !b.hidden)
            MenuAction.Delete -> deleteFor = b.profile
        }
    }

    CompositionLocalProvider(LocalHomeCues provides HomeCues(wakeAt, down, ripple)) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // Quick chat: labelled when the header has room, else the bolt alone (phones, the 380dp wide-layout pane).
                androidx.compose.foundation.layout.BoxWithConstraints { val roomy = maxWidth >= 440.dp
                ScreenHeader(s.tabBots) {
                    PcChip(link) { if (ready) nav.push(Screen.More(MorePage.Status)) else store.gateway.retryNow() }
                    app.buddyverse.ui.QuickChatButton(compact = !roomy)
                    IconButton(Icons.group, h.newGroup, { newRoom = true })
                    IconButton(Icons.add, s.newBot, ::newBot)
                }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        all.isEmpty() && !loaded && !down -> RosterSkeleton(placeholder)
                        all.isEmpty() && !loaded -> Hero(placeholder, Mood.Sleeping, s.retry) { store.gateway.retryNow() }
                        all.isEmpty() -> Hero(placeholder, Mood.Sleeping, h.makeFirstBot, ::newBot)
                        else -> {
                            LazyColumn(Modifier.fillMaxSize(), state = list) {
                                item(key = "stage") {
                                    Column(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                                        Stage(
                                            shown, parents, live, subagents, order,
                                            onOpenBot = { openChat(it) },
                                            onMenu = { menuFor = it.profile },
                                            onOpenHelper = ::openHelper,
                                            onPlay = { nav.togglePlay() },
                                        )
                                    }
                                }
                                item(key = "usage") {
                                    // Brain first: plan limits are read on its profile; tokens/cost add up every bot.
                                    val profiles = remember(polled) { polled.sortedByDescending { it.isDefault }.map { it.profile } }
                                    if (profiles.isNotEmpty()) UsageCard(profiles) { nav.push(Screen.More(MorePage.Insights)) }
                                }
                                if (asks.isNotEmpty()) {
                                    item(key = "needs") { SectionLabel(s.needsYou, motion(), c.needsYouText) }
                                    items(asks, key = { it.key }) { a ->
                                        AskCard(a, order[a.bot.profile] ?: 0, { v -> answer(a, v) }, { openChat(a.bot, a.target) }, motion())
                                    }
                                }
                                items(roster, key = { it.key }) { e ->
                                    when (e) {
                                        is BotEntry -> {
                                            val b = e.bot
                                            BotRow(
                                                b, live[b.profile] ?: BotLive(Mood.Idle, LineKind.Preview, ""), order[b.profile] ?: 0, now, b.hidden,
                                                onOpen = { openChat(b) },
                                                onMenu = { menuFor = b.profile },
                                                onHide = if (b.isDefault || b.hidden) null else ({ hide(b, true) }),
                                                modifier = motion(),
                                            )
                                        }
                                        is RoomEntry -> RoomRow(
                                            e.room, all.filter { it.profile in e.room.members }, live, now,
                                            { nav.push(Screen.GroupRoom(e.room)) }, motion(),
                                        )
                                    }
                                }
                                if (hiddenCount > 0) item(key = "hidden") { HiddenFooter(hiddenCount, showHidden, { showHidden = !showHidden }, motion()) }
                                item(key = "bottom") { Spacer(Modifier.navigationBarsPadding().height(96.dp)) }
                            }
                            val collapsed by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
                            androidx.compose.animation.AnimatedVisibility(
                                collapsed,
                                enter = slideInVertically(Motion.smooth()) { -it } + fadeIn(Motion.fade()),
                                exit = slideOutVertically(Motion.smooth()) { -it } + fadeOut(Motion.fade()),
                            ) { TeamStrip(shown, live, { openChat(it) }, { menuFor = it.profile }) }
                        }
                    }
                }
            }
            BotMenu(
                menuFor?.let { p -> all.firstOrNull { it.profile == p } }, menuFor?.let { live[it] }, menuFor?.let { subagents[it] }.orEmpty(),
                { menuFor = null }, ::act, ::openHelper,
            )
            DeleteBotSheet(deleteFor?.let { p -> all.firstOrNull { it.profile == p } }, { deleteFor = null }) { b ->
                deleteFor = null
                store.deleteBot(b.profile) { msg -> toasts.show(ToastData(msg ?: s.error)) }
                toasts.show(ToastData(h.botDeleted(b.name), buddy = b.spec, shot = OneShot.Leave))
            }
            BotProfileSheet(profileFor) { profileFor = null }
            AvatarStudio(studio, studioFor) { studio = false }
            NewRoomSheet(newRoom) { newRoom = false }
        }
    }
}

/** List insert/remove/move on the `smooth` spring with quick fades (design §5.1 "Lists"). */
private fun LazyItemScope.motion(): Modifier = Modifier.animateItem(Motion.fade(), Motion.smooth(), Motion.fade())

/** "(o) PC on" status chip, 48 dp touch: opens PC status when connected, retries at once when not. */
@Composable
private fun PcChip(link: LinkState, onClick: () -> Unit) {
    val c = Theme.colors
    val s = S.current
    val (dot, label) = when (link) {
        is LinkState.Ready -> c.success to s.pcOn
        is LinkState.Down -> c.error to s.pcOff
        else -> c.needsYou to s.connectWorking
    }
    Box(Modifier.heightIn(min = 48.dp).squish(onClick, label = label), contentAlignment = Alignment.Center) {
        Row(Modifier.height(36.dp).clip(CircleShape).background(c.surface2).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(dot, 8.dp)
            Spacer(Modifier.width(6.dp))
            Txt(label, Theme.type.caption, c.text, maxLines = 1)
        }
    }
}
