package app.buddyverse.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.Attachment
import app.buddyverse.core.ChatTarget
import app.buddyverse.core.LinkState
import app.buddyverse.core.RpcError
import app.buddyverse.core.SendMode
import app.buddyverse.core.SessionRow
import app.buddyverse.core.TempChats
import app.buddyverse.core.api.branch
import app.buddyverse.core.api.compress
import app.buddyverse.core.api.historyBefore
import app.buddyverse.core.api.react
import app.buddyverse.core.api.reopenChat
import app.buddyverse.core.api.rewind
import app.buddyverse.core.chat.BotItem
import app.buddyverse.core.chat.ChatItem
import app.buddyverse.core.chat.ChatState
import app.buddyverse.core.chat.Mark
import app.buddyverse.core.chat.NoticeItem
import app.buddyverse.core.chat.Prompt
import app.buddyverse.core.chat.SendState
import app.buddyverse.core.chat.StepStatus
import app.buddyverse.core.chat.Tone
import app.buddyverse.core.chat.UserItem
import app.buddyverse.core.chat.WorkItem
import app.buddyverse.core.nowMillis
import app.buddyverse.core.str
import app.buddyverse.design.Dot
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.home
import app.buddyverse.i18n.Strings
import app.buddyverse.i18n.chat
import app.buddyverse.ui.LocalNav
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.Screen
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.bot.BotProfileSheet
import app.buddyverse.ui.ltr
import app.buddyverse.ui.toolLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ transcript lines

private sealed interface Line { val key: String }
private data class ItemLine(val item: ChatItem, val buddy: Boolean) : Line { override val key: String get() = item.key }
private data class DayLine(override val key: String, val label: String) : Line
private data class PromptLine(val prompt: Prompt) : Line { override val key: String get() = "p-" + prompt.id }
private object TypingLine : Line { override val key: String = "typing" }
private object TopLine : Line { override val key: String = "top" }

private enum class Open { None, Sessions, Options, Model, Usage, Palette, Rename, Message, Text, Trace, Profile, Compress }

private fun rowIdOf(i: ChatItem): Long? = (i as? UserItem)?.rowId ?: (i as? BotItem)?.rowId

private const val DAY = 86_400_000L
private fun dayOf(ms: Long): Long = (ms + localOffsetMs(ms)).floorDiv(DAY)

private fun clock(ms: Long): String {
    val m = (ms + localOffsetMs(ms)).mod(DAY) / 60_000
    return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
}

/** Days since 1970 → "d.M.yyyy" (H. Hinnant's civil-from-days). */
private fun date(days: Long): String {
    val z = days + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    return "$d.$m.$y"
}

private fun dayLabel(ms: Long, now: Long, s: Strings): String {
    val d = dayOf(ms)
    val today = dayOf(now)
    val day = when (d) {
        today -> s.today
        today - 1 -> s.yesterday
        else -> ltr(date(d))
    }
    return "$day ${ltr(clock(ms))}"
}

/** Oldest → newest, then reversed for the bottom-anchored list. Day/time marks on a new day or after an hour's gap. */
private fun buildLines(items: List<ChatItem>, running: Boolean, prompts: List<Prompt>, showTop: Boolean, s: Strings): List<Line> {
    val out = ArrayList<Line>(items.size + prompts.size + 8)
    if (showTop) out += TopLine
    val now = nowMillis()
    val last = items.lastOrNull()
    // A running Work card already shows the buddy and the live step: no second "Run" line under it.
    val typing = running && !(last is BotItem && last.streaming) && !(last is WorkItem && last.running)
    val lastUser = items.indexOfLast { it is UserItem }
    val lastBot = items.indexOfLast { it is BotItem }
    val liveBot = if (!typing && lastBot > lastUser) lastBot else -1
    var prevAt = 0L
    items.forEachIndexed { i, item ->
        if (item.at > 0) {
            if (prevAt == 0L || dayOf(item.at) != dayOf(prevAt) || item.at - prevAt > 3_600_000L) out += DayLine("d-" + item.key, dayLabel(item.at, now, s))
            prevAt = item.at
        }
        out += ItemLine(item, buddy = i == liveBot)
    }
    if (typing) out += TypingLine
    for (p in prompts) out += PromptLine(p)
    return out.distinctBy { it.key }.asReversed()
}

/** design §4.3 priority: NeedsYou > Error > Talking > Working > Thinking > Listening > Idle (Offline when the PC is gone). */
private fun moodOf(st: ChatState, typing: Boolean, offline: Boolean): Mood = when {
    offline -> Mood.Offline
    st.prompts.isNotEmpty() -> Mood.NeedsYou
    st.running && st.streamingText != null -> Mood.Talking
    st.running && (st.items.lastOrNull() as? WorkItem)?.steps?.any { it.status == StepStatus.Running } == true -> Mood.Working
    st.running -> Mood.Thinking
    st.lastError != null -> Mood.Error
    typing -> Mood.Listening
    else -> Mood.Idle
}

private fun phraseText(p: String?, s: Strings): String? = p?.takeIf { it.isNotBlank() }?.let { if (' ' in it) it else toolLabel(it, null, s) }

// ------------------------------------------------------------------ screen

/** Chat with a bot (design §3.2): its Main chat, another session, or a new one. */
@Composable
fun ChatScreen(target: ChatTarget) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val toasts = LocalToasts.current
    val fb = LocalFeedback.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val s = S.current
    val sc = S.chat
    val c = Theme.colors
    val scope = rememberCoroutineScope()
    val profile = target.profile

    var chat by remember(target) { mutableStateOf(store.chat(target)) }
    val st by chat.state.collectAsState()
    val bots by store.bots.collectAsState()
    val sessions by store.sessions.collectAsState()
    val link by store.link.collectAsState()
    val bot = bots.firstOrNull { it.profile == profile }
    val name = bot?.name ?: profile
    val fallbackSpec = remember(profile) { BuddySpec(profile, profile, BuddyShape.parse(null, profile), BuddyColors.forName(profile)) }
    val spec = bot?.spec ?: fallbackSpec
    val streamSpec = remember(spec) { spec.copy(id = spec.id + "#reply") }
    // A fresh quick chat keeps no draft. It is a normal saved brain chat: leaving it never deletes or interrupts anything.
    val composer = rememberComposerState(if (target.temp) null else target.key)
    // A brain chat outside any project (see TempChats): the "Temp" tag and the Close button, fresh or reopened from Chats.
    val quick = target.temp || (!target.botChat && !target.watch && TempChats.isQuick(profile, st.sessionId, bots))

    var open by remember { mutableStateOf(Open.None) }
    LaunchedEffect(nav.palette) { if (nav.palette == target.key) { nav.palette = null; open = Open.Palette } } // Ctrl+K
    var msg by remember { mutableStateOf<ChatItem?>(null) }
    var traceWorks by remember { mutableStateOf<List<WorkItem>>(emptyList()) }
    var editing by remember(chat) { mutableStateOf<UserItem?>(null) }
    var titleOverride by remember(chat) { mutableStateOf<String?>(null) }
    var pickedModel by remember(chat) { mutableStateOf<String?>(null) }
    val reactions = remember(chat) { mutableStateMapOf<String, String>() }
    LaunchedEffect(st.model) { pickedModel = null }

    // ---- the buddy: mood, one-shots, listening
    val typing by produceState(false, composer.typedAt) {
        val left = 3_000 - (nowMillis() - composer.typedAt)
        value = composer.typedAt > 0 && left > 0
        if (value) { delay(left); value = false }
    }
    val mood = moodOf(st, typing, link is LinkState.Down)
    var cueSerial by remember { mutableLongStateOf(0L) }
    var cue by remember { mutableStateOf<BuddyCue?>(null) }
    fun fire(shot: OneShot) { cueSerial += 1; cue = BuddyCue(cueSerial, shot) }
    LaunchedEffect(target) { delay(280); fire(OneShot.Greeting) }
    // Warm the "/" palette's command list (it is cached per bot), so the first open isn't "Loading…".
    LaunchedEffect(profile, st.loaded) { if (st.loaded && !target.watch) store.catalog(profile, chat.sessionId) }
    var wasRunning by remember(chat) { mutableStateOf(st.running) }
    LaunchedEffect(st.running) {
        if (wasRunning && !st.running && st.lastError == null) fire(OneShot.Done)
        wasRunning = st.running
    }
    var lastHelpers by remember(chat) { mutableIntStateOf(st.activeSubagents) }
    LaunchedEffect(st.activeSubagents) {
        if (st.activeSubagents > lastHelpers) fire(OneShot.Spawn) else if (st.activeSubagents < lastHelpers) fire(OneShot.Return)
        lastHelpers = st.activeSubagents
    }
    val todosDone = st.todos.count { it.status == "completed" }
    var lastDone by remember(chat) { mutableIntStateOf(todosDone) }
    LaunchedEffect(todosDone) { if (todosDone > lastDone) fire(OneShot.Nod); lastDone = todosDone }

    // ---- older history (scroll to the top pages it in)
    var older by remember(chat) { mutableStateOf<List<ChatItem>>(emptyList()) }
    var olderDone by remember(chat) { mutableStateOf(false) }
    var olderBusy by remember(chat) { mutableStateOf(false) }
    val minLive = st.items.mapNotNull(::rowIdOf).minOrNull()
    val shownOlder = if (minLive == null) older else older.filter { o -> rowIdOf(o)?.let { it < minLive } ?: true }
    val allItems = if (shownOlder.isEmpty()) st.items else shownOlder + st.items
    val lines = remember(allItems, st.running, st.prompts, olderDone, st.loaded, s) {
        buildLines(allItems, st.running, st.prompts, st.loaded && !olderDone && allItems.any { rowIdOf(it) != null }, s)
    }
    val listState = rememberLazyListState()
    val atTop by remember(listState) {
        derivedStateOf { val info = listState.layoutInfo; info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 1 }
    }
    LaunchedEffect(atTop, st.loaded, olderDone, older.size, chat) {
        if (!atTop || !st.loaded || olderDone || olderBusy) return@LaunchedEffect
        val sid = chat.sessionId
        val first = (older + chat.state.value.items).mapNotNull(::rowIdOf).minOrNull()
        if (sid == null || first == null) { olderDone = true; return@LaunchedEffect }
        olderBusy = true
        try {
            val page = store.api.historyBefore(sid, profile, first)
            if (page.items.isEmpty()) olderDone = true else older = page.items + older
            if (!page.hasOlder) olderDone = true
        } catch (e: CancellationException) { throw e } catch (e: Exception) { olderDone = true } finally { olderBusy = false }
    }

    // ---- stick to the newest message unless the user scrolled up; count what they missed
    var unseen by remember(chat) { mutableIntStateOf(0) }
    val nearBottom by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    val newestKey = lines.firstOrNull()?.key
    val newestMine = ((lines.firstOrNull() as? ItemLine)?.item as? UserItem)?.state == SendState.Sending
    LaunchedEffect(newestKey) {
        if (newestKey == null) return@LaunchedEffect
        if (newestMine || listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0) else unseen++
    }
    LaunchedEffect(nearBottom) { if (nearBottom) unseen = 0 }

    // ---- which items are new this session (they grow out of the avatar)
    val seen = remember(chat) { HashSet<String>() }
    var primed by remember(chat) { mutableStateOf(false) }
    LaunchedEffect(chat, st.loaded) { if (st.loaded && !primed) { st.items.forEach { seen += it.key }; primed = true } }

    // ---- intents
    fun toast(text: String) = toasts.show(ToastData(text))
    fun errText(e: Exception): String = (e as? RpcError)?.let { it.data.str("detail") ?: it.reason } ?: e.message ?: s.error
    val currentRow: SessionRow? = st.sessionId?.let { sid ->
        sessions.rows.firstOrNull { it.id == sid } ?: SessionRow(sid, profile, st.title, null, nowMillis() / 1000.0, st.running, false, false, null, 0)
    }

    fun busy(): Boolean = chat.state.value.running.also { if (it) toast(sc.busy) }

    fun rewindThen(rowId: Long, resend: String?) {
        val sid = chat.sessionId ?: return
        if (busy()) return
        scope.launch {
            try {
                val now = chat.state.value
                val removed = store.api.rewind(sid, profile, rowId, now.revision, now.generation)
                val fresh = store.reopenChat(chat)
                chat = fresh
                if (resend != null) fresh.send(resend) else composer.set(removed.orEmpty())
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fb.haptic(Haptic.Reject); toast(errText(e)) }
        }
    }

    fun lastUserRow(): UserItem? = chat.state.value.items.lastOrNull { it is UserItem && it.rowId != null } as? UserItem

    fun compressNow(focus: String?) {
        val sid = chat.sessionId ?: return
        if (busy()) return
        toast(sc.compressing)
        scope.launch {
            try {
                val now = chat.state.value
                if (store.api.compress(sid, profile, focus, now.revision, now.generation) == "noop") toast(sc.nothingToCompress)
                else { chat = store.reopenChat(chat); fb.haptic(Haptic.Confirm); toast(sc.compressed) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(errText(e)) }
        }
    }

    fun branchFrom(rowId: Long?) {
        val sid = chat.sessionId ?: return
        if (busy()) return
        scope.launch {
            try {
                val now = chat.state.value
                val child = store.api.branch(sid, profile, rowId, now.revision, now.generation) ?: throw IllegalStateException(s.error)
                fb.haptic(Haptic.Confirm)
                nav.push(Screen.Chat(ChatTarget(profile, child, botChat = false)))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(errText(e)) }
        }
    }

    fun closeRow(row: SessionRow, current: Boolean) {
        store.closeSession(row)
        fb.haptic(Haptic.Confirm)
        if (current) nav.replaceTop(Screen.Chat(ChatTarget(profile)))
        toasts.show(ToastData(s.sessionClosed, s.undo, {
            store.undoClose(row)
            if (current) nav.replaceTop(Screen.Chat(ChatTarget(profile, row.id, botChat = false)))
        }, spec, OneShot.Wave))
    }

    /** Quick chat's Close: archive (undoable, never deleted) and go back; nothing sent yet = just go back. */
    fun closeQuick() {
        val row = currentRow
        if (row != null) {
            store.closeSession(row)
            fb.haptic(Haptic.Confirm)
            toasts.show(ToastData(s.sessionClosed, s.undo, { store.undoClose(row) }, spec, OneShot.Wave))
        }
        nav.pop()
    }

    fun newSession() { open = Open.None; nav.replaceTop(Screen.Chat(store.newChatTarget(profile))) }

    fun onSend(text: String, mode: SendMode, files: List<Attachment>) {
        val e = editing
        val rid = e?.rowId
        if (e != null && rid != null && files.isEmpty()) { editing = null; rewindThen(rid, text.trim()); return }
        editing = null
        val t = text.trim()
        if (files.isEmpty() && mode != SendMode.Aside && t.startsWith("/")) {
            val cmd = t.substringBefore(' ').lowercase()
            val arg = t.substringAfter(' ', "").trim().ifEmpty { null }
            when (cmd) {
                "/new", "/reset" -> if (arg == null) { newSession(); return }
                "/model" -> if (arg == null) { open = Open.Model; return }
                "/usage", "/cost", "/context" -> if (arg == null) { open = Open.Usage; return }
                "/compress", "/compact" -> { compressNow(arg); return }
                "/branch", "/fork" -> if (arg == null) { branchFrom(null); return }
                "/retry" -> { lastUserRow()?.let { u -> rewindThen(u.rowId!!, u.text) }; return }
                "/undo" -> if (arg == null) { lastUserRow()?.let { u -> rewindThen(u.rowId!!, null) }; return }
            }
        }
        chat.send(text, mode, files)
    }

    fun openMessage(i: ChatItem) { msg = i; open = Open.Message; fb.haptic(Haptic.LongPress) }

    fun react(i: ChatItem, emoji: String) {
        val prev = reactions[i.key]
        val next = if (prev == emoji) null else emoji
        if (next == null) reactions.remove(i.key) else reactions[i.key] = next
        open = Open.None
        val sid = chat.sessionId ?: return
        val items = chat.state.value.items
        val rowId = rowIdOf(i)
        val role = if (i is BotItem) "assistant" else "user"
        val newest = items.lastOrNull { if (i is BotItem) it is BotItem else it is UserItem }?.key == i.key
        if (rowId == null && !newest) return
        scope.launch {
            runCatching { store.api.react(sid, profile, rowId, role, next) }.onFailure {
                if (it is CancellationException) throw it
                if (prev == null) reactions.remove(i.key) else reactions[i.key] = prev
            }
        }
    }

    fun traceOf(i: ChatItem): List<WorkItem> {
        val idx = allItems.indexOfFirst { it.key == i.key }
        if (idx <= 0) return emptyList()
        val start = allItems.subList(0, idx).indexOfLast { it is UserItem } + 1
        return allItems.subList(start, idx).filterIsInstance<WorkItem>()
    }

    fun onMsgAction(a: MsgAction) {
        val i = msg ?: return
        val text = (i as? BotItem)?.text ?: (i as? UserItem)?.text ?: (i as? NoticeItem)?.text.orEmpty()
        open = Open.None
        when (a) {
            MsgAction.Copy -> { clipboard.setText(AnnotatedString(text)); toast(sc.copied) }
            MsgAction.Reply -> composer.quote(text)
            MsgAction.Select -> open = Open.Text
            MsgAction.Trace -> { traceWorks = traceOf(i); open = Open.Trace }
            MsgAction.Edit -> { val u = i as? UserItem; if (u != null) { editing = u; composer.set(u.text) } }
            MsgAction.Regenerate -> {
                val idx = allItems.indexOfFirst { it.key == i.key }
                val u = allItems.subList(0, idx.coerceAtLeast(0)).lastOrNull { it is UserItem && it.rowId != null } as? UserItem
                val rid = u?.rowId
                if (u != null && rid != null) rewindThen(rid, u.text)
            }
            MsgAction.Branch -> branchFrom(rowIdOf(i))
        }
    }

    // ---- layout
    val sessionTitle = when {
        target.botChat -> s.mainChat
        st.sessionId == null -> s.newSession
        else -> titleOverride ?: st.title?.takeIf { it.isNotBlank() } ?: currentRow?.title ?: s.untitled
    }
    val empty = st.loaded && allItems.isEmpty() && !st.running && st.prompts.isEmpty()

    Box(Modifier.fillMaxSize().attachDrop(composer)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            // Header (64): back, live buddy, name + session, sessions chip, menu.
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(Icons.arrowBack, s.back, { nav.pop() }, mirror = true)
                Buddy(spec, mood, 44.dp, cue = cue, talkAt = st.lastDeltaAt, talkChars = st.lastDeltaChars, lookAtComposer = typing, onTap = { open = Open.Profile })
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f).squish({ open = Open.Profile }, scale = .97f, label = name)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(name, Theme.type.headline, c.text, Modifier.weight(1f, fill = false), maxLines = 1)
                        if (quick) {
                            Spacer(Modifier.width(6.dp))
                            Row(Modifier.clip(CircleShape).background(c.surface2).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.bolt, c.secondary, 14.dp)
                                Spacer(Modifier.width(4.dp))
                                Txt(s.generalChat, Theme.type.micro, c.secondary, maxLines = 1)
                            }
                        }
                    }
                    Crossfade(if (st.running) "$sessionTitle · ${sc.working}" else sessionTitle, animationSpec = tween(200), label = "sub") { t ->
                        Txt(t, Theme.type.caption, if (st.running) c.working else c.secondary, maxLines = 1)
                    }
                }
                Row(
                    Modifier.heightIn(min = 48.dp).clip(CircleShape).squish({ open = Open.Sessions }, label = s.sessions).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.height(40.dp).clip(CircleShape).background(c.surface2).padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.forum, c.text, 20.dp)
                        Icon(Icons.expandMore, c.secondary, 18.dp)
                    }
                }
                if (quick) app.buddyverse.design.Chip(s.closeChat, false, { closeQuick() }, icon = Icons.close)
                // Wide window: a small Play toggle (the games dock beside the chat). Phones get it in the .. menu.
                if (nav.wide) IconButton(Icons.sportsEsports, S.home.play, { nav.togglePlay() }, tint = if (nav.play) c.accent else c.secondary, size = 40.dp, iconSize = 20.dp)
                IconButton(Icons.moreHoriz, sc.options, { open = Open.Options })
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    Modifier.fillMaxSize(), state = listState, reverseLayout = true,
                    contentPadding = PaddingValues(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
                ) {
                    items(lines, key = { it.key }, contentType = { l ->
                        when (l) {
                            is ItemLine -> when (l.item) { is UserItem -> 1; is BotItem -> 2; is WorkItem -> 3; else -> 4 }
                            is DayLine -> 5
                            is PromptLine -> 6
                            else -> 7
                        }
                    }) { line ->
                        when (line) {
                            is DayLine -> Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) { Txt(line.label, Theme.type.micro, c.tertiary, maxLines = 1) }
                            is TopLine -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { if (olderBusy) Spinner(20.dp, c.tertiary) }
                            is TypingLine -> TypingView(streamSpec, mood, phraseText(st.phrase, s) ?: s.thinking, typing)
                            is PromptLine -> PromptCard(line.prompt, { v -> chat.answer(line.prompt, v) }, bot = bot)
                            is ItemLine -> when (val i = line.item) {
                                is UserItem -> UserBubble(i, reactions[i.key], { openMessage(i) }, { chat.retry(i.key) }, { chat.discard(i.key) })
                                is BotItem -> {
                                    val fresh = remember(i.key) { primed && nowMillis() - i.at < 5_000 && seen.add(i.key) }
                                    BotBubble(i, profile, streamSpec, line.buddy, if (line.buddy) mood else Mood.Idle, st.lastDeltaAt, st.lastDeltaChars, typing, fresh, reactions[i.key]) { openMessage(i) }
                                }
                                is WorkItem -> WorkCard(i, spec, if (i.running) st.phrase else null, chat.sessionId, profile)
                                is NoticeItem -> NoticeView(i) { openMessage(i) }
                            }
                        }
                    }
                }
                when {
                    !st.loaded && allItems.isEmpty() -> CenterBuddy(spec, Mood.Thinking, s.loading)
                    empty -> CenterBuddy(spec, if (typing) Mood.Listening else Mood.Idle, s.greetingFirst, lookAtComposer = typing)
                }
                JumpPill(!nearBottom, unseen, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) { scope.launch { listState.animateScrollToItem(0) } }
            }

            // Status stack above the composer: aside answer, plan, queued messages, editing.
            // One card per /btw or /background answer, each dismissed on its own (newest at the bottom).
            for (a in st.sideAnswers) key(a.taskId) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface2).padding(start = 16.dp, top = 6.dp)) {
                    Column(Modifier.weight(1f).heightIn(max = if (st.sideAnswers.size > 1) 140.dp else 220.dp).verticalScroll(rememberScrollState()).padding(bottom = 10.dp)) {
                        Txt(listOfNotNull(if (a.background) s.background else s.sideAnswer, a.question?.takeIf { it.isNotBlank() }).joinToString(" · "),
                            Theme.type.caps, c.secondary, Modifier.padding(top = 6.dp, bottom = 4.dp), maxLines = 1)
                        MdText(a.text, Theme.type.sub, c.text, c.surface)
                    }
                    IconButton(Icons.close, s.close, { chat.dismissAside(a.taskId) })
                }
            }
            if (st.todos.isNotEmpty()) PlanCard(st.todos, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            for (q in st.queued) Row(
                Modifier.padding(horizontal = 12.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface2).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.schedule, c.secondary, 18.dp)
                Spacer(Modifier.width(8.dp))
                Txt(q.text, Theme.type.sub, c.text, Modifier.weight(1f), maxLines = 1)
                if (q.canSteer) IconButton(Icons.send, s.sendNow, { chat.steerQueued(q); fb.haptic(Haptic.Confirm) }, tint = c.accent)
                IconButton(Icons.close, s.cancel, { chat.cancelQueued(q) })
            }
            editing?.let { e ->
                Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface2).padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.edit, c.accent, 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Txt(e.text, Theme.type.sub, c.secondary, Modifier.weight(1f), maxLines = 1)
                    IconButton(Icons.close, s.cancel, { editing = null; composer.clear() })
                }
            }
            if (target.watch) Txt(S.chat.helperReadOnly, Theme.type.caption, c.secondary,
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp), maxLines = 1)
            else Composer(
                hint = s.message(name),
                running = st.running,
                onSend = ::onSend,
                onStop = { chat.stop() },
                modifier = Modifier.navigationBarsPadding(),
                state = composer,
                profile = profile,
                onSlash = { open = Open.Palette },
                model = pickedModel ?: st.model,
                onModel = { open = Open.Model },
            )
        }

        // Sheets: each closes by back, scrim and its ×.
        SessionSheet(
            open == Open.Sessions, profile, name, bot?.botChatId, bot?.liveSessions.orEmpty(), st.sessionId, target.botChat,
            onDismiss = { open = Open.None },
            onOpen = { t -> open = Open.None; if (t.key != target.key && !(t.sessionId != null && t.sessionId == st.sessionId)) nav.replaceTop(Screen.Chat(t)) },
            onClose = { r -> closeRow(r, r.id == st.sessionId) },
            onNew = ::newSession,
        )
        OptionsSheet(open == Open.Options, target.botChat, st.sessionId != null, st.running, { open = Open.None }) { a ->
            open = Open.None
            when (a) {
                ChatAction.NewSession -> newSession()
                ChatAction.Model -> open = Open.Model
                ChatAction.Usage -> open = Open.Usage
                ChatAction.Rename -> open = Open.Rename
                ChatAction.Branch -> branchFrom(null)
                ChatAction.Compress -> open = Open.Compress
                ChatAction.Stop -> chat.stop()
                ChatAction.Close -> currentRow?.let { closeRow(it, true) }
                ChatAction.Delete -> currentRow?.let { r -> store.deleteSession(r); nav.replaceTop(Screen.Chat(ChatTarget(profile))) }
                ChatAction.Play -> nav.togglePlay()
            }
        }
        RenameSheet(open == Open.Rename, sessionTitle, { open = Open.None }) { t ->
            open = Open.None
            titleOverride = t
            currentRow?.let { store.renameSession(it, t) }
        }
        ModelSheet(open == Open.Model, chat, { open = Open.None }) { m -> pickedModel = m }
        ChatUsageSheet(open == Open.Usage, st.sessionId, profile, { open = Open.None })
        // Compress can't be undone: ask first (a stray tap must never compact the chat).
        app.buddyverse.design.Sheet(open == Open.Compress, { open = Open.None }, sc.compressAsk) {
            Txt(sc.compressBody, Theme.type.callout, c.secondary, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                app.buddyverse.design.PillButton(s.cancel, { open = Open.None }, Modifier.weight(1f), kind = app.buddyverse.design.ButtonKind.Outline)
                app.buddyverse.design.PillButton(sc.compressGo, { open = Open.None; compressNow(null) }, Modifier.weight(1f), haptic = Haptic.Confirm)
            }
        }
        CommandPalette(open == Open.Palette, profile, st.sessionId, { open = Open.None },
            onRun = { cmd -> onSend(cmd, SendMode.Auto, emptyList()) },
            onInsert = { text -> composer.set(text); composer.typedAt = nowMillis() })
        val m = msg
        val items = st.items
        MessageSheet(
            visible = open == Open.Message,
            mine = m is UserItem,
            reaction = m?.let { reactions[it.key] },
            canEdit = m is UserItem && m.rowId != null && !st.running,
            canRegenerate = m is BotItem && !st.running && items.lastOrNull { it is BotItem }?.key == m.key,
            canBranch = m != null && rowIdOf(m) != null && !st.running,
            hasTrace = open == Open.Message && m is BotItem && traceOf(m).isNotEmpty(),
            onDismiss = { open = Open.None },
            onReact = { e -> m?.let { react(it, e) } },
            onAction = ::onMsgAction,
        )
        TextSheet(open == Open.Text, (m as? BotItem)?.text ?: (m as? UserItem)?.text ?: (m as? NoticeItem)?.text.orEmpty(), { open = Open.None })
        TraceSheet(open == Open.Trace, traceWorks, spec, chat.sessionId, profile, { open = Open.None })
        BotProfileSheet(if (open == Open.Profile) profile else null) { open = Open.None }
    }
}

// ------------------------------------------------------------------ pieces

/** "↓ N new" (design §5.1 Lists): shows when scrolled away from the newest message. */
@Composable
private fun JumpPill(visible: Boolean, unseen: Int, modifier: Modifier, onClick: () -> Unit) {
    val c = Theme.colors
    val label = S.current.newMessages(unseen)
    AnimatedVisibility(visible, modifier, enter = scaleIn(Motion.bouncy()) + fadeIn(Motion.fade()), exit = scaleOut(Motion.snappy()) + fadeOut(Motion.fade())) {
        Row(
            Modifier.heightIn(min = 48.dp).shadow(10.dp, CircleShape).clip(CircleShape).background(c.surface).squish(onClick, label = label).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.expandMore, c.text, 22.dp)
            if (unseen > 0) { Spacer(Modifier.width(6.dp)); Txt(label, Theme.type.caption, c.text, maxLines = 1) }
        }
    }
}

@Composable
private fun CenterBuddy(spec: BuddySpec, mood: Mood, text: String, lookAtComposer: Boolean = false) {
    Column(Modifier.fillMaxSize().padding(bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        Buddy(spec, mood, 112.dp, lookAtComposer = lookAtComposer)
        Spacer(Modifier.height(14.dp))
        Txt(text, Theme.type.callout, Theme.colors.secondary, maxLines = 2)
    }
}

@Composable
private fun TypingView(spec: BuddySpec, mood: Mood, phrase: String, typing: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.BottomCenter) { Buddy(spec, mood, 36.dp, lookAtComposer = typing) }
        Spacer(Modifier.width(8.dp))
        Crossfade(phrase, animationSpec = tween(320), label = "phrase") { p -> Txt(p, Theme.type.sub, Theme.colors.secondary, maxLines = 1) }
    }
}

@Composable
private fun Reaction(e: String, alignEnd: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.padding(top = 2.dp, start = if (alignEnd) 0.dp else 48.dp).clip(CircleShape).background(Theme.colors.surface2).padding(horizontal = 8.dp, vertical = 2.dp)) {
            Txt(e, Theme.type.callout, Theme.colors.text, maxLines = 1)
        }
    }
}

/**
 * A message bubble's actions menu without stealing text selection (the [SelectionContainer] inside owns press, drag, double-click).
 * Touch: quick tap opens it (hold selects). Mouse: right-click opens it (left click/drag/double-click select, like any PC app).
 * Watches the Initial pass without consuming, so selection still gets every event; a move past slop or a hold is no tap.
 */
private fun Modifier.quickTap(onTap: () -> Unit): Modifier = composed {
    val tap = rememberUpdatedState(onTap)
    pointerInput(Unit) {
        awaitEachGesture {
            val first = awaitPointerEvent(PointerEventPass.Initial)
            val down = first.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: return@awaitEachGesture
            if (down.type == PointerType.Mouse) { if (first.buttons.isSecondaryPressed) tap.value(); return@awaitEachGesture }
            val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val ch = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                    if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                    if (!ch.pressed) return@withTimeoutOrNull true
                }
                @Suppress("UNREACHABLE_CODE") false
            }
            if (up == true) tap.value()
        }
    }
}

@Composable
private fun UserBubble(i: UserItem, reaction: String?, onLong: () -> Unit, onRetry: () -> Unit, onDiscard: () -> Unit) {
    val c = Theme.colors
    val s = S.current
    val failed = i.state == SendState.Failed
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalAlignment = Alignment.End) {
        Box(Modifier.fillMaxWidth(.8f), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.clip(RoundedCornerShape(22.dp)).background(if (failed) c.error else c.accentFill)
                    .quickTap(onLong).padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                for (a in i.attachments) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    Icon(Icons.attachFile, c.onAccent, 16.dp)
                    Spacer(Modifier.width(4.dp))
                    Txt(a, Theme.type.caption, c.onAccent, maxLines = 1)
                }
                SelectionContainer { MdText(i.text, Theme.type.body, c.onAccent, Color.Black.copy(alpha = .18f)) }
            }
        }
        if (reaction != null) Reaction(reaction, alignEnd = true)
        when (i.state) {
            SendState.Sending -> Dot(c.tertiary, 6.dp, Modifier.padding(top = 5.dp, end = 12.dp))
            SendState.Queued -> Txt(s.queued, Theme.type.micro, c.secondary, Modifier.padding(top = 3.dp, end = 8.dp), maxLines = 1)
            SendState.Steered -> Txt(s.steered, Theme.type.micro, c.accent, Modifier.padding(top = 3.dp, end = 8.dp), maxLines = 1)
            SendState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(s.failed, Theme.type.micro, c.errorText, maxLines = 1)
                IconButton(Icons.refresh, s.retrySend, onRetry, tint = c.errorText)
                IconButton(Icons.delete, s.delete, onDiscard, tint = c.secondary)
            }
            SendState.Sent -> Unit
        }
    }
}

@Composable
private fun BotBubble(
    i: BotItem, profile: String, spec: BuddySpec, buddy: Boolean, mood: Mood, talkAt: Long, talkChars: Int, typing: Boolean, fresh: Boolean,
    reaction: String?, onLong: () -> Unit,
) {
    val c = Theme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val grow = remember(i.key) { Animatable(if (fresh) 0f else 1f) }
    LaunchedEffect(i.key) { if (grow.value < 1f) grow.animateTo(1f, Motion.replyIn()) }
    // `MEDIA:/path` lines become file cards under the text.
    val media = remember(i.text) { splitMedia(i.text) }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.BottomCenter) {
                if (buddy) Buddy(spec, mood, 36.dp, talkAt = talkAt, talkChars = talkChars, lookAtComposer = typing)
            }
            Spacer(Modifier.width(6.dp))
            Column(
                Modifier.weight(1f, fill = false).widthIn(max = 620.dp)
                    .graphicsLayer {
                        val p = grow.value
                        scaleX = .84f + .16f * p; scaleY = .84f + .16f * p
                        translationY = (1f - p) * 8.dp.toPx()
                        alpha = p
                        transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 1f)
                    }
                    .clip(RoundedCornerShape(22.dp)).background(c.surface2)
                    .quickTap(onLong)
                    .animateContentSize(Motion.smooth())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                if (media.text.isNotBlank() || media.paths.isEmpty()) SelectionContainer { MdText(media.text, Theme.type.body, c.text, c.ground) }
                MediaFiles(media.paths, profile)
            }
            Spacer(Modifier.width(28.dp))
        }
        if (reaction != null) Reaction(reaction, alignEnd = false)
    }
}

@Composable
private fun NoticeView(n: NoticeItem, onLong: () -> Unit) {
    val c = Theme.colors
    val color = when (n.tone) { Tone.Info -> c.secondary; Tone.Warn -> c.needsYouText; Tone.Error -> c.errorText }
    val mark = n.mark
    if (mark != null) {
        // Compaction / helper finished / job finished: a quiet divider, never the engine's own text.
        val label = when (mark) { Mark.Summary -> S.chat.summarized; Mark.HelperDone -> S.chat.helperDone; Mark.JobDone -> S.chat.jobDone }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(1.dp).background(c.hairline))
            Txt(label, Theme.type.micro, c.tertiary, Modifier.padding(horizontal = 10.dp), maxLines = 1)
            Box(Modifier.weight(1f).height(1.dp).background(c.hairline))
        }
        return
    }
    val text = n.oops?.let { S.current.oops(it) } ?: n.text
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(c.surface2).squish({}, scale = .98f, haptic = null, onLongPress = onLong).padding(horizontal = 14.dp, vertical = 8.dp)) {
            MdText(text, Theme.type.caption, color, c.surface)
        }
    }
}
