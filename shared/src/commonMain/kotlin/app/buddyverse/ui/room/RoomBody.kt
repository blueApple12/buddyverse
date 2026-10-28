package app.buddyverse.ui.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.BuddyGroup
import app.buddyverse.buddy.Mood
import app.buddyverse.core.ChatTarget
import app.buddyverse.core.Room
import app.buddyverse.core.api.RoomFile
import app.buddyverse.core.api.RoomSeat
import app.buddyverse.core.api.memberLiveSession
import app.buddyverse.core.api.roomReason
import app.buddyverse.core.nowMillis
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.room
import app.buddyverse.ui.LocalNav
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.Screen
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.chat.Composer
import app.buddyverse.ui.chat.ComposerState
import app.buddyverse.ui.chat.MenuItem
import app.buddyverse.ui.chat.MenuPopup
import app.buddyverse.ui.chat.Spinner
import app.buddyverse.ui.chat.attachDrop
import app.buddyverse.ui.chat.rememberComposerState
import app.buddyverse.ui.more.files.rememberFileSaver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a long press on a transcript row offers. */
private data class Held(val text: String, val seat: RoomSeat?, val files: List<RoomFile>, val eventId: String)

/**
 * The room conversation under any header (design §3.3): crew strip, clustered transcript, pending actions, presence line, composer.
 * Used by [RoomScreen] and the Project screen: keep this signature; add parameters only with defaults.
 * [model] lets the screen share the room state with its header; [onProfile] opens a member's profile sheet at screen level
 * (null = the crew strip only offers the @mention). [composer] lets a screen put text in the draft.
 */
@Composable
fun RoomBody(
    room: Room,
    modifier: Modifier = Modifier,
    model: RoomModel = rememberRoomModel(room),
    onProfile: ((String) -> Unit)? = null,
    composer: ComposerState = rememberComposerState("room:${room.profile}:${room.roomId}"),
) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val toasts = LocalToasts.current
    val fb = LocalFeedback.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val saver = rememberFileSaver()
    val s = S.current
    val sr = S.room
    val c = Theme.colors
    val scope = rememberCoroutineScope()
    val bots by store.bots.collectAsState()

    LaunchedEffect(model) { model.poll() }

    val typing by produceState(false, composer.typedAt) {
        val left = 3_000 - (nowMillis() - composer.typedAt)
        value = composer.typedAt > 0 && left > 0
        if (value) { delay(left); value = false }
    }

    fun botName(p: String) = bots.firstOrNull { it.profile == p }?.name
    fun nameOf(seat: RoomSeat) = seatName(seat.memberId, seat, null, ::botName)
    fun seatSpec(seat: RoomSeat) = specOf(seat.profile, bots)
    fun toast(t: String) = toasts.show(ToastData(t))

    val events = model.events
    val sends = model.sends.toList()
    val lines = remember(events, sends, bots, model.seats) {
        roomLines(events, sends) { mid, actor -> seatName(mid, model.seat(mid), actor, ::botName) }.asReversed()
    }
    val failed = remember(events) { failedMembers(events) }
    val replies = remember(events) { lastReplies(events) }
    val (workingIds, queuedIds) = presenceOf(model.driver?.activity.orEmpty())
    val working = workingIds.mapNotNull(model::seat)
    val queued = queuedIds.mapNotNull(model::seat)
    val memberBots = model.seats.mapNotNull { st -> bots.firstOrNull { it.profile == st.profile } }

    var held by remember { mutableStateOf<Held?>(null) }
    var watchMenu by remember { mutableStateOf(false) }

    fun mention(seat: RoomSeat) {
        val t = composer.text
        composer.set(t + (if (t.isEmpty() || t.endsWith(" ") || t.endsWith("\n")) "" else " ") + "@${seat.handle} ")
        composer.typedAt = nowMillis()
        fb.haptic(Haptic.Tick)
    }

    fun watch(seat: RoomSeat) {
        watchMenu = false
        scope.launch {
            val sid = try { store.api.memberLiveSession(seat.profile, model.room.name) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (sid != null) nav.push(Screen.Chat(ChatTarget(seat.profile, sid))) else toast(sr.nothingToWatch)
        }
    }

    fun save(eventId: String, f: RoomFile) {
        if (eventId.isEmpty()) return
        fb.haptic(Haptic.Press)
        store.scope.launch {
            try {
                val bytes = model.bytes(eventId, f)
                val ok = withContext(Dispatchers.Default) {
                    val out = saver.create(f.name, f.mime.ifBlank { "application/octet-stream" }) ?: return@withContext null
                    try { out.write(bytes, bytes.size); if (out.finish()) out else null } catch (e: Exception) { out.abort(); throw e }
                }
                if (ok != null) toasts.show(ToastData(sr.saved, s.open, { ok.open() })) else toast(sr.saveFailed)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.roomReason()) }
        }
    }

    Column(modifier.fillMaxWidth().attachDrop(composer)) {
        CrewStrip(
            model.seats, ::seatSpec, ::nameOf, { model.moodOf(it.memberId, failed, typing) },
            { st -> replies[st.memberId]?.takeIf { it.first > model.primedSeq && model.primedSeq >= 0 } ?: (0L to 0) },
            typing, onTap = { st -> if (onProfile != null) onProfile(st.profile) else mention(st) }, onLong = ::mention,
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val listState = rememberLazyListState()
            val newest = lines.firstOrNull()?.key
            LaunchedEffect(newest) { if (newest != null && listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0) }
            LazyColumn(
                Modifier.fillMaxSize(), state = listState, reverseLayout = true,
                contentPadding = PaddingValues(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
            ) {
                items(lines, key = { it.key }, contentType = { l -> when (l) { is UserLine -> 1; is MemberLine -> 2; is NoticeLine -> 3 } }) { line ->
                    when (line) {
                        is UserLine -> RoomUserBubble(line, model, { held = Held(line.text, null, line.files, line.eventId.orEmpty()); fb.haptic(Haptic.LongPress) }, ::save)
                        is MemberLine -> MemberBubble(
                            line, specOf(line.profile ?: model.seat(line.memberId)?.profile ?: line.memberId, bots),
                            fresh = model.primedSeq >= 0 && line.seq > model.primedSeq, model = model,
                            onLong = { held = Held(line.text, model.seat(line.memberId), line.files, line.eventId); fb.haptic(Haptic.LongPress) },
                            onFile = ::save,
                        )
                        is NoticeLine -> RoomNotice(line)
                    }
                }
            }
            when {
                !model.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(28.dp, c.tertiary) }
                lines.isEmpty() -> Column(
                    Modifier.fillMaxSize().padding(bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    BuddyGroup(model.seats.map(::seatSpec), model.seats.map { if (typing) Mood.Listening else Mood.Idle }, 112.dp)
                    Spacer(Modifier.height(14.dp))
                    Txt(if (model.gone) sr.gone else sr.sayHi, Theme.type.callout.copy(textAlign = TextAlign.Center), c.secondary, maxLines = 2)
                }
            }
            held?.let { h ->
                MenuPopup(h.seat?.let(::nameOf) ?: sr.you, alignEnd = h.seat == null, onDismiss = { held = null }) {
                    if (h.text.isNotBlank()) MenuItem(Icons.contentCopy, s.copy) {
                        clipboard.setText(AnnotatedString(h.text)); held = null; toast(sr.copied)
                    }
                    h.seat?.let { st -> MenuItem(Icons.reply, sr.mention) { held = null; mention(st) } }
                    for (f in h.files) MenuItem(Icons.attachFile, "${sr.download} · ${f.name}") { held = null; save(h.eventId, f) }
                }
            }
        }

        model.error?.takeIf { model.loaded && !model.gone }?.let {
            Txt(it, Theme.type.micro, c.errorText, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp), maxLines = 2)
        }
        for (a in model.actions) {
            val seat = model.seat(a.memberId)
            ActionCard(
                a, seat?.let(::nameOf) ?: a.memberId, specOf(seat?.profile ?: a.memberId, bots),
                onAllow = { model.approve(a, true) }, onDeny = { model.approve(a, false) },
                onRetry = { model.retryTurn(a) }, onDiscard = { model.discardTurn(a) },
            )
        }
        Box {
            PresenceLine(working, queued, model.working, { model.workingSince[it] }, ::seatSpec, ::nameOf) {
                if (working.size == 1) watch(working[0]) else if (working.size > 1) watchMenu = true
            }
            if (watchMenu) MenuPopup(sr.watchWho, alignEnd = true, onDismiss = { watchMenu = false }) {
                for (st in working) MenuItem(Icons.visibility, nameOf(st)) { watch(st) }
            }
        }
        if (model.gone) {
            Txt(sr.gone, Theme.type.callout.copy(textAlign = TextAlign.Center), c.secondary,
                Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp), maxLines = 2)
        } else {
            Composer(
                hint = sr.messageTeam,
                running = model.working,
                onSend = { text, _, files ->
                    val left = model.send(text, files)
                    if (left > 0 && files.isNotEmpty()) toast(if (files.any { it.bytes.size > RoomFile.MAX_BYTES }) sr.tooBig else sr.tooMany)
                },
                onStop = model::stop,
                modifier = Modifier.navigationBarsPadding(),
                mentions = memberBots,
                state = composer,
                profile = room.profile,
            )
        }
    }
}
