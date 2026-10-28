package app.buddyverse.ui.chats

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.ChatTarget
import app.buddyverse.core.LinkState
import app.buddyverse.core.SessionRow
import app.buddyverse.core.api.SearchHit
import app.buddyverse.core.Hermes
import app.buddyverse.core.api.searchSessions
import app.buddyverse.core.api.sessionText
import app.buddyverse.core.nowMillis
import app.buddyverse.design.BackHandler
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Chip
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.chats
import app.buddyverse.ui.LocalNav
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.Screen
import app.buddyverse.ui.ScreenHeader
import app.buddyverse.ui.ToastData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState

/** All = your chats; Background = internal work (one-shots, tools, helpers, routine runs), kept out of All on purpose (design §3.4). */
private enum class Filter { All, Live, Archived, Background }

/** A list line: a section label, a session, or the "still searching" footer. */
private sealed interface Line { val key: String }
private data class Header(val text: String) : Line { override val key: String get() = "h:$text" }
private data class Item(val row: SessionRow, val snippet: String? = null) : Line { override val key: String get() = row.id }
private data object Searching : Line { override val key: String get() = "searching" }

/** What the long press opened. */
private sealed interface Pop { val row: SessionRow }
private class MenuPop(override val row: SessionRow) : Pop
private class RenamePop(override val row: SessionRow) : Pop
private class DeletePop(override val row: SessionRow) : Pop

/** Room under a tab-level sheet's last button for the floating tab bar (68 + 8 margin), until the bar hides behind sheets. */
private val TabBarClearance = 84.dp

/**
 * Chats tab (design §3.4): every session, newest first, in Pinned · Today · Yesterday · Earlier.
 * Filters All · Live · Archived · Background; search (titles at once, then the server's full-text); swipe toward start = Close with undo;
 * long press = Rename · Pin · Copy as text · Close · Delete (asks first). Archived rows swipe back with Restore.
 */
@Composable
fun ChatsScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val toasts = LocalToasts.current
    @Suppress("DEPRECATION") val clip = LocalClipboardManager.current
    val s = S.current
    val cs = S.chats
    val c = Theme.colors
    val sessions by store.sessions.collectAsState()
    val bots by store.bots.collectAsState()
    val botsBy = remember(bots) { bots.associateBy { it.profile } }
    val live = remember(bots) { bots.flatMapTo(HashSet()) { it.liveSessions } }
    var filter by remember { mutableStateOf(Filter.All) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>?>(emptyList()) }
    var openId by remember { mutableStateOf<String?>(null) }
    var pop by remember { mutableStateOf<Pop?>(null) }
    var now by remember { mutableLongStateOf(nowMillis()) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (store.link.value is LinkState.Ready) store.refreshSessions()
        while (true) { delay(30_000); now = nowMillis() }
    }
    LaunchedEffect(nav.reselect) { if (nav.reselect > 0) list.animateScrollToItem(0) }
    LaunchedEffect(filter) { openId = null; list.scrollToItem(0) }
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress) openId = null }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { hits = emptyList(); return@LaunchedEffect }
        hits = null
        delay(250)
        hits = store.searchSessions(q, (bots.map { it.profile } + sessions.rows.map { it.profile }).toSet().ifEmpty { setOf("default") })
    }
    fun closeSearch() { searching = false; query = "" }
    BackHandler(searching) { closeSearch() }

    fun specOf(profile: String): BuddySpec = botsBy[profile]?.spec ?: fallbackSpec(profile)
    fun close(r: SessionRow) {
        store.closeSession(r)
        toasts.show(ToastData(s.sessionClosed, s.undo, { store.undoClose(r) }, specOf(r.profile), OneShot.Wave))
    }
    fun restore(r: SessionRow) {
        store.unarchive(r)
        toasts.show(ToastData(cs.restored, s.undo, { store.closeSession(r) }, specOf(r.profile), OneShot.Return))
    }
    fun delete(r: SessionRow) {
        store.deleteSession(r)
        toasts.show(ToastData(cs.deleted, buddy = specOf(r.profile), shot = OneShot.Leave))
    }
    fun copy(r: SessionRow) {
        scope.launch {
            try {
                val text = store.api.sessionText(r.id, r.profile, cs.me, botsBy[r.profile]?.name ?: r.profile)
                if (text.isEmpty()) { toasts.show(ToastData(s.nothingHere)); return@launch }
                clip.setText(AnnotatedString(text))
                toasts.show(ToastData(cs.copied, buddy = specOf(r.profile), shot = OneShot.Nod))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { toasts.show(ToastData(e.message ?: s.failed)) }
        }
    }

    val q = query.trim()
    val inSearch = searching && q.isNotEmpty()
    val offset = utcOffsetMs(now)
    val lines: List<Line> = buildList<Line> {
        if (inSearch) {
            // Internal work (one-shots, routine runs…) shows up in search only under Background.
            val pool = if (filter == Filter.Background) sessions.background else sessions.rows + sessions.archived
            val local = pool.filter { r ->
                listOfNotNull(r.title, r.preview, botsBy[r.profile]?.name, r.profile).any { it.contains(q, ignoreCase = true) }
            }
            val internal = Hermes.INTERNAL_SOURCES.split(',')
            val found = hits.orEmpty().filter { h -> (h.row.source in internal) == (filter == Filter.Background) }
            val snippets = found.associate { it.row.id to it.snippet }
            local.forEach { add(Item(it, snippets[it.id])) }
            val seen = local.mapTo(HashSet()) { it.id }
            found.filter { it.row.id !in seen }.forEach { add(Item(it.row, it.snippet)) }
            if (hits == null) add(Searching)
        } else when (filter) {
            Filter.All -> sectioned(sessions.rows, now, offset, listOf(cs.pinned, s.today, s.yesterday, cs.earlier))
            Filter.Archived -> sectioned(sessions.archived, now, offset, listOf(cs.pinned, s.today, s.yesterday, cs.earlier))
            Filter.Live -> liveRows(bots, sessions.rows + sessions.archived + sessions.background).forEach { add(Item(it)) }
            Filter.Background -> sessions.background.sortedByDescending { it.lastActive }.forEach { add(Item(it)) }
        }
    }.distinctBy { it.key } // a duplicate key would crash the list
    val brain = (bots.firstOrNull { it.isDefault } ?: bots.firstOrNull())?.spec ?: fallbackSpec("default")
    val liveCount = live.size
    val bgIds = remember(sessions.background) { sessions.background.mapTo(HashSet()) { it.id } }
    val bgLabel = if (bgIds.isNotEmpty()) "${cs.background} · ${bgIds.size}" else cs.background
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(s.tabChats) {
                app.buddyverse.ui.QuickChatButton()
                IconButton(if (searching) Icons.close else Icons.search, if (searching) s.close else s.search, {
                    if (searching) closeSearch() else { openId = null; searching = true }
                })
            }
            AnimatedVisibility(searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Field(query, { query = it }, cs.searchHint, Modifier.padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus), shape = CircleShape)
            }
            AnimatedVisibility(!searching) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(s.all, filter == Filter.All, { filter = Filter.All })
                    Chip(if (liveCount > 0) "${s.live} · $liveCount" else s.live, filter == Filter.Live, { filter = Filter.Live })
                    Chip(s.archived, filter == Filter.Archived, { filter = Filter.Archived })
                    Chip(bgLabel, filter == Filter.Background, { filter = Filter.Background })
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    !sessions.loaded && sessions.rows.isEmpty() -> EmptyState(brain, Mood.Thinking, s.loading)
                    lines.isEmpty() -> when {
                        inSearch -> EmptyState(brain, Mood.Idle, cs.noMatches)
                        filter == Filter.Live -> EmptyState(brain, Mood.Sleeping, cs.noLive)
                        filter == Filter.Archived -> EmptyState(brain, Mood.Idle, cs.noArchived)
                        filter == Filter.Background -> EmptyState(brain, Mood.Idle, cs.noBackground)
                        sessions.error != null -> EmptyState(brain, Mood.Error, cs.loadFailed) {
                            PillButton(s.retry, { store.refreshSessions() }, kind = ButtonKind.Quiet, icon = Icons.refresh)
                        }
                        bgIds.isNotEmpty() -> EmptyState(brain, Mood.Idle, s.nothingHere) {
                            PillButton(bgLabel, { filter = Filter.Background }, kind = ButtonKind.Quiet)
                        }
                        else -> EmptyState(brain, Mood.Idle, s.nothingHere)
                    }
                    else -> LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = 4.dp, bottom = 100.dp + navBottom)) {
                        items(lines, key = { it.key }, contentType = { it::class }) { line ->
                            val anim = Modifier.animateItem(fadeInSpec = Motion.fade(), placementSpec = Motion.smooth(), fadeOutSpec = Motion.fade())
                            when (line) {
                                is Header -> SectionLabel(line.text, anim)
                                is Item -> {
                                    val r = line.row
                                    SessionItem(
                                        row = r, bot = botsBy[r.profile], live = r.id in live, now = now, snippet = line.snippet,
                                        swipe = !inSearch && r.id !in bgIds, open = openId == r.id,
                                        onOpenChange = { o -> openId = if (o) r.id else if (openId == r.id) null else openId },
                                        onClick = {
                                            if (openId != null) openId = null
                                            else nav.push(Screen.Chat(if (r.id == botsBy[r.profile]?.botChatId) ChatTarget(r.profile) else ChatTarget(r.profile, r.id, botChat = false)))
                                        },
                                        onLongPress = { openId = null; pop = MenuPop(r) },
                                        onSwipe = { if (r.archived) restore(r) else close(r) },
                                        modifier = anim,
                                        quick = app.buddyverse.core.TempChats.isQuick(r, bots),
                                    )
                                }
                                Searching -> Row(anim.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Buddy(brain, Mood.Thinking, 32.dp, interactive = false)
                                    Spacer(Modifier.width(12.dp))
                                    Txt(s.loading, Theme.type.sub, c.secondary, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Long-press menu (design §3.4). Each sheet keeps its row through the exit animation.
        val menu = rememberLast((pop as? MenuPop)?.row)
        Sheet(pop is MenuPop, { pop = null }, title = menu?.let { it.title ?: s.untitled }) {
            val r = menu ?: return@Sheet
            val bg = r.id in bgIds // internal work: read it, copy it, delete it; it never joins your chats
            if (!bg && !r.archived) {
                MenuRow(Icons.edit, s.rename) { pop = RenamePop(r) }
                MenuRow(if (r.pinned) Icons.pushPinFilled else Icons.pushPin, if (r.pinned) s.unpin else s.pin) { pop = null; store.pinSession(r, !r.pinned) }
            } else if (!bg) MenuRow(Icons.unarchive, cs.restore) { pop = null; restore(r) }
            MenuRow(Icons.contentCopy, cs.copyText) { pop = null; copy(r) }
            if (!r.archived && !bg) MenuRow(Icons.close, s.closeSession) { pop = null; close(r) }
            MenuRow(Icons.delete, s.delete, c.errorText) { pop = DeletePop(r) }
            Spacer(Modifier.height(TabBarClearance))
        }

        val ren = rememberLast((pop as? RenamePop)?.row)
        Sheet(pop is RenamePop, { pop = null }, title = s.rename) {
            val r = ren ?: return@Sheet
            var text by remember(r.id) { mutableStateOf(r.title.orEmpty()) }
            val focus = remember { FocusRequester() }
            LaunchedEffect(r.id) { runCatching { focus.requestFocus() } }
            fun save() {
                val t = text.trim()
                if (t.isNotEmpty() && t != r.title) store.renameSession(r, t)
                pop = null
            }
            Field(text, { text = it }, s.untitled, Modifier.padding(horizontal = 20.dp, vertical = 8.dp).focusRequester(focus))
            PillButton(s.save, ::save, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), enabled = text.isNotBlank(), haptic = Haptic.Confirm)
            Spacer(Modifier.height(TabBarClearance))
        }

        val del = rememberLast((pop as? DeletePop)?.row)
        Sheet(pop is DeletePop, { pop = null }, title = s.confirmDelete) {
            val r = del ?: return@Sheet
            Txt(r.title ?: s.untitled, Theme.type.callout, c.secondary, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), maxLines = 2)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(s.cancel, { pop = null }, Modifier.weight(1f), kind = ButtonKind.Quiet)
                PillButton(s.deleteForGood, { pop = null; delete(r) }, Modifier.weight(1f), kind = ButtonKind.Danger, haptic = Haptic.Reject)
            }
            Spacer(Modifier.height(TabBarClearance))
        }
    }
}

/** Pinned first, then calendar days, newest first. [labels] = pinned, today, yesterday, earlier. */
private fun MutableList<Line>.sectioned(rows: List<SessionRow>, nowMs: Long, offsetMs: Long, labels: List<String>) {
    val (pinned, rest) = rows.partition { it.pinned && !it.archived }
    if (pinned.isNotEmpty()) { add(Header(labels[0])); pinned.forEach { add(Item(it)) } }
    rest.sortedByDescending { it.lastActive }.groupBy { dayOf(it.lastActive, nowMs, offsetMs) }
        .forEach { (day, rs) -> add(Header(labels[day.ordinal + 1])); rs.forEach { add(Item(it)) } }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, tint: Color = Theme.colors.text, onClick: () -> Unit) =
    PressRow(onClick, minHeight = 56.dp, label = label) {
        Icon(icon, tint, 24.dp)
        Spacer(Modifier.width(16.dp))
        Txt(label, Theme.type.callout, tint, maxLines = 1)
    }

/** A big, living buddy with one line under it (and an optional action). Tap the buddy: it reacts. */
@Composable
private fun EmptyState(spec: BuddySpec, mood: Mood, text: String, action: @Composable (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(bottom = 120.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Buddy(spec, mood, 96.dp)
        Spacer(Modifier.height(16.dp))
        Txt(text, Theme.type.headline.copy(textAlign = TextAlign.Center), Theme.colors.secondary, Modifier.padding(horizontal = 32.dp))
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

private class Last<T> { var v: T? = null }

/** The latest non-null [value], so a closing sheet keeps showing what it showed. */
@Composable
internal fun <T : Any> rememberLast(value: T?): T? {
    val box = remember { Last<T>() }
    if (value != null) box.v = value
    return box.v
}
