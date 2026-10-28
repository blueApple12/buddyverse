package app.buddyverse.ui.room

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import app.buddyverse.core.AppStore
import app.buddyverse.core.Attachment
import app.buddyverse.core.Room
import app.buddyverse.core.RpcError
import app.buddyverse.core.api.RoomAction
import app.buddyverse.core.api.RoomDriver
import app.buddyverse.core.api.RoomEvent
import app.buddyverse.core.api.RoomFile
import app.buddyverse.core.api.RoomSeat
import app.buddyverse.core.api.groupsApprove
import app.buddyverse.core.api.groupsDisband
import app.buddyverse.core.api.groupsDiscard
import app.buddyverse.core.api.groupsRename
import app.buddyverse.core.api.groupsRetry
import app.buddyverse.core.api.groupsStop
import app.buddyverse.core.api.roomDownload
import app.buddyverse.core.api.roomGone
import app.buddyverse.core.api.roomLog
import app.buddyverse.core.api.roomReason
import app.buddyverse.core.api.roomSend
import app.buddyverse.core.api.roomState
import app.buddyverse.core.api.roomUpload
import app.buddyverse.core.nowMillis
import app.buddyverse.core.uuid
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.ToastData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * One room's live state and intents (hermes-api.md §2.4 "Room view"). Rooms have no push: [poll] reads `groups.state`
 * and then only the new part of `groups.log` (a cursor, not the Desktop's full re-read) every 2 s while the room is shown,
 * plus right after every write. Writes run on the store's scope, so they finish even if the screen closes; each one changes
 * the screen first (optimistic) and puts it back with the server's reason if it fails.
 */
@Stable
class RoomModel(private val store: AppStore, initial: Room) {
    private val api get() = store.api
    var room by mutableStateOf(initial); private set
    var seats by mutableStateOf(initial.members.map { RoomSeat(it, it, it, null) }); private set
    var events by mutableStateOf<List<RoomEvent>>(emptyList()); private set
    /** Null while the room's driver isn't running (a fresh room, or the engine is busy elsewhere). */
    var driver by mutableStateOf<RoomDriver?>(null); private set
    var loaded by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    /** Disbanded, pruned, or not this user's: nothing more to show or send. */
    var gone by mutableStateOf(false); private set
    var stopping by mutableStateOf(false); private set
    val sends = mutableStateListOf<OutSend>()
    /** Pending actions already answered here: hidden until the next poll drops them. */
    var answered by mutableStateOf<Set<String>>(emptySet()); private set
    /** The newest seq when the room first loaded: later replies are fresh (they grow in and their buddy talks). */
    var primedSeq by mutableLongStateOf(-1L); private set
    /** member_id → when this screen first saw it working (the presence timer). */
    val workingSince = mutableStateMapOf<String, Long>()
    val images = mutableStateMapOf<String, ImageBitmap>()
    var toast: (String) -> Unit = {}

    private var lastSeq = 0L
    private val lock = Mutex()
    private val downloads = Semaphore(2)

    val working: Boolean get() = driver?.working == true
    val actions: List<RoomAction> get() = driver?.actions.orEmpty().filter { it.key !in answered }

    fun seat(memberId: String): RoomSeat? = seats.firstOrNull { it.memberId == memberId }

    suspend fun poll() {
        while (!gone) { refresh(); delay(2_000) }
    }

    suspend fun refresh() = lock.withLock {
        val p = room.profile
        val id = room.roomId
        try {
            val (info, status) = api.roomState(p, id)
            if (info != null) {
                room = info.room
                if (info.seats.isNotEmpty()) seats = info.seats
                if (info.disbanded) gone = true
            }
            driver = status
            val fresh = ArrayList<RoomEvent>()
            var since = lastSeq
            while (true) {
                val page = api.roomLog(p, id, since)
                fresh += page.events
                since = page.events.lastOrNull()?.seq ?: since
                if (!page.hasMore || page.events.isEmpty()) break
            }
            if (fresh.isNotEmpty()) { events = merge(events, fresh); lastSeq = since }
            if (primedSeq < 0) primedSeq = lastSeq
            trackPresence(status)
            loaded = true
            error = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            when {
                e.roomGone -> gone = true
                // Our cursor is past the log (history pruned or reset): read it again from the start.
                (e as? RpcError)?.message?.contains("ahead") == true -> { lastSeq = 0; events = emptyList() }
            }
            error = e.roomReason()
            loaded = true
        }
    }

    private fun merge(old: List<RoomEvent>, fresh: List<RoomEvent>) = (old + fresh).distinctBy { it.seq }.sortedBy { it.seq }

    private fun trackPresence(status: RoomDriver?) {
        val live = status?.activity.orEmpty().filterValues { it != "queued" }.keys
        (workingSince.keys - live).forEach { workingSince.remove(it) }
        val now = nowMillis()
        for (m in live) if (m !in workingSince) workingSince[m] = now
    }

    // ------------------------------------------------------------------ send

    /** Shows the message at once, then uploads and sends. Returns how many files were left out (over 15 MB or past 8). */
    fun send(text: String, files: List<Attachment>): Int {
        val t = text.trim()
        val ok = files.filter { it.bytes.size <= RoomFile.MAX_BYTES }.take(RoomFile.MAX_FILES)
        if (t.isEmpty() && ok.isEmpty()) return files.size
        val o = OutSend(uuid(), t, ok, ok.map { uuid() })
        sends += o
        store.scope.launch { deliver(o) }
        return files.size - ok.size
    }

    fun retry(o: OutSend) {
        val again = o.copy(failed = null)
        replace(again)
        store.scope.launch { deliver(again) }
    }

    fun discard(o: OutSend) { sends.removeAll { it.eventId == o.eventId } }

    private fun replace(o: OutSend) { val i = sends.indexOfFirst { it.eventId == o.eventId }; if (i >= 0) sends[i] = o }

    private suspend fun deliver(o: OutSend) {
        val p = room.profile
        val id = room.roomId
        var cur = o
        try {
            val files = cur.uploaded ?: cur.files.mapIndexed { i, f -> api.roomUpload(p, id, cur.uploadIds[i], f.name, f.mime, f.bytes) }
            cur = cur.copy(uploaded = files)
            replace(cur)
            val ev = api.roomSend(p, id, cur.eventId, cur.text, files)
            lock.withLock {
                if (ev != null) events = merge(events, listOf(ev))
                sends.removeAll { it.eventId == o.eventId }
            }
            refresh()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            replace(cur.copy(failed = e.roomReason()))
        }
    }

    // ------------------------------------------------------------------ controls

    fun stop() {
        if (stopping) return
        stopping = true
        store.scope.launch {
            try { api.groupsStop(room.profile, room.roomId) } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.roomReason()) }
            refresh()
            stopping = false
        }
    }

    fun approve(a: RoomAction, allow: Boolean) = act(a) { api.groupsApprove(room.profile, room.roomId, a, if (allow) "once" else "deny") }
    fun retryTurn(a: RoomAction) = act(a) { api.groupsRetry(room.profile, room.roomId, a) }
    fun discardTurn(a: RoomAction) = act(a) { api.groupsDiscard(room.profile, room.roomId, a) }

    private fun act(a: RoomAction, call: suspend () -> Unit) {
        answered = answered + a.key
        store.scope.launch {
            try { call(); refresh() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                answered = answered - a.key
                toast(e.roomReason())
            }
        }
    }

    fun rename(name: String) {
        val t = name.trim().take(200)
        if (t.isEmpty() || t == room.name) return
        val old = room
        room = room.copy(name = t)
        store.scope.launch {
            try {
                api.groupsRename(old.profile, old.roomId, t)?.let { room = it.room }
                store.refreshRooms()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { room = old; toast(e.roomReason()) }
        }
    }

    /** Stops any work first (disband is refused while work is still stopping), then retires the room for good. */
    fun disband(onDone: () -> Unit) {
        store.scope.launch {
            val p = room.profile
            val id = room.roomId
            try {
                if (working) runCatching { api.groupsStop(p, id) }
                try { api.groupsDisband(p, id) } catch (e: RpcError) { delay(1_500); api.groupsDisband(p, id) }
                gone = true
                store.refreshRooms()
                onDone()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.roomReason()) }
        }
    }

    // ------------------------------------------------------------------ files

    /** A small image's preview (≤ 5 MB, two downloads at a time), cached for the screen's life. */
    suspend fun preview(eventId: String, f: RoomFile): ImageBitmap? {
        images[f.attachmentId]?.let { return it }
        if (f.kind != "image" || f.size > 5L * 1024 * 1024 || eventId.isEmpty()) return null
        return downloads.withPermit {
            runCatching {
                val bytes = api.roomDownload(room.profile, room.roomId, eventId, f.attachmentId)
                withContext(Dispatchers.Default) { bytes.decodeToImageBitmap() }
            }.getOrNull()?.also { images[f.attachmentId] = it }
        }
    }

    suspend fun bytes(eventId: String, f: RoomFile): ByteArray = api.roomDownload(room.profile, room.roomId, eventId, f.attachmentId)
}

/** The model for [room], kept while the screen is composed; RoomScreen / ProjectScreen pass it to [RoomBody] to share it. */
@Composable
fun rememberRoomModel(room: Room): RoomModel {
    val store = LocalStore.current
    val toasts = LocalToasts.current
    return remember(room.profile, room.roomId) { RoomModel(store, room).also { m -> m.toast = { toasts.show(ToastData(it)) } } }
}
