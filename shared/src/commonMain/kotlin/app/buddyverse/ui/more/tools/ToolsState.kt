package app.buddyverse.ui.more.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.AppStore
import app.buddyverse.core.Hermes
import app.buddyverse.core.RestError
import app.buddyverse.core.api.SetupStatus
import app.buddyverse.core.api.Toolset
import app.buddyverse.core.api.runToolSetup
import app.buddyverse.core.api.setToolset
import app.buddyverse.core.api.toolCallCounts
import app.buddyverse.core.api.toolSetupStatus
import app.buddyverse.core.api.toolsetList
import app.buddyverse.design.Feedback
import app.buddyverse.design.Haptic
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Sound
import app.buddyverse.i18n.APP_NAME
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.Toasts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Composable
fun rememberToolsState(): ToolsState {
    val store = LocalStore.current
    val fb = LocalFeedback.current
    val toasts = LocalToasts.current
    return remember(store) { ToolsState(store, fb, toasts) }
}

/**
 * More ▸ Tools for one bot (profile): its toolsets, optimistic writes, the install watcher and the buddy's reactions.
 * Work runs on the store scope so a write finishes even if the page closes.
 */
@Stable
class ToolsState(private val store: AppStore, private val fb: Feedback, private val toasts: Toasts) {
    val api: Hermes get() = store.api

    var profile by mutableStateOf<String?>(null)
        private set
    var toolsets by mutableStateOf<List<Toolset>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    /** Calls per tool over the last year; empty when the engine has none. */
    var counts by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
    var cue by mutableStateOf<BuddyCue?>(null)
        private set
    /** Bumps after every write: an open sheet re-reads its config. */
    var configRev by mutableIntStateOf(0)
        private set
    /** The toolset whose install runs (or last ran) on the PC, and the log tail. */
    var setupFor by mutableStateOf<String?>(null)
        private set
    var setupRunning by mutableStateOf(false)
        private set
    var setupLog by mutableStateOf<SetupStatus?>(null)
        private set

    /** Set from composition: the generic error line, and the buddy shown in error toasts. */
    var errorText = "Something went wrong"
    var buddy: BuddySpec? = null

    private val writes = Mutex()
    private var cueSerial = 0L
    private var loadSerial = 0
    private var loadJob: Job? = null
    private var setupJob: Job? = null

    fun react(shot: OneShot) { cue = BuddyCue(++cueSerial, shot) }

    fun select(p: String) {
        if (p == profile) return
        profile = p; toolsets = emptyList(); counts = emptyMap()
        react(OneShot.Arrive)
        load()
    }

    fun refresh() { configRev++; load() }

    fun load() {
        val p = profile ?: return
        val serial = ++loadSerial
        loadJob?.cancel()
        loading = true; failed = false
        loadJob = store.scope.launch {
            try {
                val list = api.toolsetList(p)
                if (serial == loadSerial) toolsets = list
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (serial == loadSerial) failed = true
            } finally {
                if (serial == loadSerial) loading = false
            }
            if (serial != loadSerial) return@launch
            try { counts = api.toolCallCounts(p) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    /**
     * One config write at a time: the server does read-modify-write on the profile's config (hermes-api.md §2.6 notes).
     * [optimistic] runs now, [block] after the ones before it, [revert] if it fails. An open sheet re-reads either way.
     */
    fun write(
        shot: OneShot? = OneShot.Nod,
        confirm: Boolean = false,
        optimistic: () -> Unit = {},
        revert: () -> Unit = {},
        block: suspend (profile: String) -> Unit,
    ) {
        val p = profile ?: return
        optimistic()
        store.scope.launch {
            try {
                writes.withLock { block(p) }
                if (confirm) fb.haptic(Haptic.Confirm)
                shot?.let { react(it) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (p == profile) revert()
                fail(e)
            }
            configRev++
        }
    }

    fun toggle(t: Toolset, on: Boolean) {
        fun set(v: Boolean) { toolsets = toolsets.map { if (it.name == t.name) it.copy(enabled = v) else it } }
        write(if (on) OneShot.Learned else OneShot.Nod, optimistic = { set(on) }, revert = { set(!on) }) { p ->
            if (api.setToolset(t.name, on, p) != null) watchSetup(t.name)
        }
    }

    fun runSetup(toolset: String, key: String) = write(shot = null) { p ->
        api.runToolSetup(toolset, key, p)
        watchSetup(toolset)
    }

    private fun fail(e: Throwable) {
        fb.haptic(Haptic.Reject); fb.sound(Sound.Bonk); react(OneShot.Dizzy)
        val detail = (e as? RestError)?.detail?.let(::brandFree)?.takeIf { it.isNotBlank() }
        toasts.show(ToastData(detail ?: errorText, buddy = buddy, shot = OneShot.Dizzy))
    }

    /** Tails the PC-side install until it exits; the buddy works meanwhile and cheers (or gets dizzy) at the end. */
    private fun watchSetup(toolset: String) {
        setupJob?.cancel()
        setupFor = toolset; setupRunning = true; setupLog = null
        setupJob = store.scope.launch {
            var last: SetupStatus? = null
            delay(800)
            // ponytail: watches for 10 minutes at most; a longer install finishes unseen (reopen the sheet to re-read its status).
            for (i in 0 until 600) {
                last = try { api.toolSetupStatus() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (last != null) setupLog = last
                if (last != null && !last.running) break
                delay(1000)
            }
            setupRunning = false
            when (last?.exitCode) {
                0L -> { fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm); react(OneShot.Done) }
                null -> react(OneShot.Nod)
                else -> { fb.sound(Sound.Bonk); fb.haptic(Haptic.Reject); react(OneShot.Dizzy) }
            }
            configRev++
        }
    }
}

private val clauseBreak = Regex("(?<=[.;,—])\\s+")
private val engineName = Regex("\\bHermes(?: Agent)?\\b")

/**
 * Text from the engine without its branding (design §0): its name becomes the app's, and clauses about the vendor's paid
 * service are dropped. Lowercase paths (`…\hermes\…`) are left alone: they are real locations, not branding.
 */
internal fun brandFree(text: String): String {
    if ("Nous" !in text && "Hermes" !in text) return text
    return text.split(clauseBreak).filterNot { "Nous" in it }.joinToString(" ")
        .replace(engineName, APP_NAME).trim().trimEnd(',', ';', '—').trim()
}
