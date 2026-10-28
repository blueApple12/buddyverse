package app.buddyverse.ui.more.status

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.AppStore
import app.buddyverse.core.Hermes
import app.buddyverse.core.RestError
import app.buddyverse.core.api.PcActionStart
import app.buddyverse.core.api.PcHealth
import app.buddyverse.core.api.PcReceipt
import app.buddyverse.core.api.PcStats
import app.buddyverse.core.api.PcUpdate
import app.buddyverse.core.api.actionStatus
import app.buddyverse.core.api.gatewayRestart
import app.buddyverse.core.api.gatewayStartAction
import app.buddyverse.core.api.logTail
import app.buddyverse.core.api.pcHealth
import app.buddyverse.core.api.pcStats
import app.buddyverse.core.api.runConfigMigrate
import app.buddyverse.core.api.runDoctor
import app.buddyverse.core.api.runSecurityAudit
import app.buddyverse.core.api.updateApply
import app.buddyverse.core.api.updateCheck
import app.buddyverse.core.api.updateReceipt
import app.buddyverse.core.nowMillis
import app.buddyverse.ui.parseIsoMillis
import app.buddyverse.design.Feedback
import app.buddyverse.design.Haptic
import app.buddyverse.design.Sound
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.buddyverse.i18n.Strings

/** Background actions this page starts (hermes-api.md §2.6 `_ACTION_LOG_FILES`). */
object Act {
    const val RESTART = "gateway-restart"
    const val START = "gateway-start"
    const val UPDATE = "hermes-update"
    const val DOCTOR = "doctor"
    const val AUDIT = "security-audit"
    const val MIGRATE = "config-migrate"
}

/** One background action as the page follows it: its live output, exit code and (for the update) the receipt. */
@Stable
class ActionRun(val name: String) {
    var running by mutableStateOf(false)
    var lines by mutableStateOf<List<String>>(emptyList())
    var exitCode by mutableStateOf<Int?>(null)
    var receipt by mutableStateOf<PcReceipt?>(null)
    /** The start call was refused or failed; the text says why. */
    var error by mutableStateOf<String?>(null)
    /** Set when this page started it, so the result is announced. */
    var mine by mutableStateOf(false)
    var actionId: String? = null
    val ok: Boolean get() = !running && error == null && (receipt?.succeeded ?: (exitCode == 0))
    val finished: Boolean get() = !running && (exitCode != null || receipt != null || error != null)
}

private fun Throwable.why(): String = (this as? RestError)?.detail?.takeIf { it.isNotBlank() } ?: message ?: toString()

/** "3h 12m", "2d 4h", "5m" (Western digits). */
fun span(seconds: Long, s: Strings): String = when {
    seconds < 60 -> s.unit(seconds.coerceAtLeast(0), "s")
    seconds < 3600 -> s.unit(seconds / 60, "m")
    seconds < 86_400 -> s.unit(seconds / 3600, "h") + " " + s.unit((seconds % 3600) / 60, "m")
    else -> s.unit(seconds / 86_400, "d") + " " + s.unit((seconds % 86_400) / 3600, "h")
}

/** Bytes → "12.4 GB" / "512 MB". */
fun bytes(b: Long): String {
    val gb = b / 1_073_741_824.0
    return if (gb >= 1) "${(gb * 10).toLong() / 10.0} GB" else "${b / 1_048_576} MB"
}

/** Mood of the PC buddy from what the engine reports (design §4.3 moods). */
fun PcHealth?.mood(reachable: Boolean, acting: Boolean): Mood = when {
    !reachable -> Mood.Offline
    this == null -> Mood.Thinking
    acting -> Mood.Working
    state == "fatal" || state == "startup_failed" -> Mood.Error
    !running || state == "stopped" -> Mood.Sleeping
    state == "starting" || state == "draining" -> Mood.Working
    overall != null && overall != "ok" -> Mood.NeedsYou
    busy || activeAgents > 0 -> Mood.Working
    else -> Mood.Idle
}

/** Gateway uptime in seconds from `memory.boot_id` (its `started_at`); null when unknown. */
fun PcHealth.uptimeSeconds(nowMs: Long): Long? = memory?.bootId?.let(::parseIsoMillis)?.let { (nowMs - it) / 1000 }?.takeIf { it >= 0 }

/**
 * State of More ▸ PC status: the engine's health (`/api/status`, polled while the page is open), the PC's numbers, the update
 * check and every background action started here. Actions run on the store's scope, so leaving the page doesn't stop the
 * follow-up; the page shows them again when it comes back.
 */
@Stable
class StatusModel(private val store: AppStore, private val fb: Feedback) {
    private val api: Hermes get() = store.api
    var health by mutableStateOf<PcHealth?>(null); private set
    var stats by mutableStateOf<PcStats?>(null); private set
    var reachable by mutableStateOf(true); private set
    var update by mutableStateOf<PcUpdate?>(null); private set
    var checking by mutableStateOf(false); private set
    var updateError by mutableStateOf<String?>(null); private set
    var receipt by mutableStateOf<PcReceipt?>(null); private set
    var cue by mutableStateOf<BuddyCue?>(null); private set
    /** Bumps when something worth saying lands (the buddy mouths the hero line). */
    var talkAt by mutableStateOf(0L); private set
    val runs = mutableStateMapOf<String, ActionRun>()
    private val follows = mutableMapOf<String, Job>()

    fun run(name: String): ActionRun = runs.getOrPut(name) { ActionRun(name) }
    val acting: Boolean get() = runs[Act.RESTART]?.running == true || runs[Act.START]?.running == true || runs[Act.UPDATE]?.running == true

    fun react(shot: OneShot) { cue = BuddyCue(nowMillis(), shot) }

    suspend fun refresh() {
        val wasReachable = reachable
        try {
            health = api.pcHealth()
            reachable = true
            if (!wasReachable) { react(OneShot.Arrive); talkAt = nowMillis() }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            if (wasReachable) react(OneShot.Startle)
            reachable = false
        }
        if (reachable) runCatching { api.pcStats() }.onSuccess { stats = it }
    }

    fun loadUpdates(force: Boolean) {
        if (checking) return
        checking = true
        store.scope.launch {
            try {
                update = api.updateCheck(force); updateError = null
                if (force) { react(if (update?.available == true) OneShot.Wiggle else OneShot.Nod); fb.haptic(Haptic.Confirm) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                updateError = e.why()
                if (force) { fb.haptic(Haptic.Reject); react(OneShot.Startle) }
            } finally {
                checking = false
            }
            runCatching { api.updateReceipt() }.onSuccess { receipt = it }
        }
    }

    /** Shows what an action printed last time (a sheet opens on it); never starts it. */
    fun peek(name: String) {
        val r = run(name)
        if (r.running) return
        store.scope.launch {
            runCatching { api.actionStatus(name, 400) }.onSuccess { st ->
                if (!r.running) { r.lines = st.lines; r.exitCode = st.exitCode; r.receipt = st.receipt; r.running = st.running }
                if (st.running && follows[name]?.isActive != true) follows[name] = store.scope.launch { follow(r) }
            }
        }
    }

    /** Starts [name] and follows its output until it ends. A second press while it runs does nothing. */
    fun start(name: String) {
        val r = run(name)
        if (r.running) return
        r.running = true; r.mine = true; r.error = null; r.exitCode = null; r.receipt = null; r.lines = emptyList()
        fb.haptic(Haptic.Confirm)
        react(OneShot.Nod)
        follows[name]?.cancel()
        follows[name] = store.scope.launch {
            val started: PcActionStart = try {
                when (name) {
                    Act.RESTART -> api.gatewayRestart()
                    Act.START -> api.gatewayStartAction()
                    Act.UPDATE -> api.updateApply()
                    Act.DOCTOR -> api.runDoctor()
                    Act.AUDIT -> api.runSecurityAudit()
                    Act.MIGRATE -> api.runConfigMigrate()
                    else -> error("unknown action $name")
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                r.running = false; r.error = e.why(); ouch(); return@launch
            }
            if (!started.ok) {
                r.running = false; r.error = started.message ?: started.error ?: "refused"; ouch(); return@launch
            }
            r.actionId = started.actionId
            follow(r)
        }
    }

    private suspend fun follow(r: ActionRun) {
        // ponytail: fixed caps (15 min for the update, 5 min otherwise); a longer run just stops being followed here.
        val until = nowMillis() + if (r.name == Act.UPDATE) 15 * 60_000L else 5 * 60_000L
        delay(700)
        var done = false
        while (!done && nowMillis() < until) {
            // The engine restarts under us for restart/update: failed reads just mean "not back yet".
            val st = runCatching { api.actionStatus(r.name, 400) }.getOrNull()
            if (st != null) {
                if (st.lines.isNotEmpty() || r.lines.isEmpty()) r.lines = st.lines
                r.exitCode = st.exitCode
                st.receipt?.let { r.receipt = it }
                done = if (r.name == Act.UPDATE) {
                    val id = r.actionId
                    val marker = id != null && st.lines.any { it.contains("hermes-update completed $id") }
                    val receiptDone = st.receipt?.let { it.outcome != "running" && (id == null || it.actionId == null || it.actionId == id) } == true
                    marker || receiptDone || (!st.running && st.exitCode != null)
                } else !st.running
            }
            if (!done) delay(1_200)
        }
        r.running = false
        if (r.mine) {
            if (r.ok) { fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm); react(OneShot.Done) } else ouch()
            talkAt = nowMillis()
        }
        if (r.name == Act.RESTART || r.name == Act.START || r.name == Act.UPDATE || r.name == Act.MIGRATE) {
            refresh()
            if (r.name == Act.UPDATE) loadUpdates(false)
        }
    }

    private fun ouch() { fb.sound(Sound.Bonk); fb.haptic(Haptic.Reject); react(OneShot.Startle) }
}

/** Log viewer state: one file, an optional minimum level and a search, re-read every few seconds while following. */
@Stable
class LogsModel(private val api: Hermes) {
    var file by mutableStateOf(LOG_FILES.first())
    /** null = all levels; else `INFO`, `WARNING` or `ERROR` (minimum). */
    var level by mutableStateOf<String?>(null)
    var search by mutableStateOf("")
    var follow by mutableStateOf(true)
    var lines by mutableStateOf<List<String>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var loadedKey by mutableStateOf(""); private set

    suspend fun load() {
        val key = "$file|$level|$search"
        loading = true
        try {
            val l = api.logTail(file, 400, level, search)
            lines = l; error = null; loadedKey = key
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            error = e.why()
            if (loadedKey != key) lines = emptyList()
        } finally {
            loading = false
        }
    }

    companion object {
        val LOG_FILES = listOf("agent", "errors", "gateway")
        val LEVELS = listOf(null, "INFO", "WARNING", "ERROR")
    }
}

/** The level a log line carries, for its color: 2 error, 1 warning, 0 other. */
fun lineLevel(line: String): Int = when {
    line.contains(" ERROR ") || line.contains("[ERROR]") || line.contains(" CRITICAL ") || line.startsWith("Traceback") -> 2
    line.contains(" WARNING ") || line.contains("[WARNING]") || line.contains(" WARN ") -> 1
    else -> 0
}
