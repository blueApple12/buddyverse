package app.buddyverse.ui.more.artifacts

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.AppStore
import app.buddyverse.core.RestError
import app.buddyverse.core.api.artifactImageBytes
import app.buddyverse.core.api.artifactSessions
import app.buddyverse.core.api.transcriptPage
import app.buddyverse.core.nowMillis
import app.buddyverse.design.Feedback
import app.buddyverse.design.Haptic
import app.buddyverse.design.NoFeedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** How many recent chats a scan reads (the desktop reads 30). */
const val SCAN_CHATS = 30

/** ponytail: a chat longer than this many pages (100 rows each) is read only up to here; raise it if long chats lose artifacts. */
private const val MAX_PAGES = 40

/** Grid thumbnails are decoded at most this big; the viewer uses [FULL_SIDE]. */
const val THUMB_SIDE = 360
const val FULL_SIDE = 2048

/** The last scan, per paired PC, so coming back to the page shows it at once while a fresh scan runs. */
private val lastScan = mutableMapOf<String, List<Artifact>>()

/**
 * More ▸ Artifacts state (hermes-api.md §2.6 Artifacts). There is no artifact backend: [scan] reads the last [SCAN_CHATS]
 * chats of every bot page by page and lists what they produced ([scanPage]), newest first, as each chat finishes. Read-only
 * on the PC: the page opens, downloads, copies and saves; it never changes a chat.
 */
@Stable
class ArtifactsModel(private val store: AppStore, private val scope: CoroutineScope) {
    var fb: Feedback = NoFeedback

    var items by mutableStateOf(lastScan[store.savedLink].orEmpty()); private set
    var scanning by mutableStateOf(false); private set
    var done by mutableIntStateOf(0); private set
    var total by mutableIntStateOf(0); private set
    /** Chats that couldn't be read this time. */
    var skipped by mutableIntStateOf(0); private set
    /** The chat list itself failed: nothing could be scanned. */
    var failed by mutableStateOf(false); private set
    var cue by mutableStateOf<BuddyCue?>(null); private set
    /** Bumps when a scan lands new things: the buddy mouths it. */
    var talkAt by mutableStateOf(0L); private set

    var kind by mutableStateOf<ArtifactKind?>(null)
    var bot by mutableStateOf<String?>(null)
    var query by mutableStateOf("")

    /** Decoded images by artifact id ([THUMB_SIDE]); a `null` value = it failed, don't retry. */
    val thumbs = mutableStateMapOf<String, ImageBitmap?>()
    private val thumbOrder = ArrayDeque<String>()
    private val decodeGate = Semaphore(3)
    private var job: Job? = null

    val mood: Mood
        get() = when {
            failed && items.isEmpty() -> Mood.Error
            scanning -> Mood.Working
            else -> Mood.Idle
        }

    /** What the filters leave, newest first. */
    val shown: List<Artifact>
        get() {
            val q = query.trim().lowercase()
            return items.filter { a ->
                (kind == null || a.kind == kind) && (bot == null || a.profile == bot) &&
                    (q.isEmpty() || a.label.lowercase().contains(q) || (a.made == null && a.value.lowercase().contains(q)) ||
                        a.sessionTitle.lowercase().contains(q))
            }
        }

    fun count(k: ArtifactKind?): Int = items.count { (k == null || it.kind == k) && (bot == null || it.profile == bot) }

    private fun shot(s: OneShot) { cue = BuddyCue(nowMillis(), s) }

    /** A fresh scan (a running one is restarted). [loud] = the user asked for it: confirm with a haptic when it lands. */
    fun scan(loud: Boolean = false) {
        job?.cancel()
        job = scope.launch {
            scanning = true; failed = false; skipped = 0; done = 0
            val before = items.mapTo(HashSet()) { it.id }
            try {
                val sessions = store.api.artifactSessions(SCAN_CHATS).mapNotNull { ScanSession.from(it) }
                total = sessions.size
                val found = LinkedHashMap<String, Artifact>()
                val read = HashSet<String>()
                val gate = Semaphore(3)
                // Three chats at a time; every continuation runs on this (main) scope, so found/read need no lock.
                coroutineScope {
                    for (s in sessions) launch {
                        val mine = gate.withPermit { readChat(s) }
                        if (mine == null) skipped++ else found.putAll(mine)
                        read += s.id
                        done++
                        // Show what's found so far; things from chats not read yet stay until the scan ends.
                        items = (found.values + items.filter { it.id !in found && it.sessionId !in read }).sortedByDescending { it.timestampMs }
                    }
                }
                items = found.values.sortedByDescending { it.timestampMs }
                lastScan[store.savedLink] = items
                val gained = items.count { it.id !in before }
                if (gained > 0) { talkAt = nowMillis(); shot(if (before.isEmpty()) OneShot.Learned else OneShot.Hop) } else shot(OneShot.Nod)
                if (loud) fb.haptic(Haptic.Confirm)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                shot(OneShot.Startle)
                if (loud) fb.haptic(Haptic.Reject)
            } finally {
                scanning = false
            }
        }
    }

    /** One chat, page by page (oldest first); null when it couldn't be read. */
    private suspend fun readChat(s: ScanSession): Map<String, Artifact>? = try {
        val mine = LinkedHashMap<String, Artifact>()
        var offset = 0
        var pages = 0
        while (pages++ < MAX_PAGES) {
            val p = store.api.transcriptPage(s.id, s.profile, offset)
            withContext(Dispatchers.Default) { scanPage(s, p.messages, mine, nowMillis()) }
            val limit = p.pageLimit ?: break // an engine that ignores paging sent the whole chat
            if (p.messages.isEmpty() || p.messages.size < limit) break
            offset += p.messages.size
        }
        mine
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Loads one image tile (cached; at most three decode at once). Silently stays empty when it can't be shown. */
    suspend fun loadThumb(a: Artifact) {
        if (a.id in thumbs) return
        val img = decodeGate.withPermit {
            try {
                val bytes = store.api.artifactImageBytes(a.value, a.profile)
                withContext(Dispatchers.Default) { decodeScaled(bytes, THUMB_SIDE) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        thumbs[a.id] = img
        thumbOrder.addLast(a.id)
        // ponytail: plain FIFO cap on decoded tiles; an LRU would keep scrolled-back ones longer.
        while (thumbOrder.size > 150) thumbs.remove(thumbOrder.removeFirst())
    }

    /** The image at viewer size; null when it can't be read or isn't an image. */
    suspend fun loadFull(a: Artifact): Result<ImageBitmap> = try {
        val bytes = store.api.artifactImageBytes(a.value, a.profile)
        withContext(Dispatchers.Default) { decodeScaled(bytes, FULL_SIDE) }?.let { Result.success(it) } ?: Result.failure(RestError(415, "image"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
