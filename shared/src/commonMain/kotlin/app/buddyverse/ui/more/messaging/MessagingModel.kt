package app.buddyverse.ui.more.messaging

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.buddyverse.core.AppStore
import app.buddyverse.core.Disconnected
import app.buddyverse.core.LinkState
import app.buddyverse.core.RestError
import app.buddyverse.core.api.MsgPairingUser
import app.buddyverse.core.api.MsgPlatform
import app.buddyverse.core.api.MsgSaved
import app.buddyverse.core.api.MsgSetupApplied
import app.buddyverse.core.api.MsgTest
import app.buddyverse.core.api.MsgWhatsappSession
import app.buddyverse.core.api.messagingApprove
import app.buddyverse.core.api.messagingClearPending
import app.buddyverse.core.api.messagingPairing
import app.buddyverse.core.api.messagingPlatforms
import app.buddyverse.core.api.messagingRestart
import app.buddyverse.core.api.messagingRestartStatus
import app.buddyverse.core.api.messagingRevoke
import app.buddyverse.core.api.messagingTelegramApply
import app.buddyverse.core.api.messagingTelegramCancel
import app.buddyverse.core.api.messagingTelegramStart
import app.buddyverse.core.api.messagingTelegramStatus
import app.buddyverse.core.api.messagingTest
import app.buddyverse.core.api.messagingUpdate
import app.buddyverse.core.api.messagingWhatsappApply
import app.buddyverse.core.api.messagingWhatsappCancel
import app.buddyverse.core.api.messagingWhatsappStart
import app.buddyverse.core.api.messagingWhatsappStatus
import app.buddyverse.core.nowMillis
import app.buddyverse.i18n.MessagingPageStrings
import app.buddyverse.i18n.Strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Why a messaging call failed, worded by [text]. */
enum class Problem { Offline, Invalid, Gone, Conflict, TooMany, Other }

fun problemOf(e: Throwable?): Problem = when (e) {
    null -> Problem.Other
    is Disconnected, is TimeoutCancellationException -> Problem.Offline
    is RestError -> when (e.status) {
        400, 422 -> Problem.Invalid
        404, 410 -> Problem.Gone
        409 -> Problem.Conflict
        429 -> Problem.TooMany
        else -> Problem.Other
    }
    // ponytail: network failures are platform classes (java.net.*); matched by name to stay in commonMain.
    else -> if (Regex("Connect|Socket|UnknownHost|IOException|Timeout").containsMatchIn(e::class.simpleName.orEmpty())) Problem.Offline else Problem.Other
}

/** The words for a failure; the server's own reason (made brand-free) where it says more than ours. */
fun Problem.text(s: MessagingPageStrings, base: Strings, detail: String?, bot: String): String = when (this) {
    Problem.Offline -> s.offline
    Problem.Invalid -> plain(detail, bot).ifBlank { s.invalid }
    Problem.Gone -> s.gone
    Problem.Conflict -> plain(detail, bot).ifBlank { s.conflict }
    Problem.TooMany -> s.tooMany
    Problem.Other -> base.error
}

private val ENGINE = Regex("\\bHermes(?:\\s+Agent)?\\b")
private val ENGINE_LOWER = Regex("\\bhermes\\b")
private val VENDOR = Regex("\\s*\\bNous(?:\\s+Research)?\\b", RegexOption.IGNORE_CASE)
private val BRANDED = Regex("hermes|nous", RegexOption.IGNORE_CASE)

/** Server copy without the engine's name (design rule: no engine branding): "Hermes" becomes the bot's name. */
fun plain(text: String?, bot: String): String =
    text.orEmpty().replace(ENGINE, Regex.escapeReplacement(bot)).replace(ENGINE_LOWER, "bot").replace(VENDOR, "").replace(Regex(" {2,}"), " ").trim()

/** A link the app may open: http(s) and not the engine's own site. */
fun ownLink(url: String?): String? = url?.trim()?.takeIf { it.startsWith("http") && !BRANDED.containsMatchIn(it) }

/** How a platform's state reads (hermes-api.md: connected = good, disabled = muted, fatal / startup_failed = bad, else warn). */
enum class Tone { Good, Muted, Bad, Warn }

val MsgPlatform.tone: Tone get() = when (state) {
    "connected" -> Tone.Good
    "disabled" -> Tone.Muted
    "fatal", "startup_failed" -> Tone.Bad
    else -> Tone.Warn
}

fun stateLabel(state: String, s: MessagingPageStrings): String = when (state) {
    "connected" -> s.stConnected
    "connecting" -> s.stConnecting
    "fatal" -> s.stFatal
    "degraded" -> s.stDegraded
    "draining" -> s.stDraining
    "disabled" -> s.stOff
    "not_configured" -> s.stNotSetUp
    "pending_restart" -> s.stPendingRestart
    "startup_failed" -> s.stStartupFailed
    "gateway_stopped" -> s.stEngineOff
    else -> state.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** Shown first in the add list; the rest keep the server's order. */
private val POPULAR = listOf("telegram", "whatsapp", "discord", "slack", "signal", "email", "sms", "teams", "google_chat", "matrix", "line")

/** QR setups the backend offers natively. */
val QR_SETUP = setOf("telegram", "whatsapp")

/** A Telegram or WhatsApp QR setup in progress. */
@Immutable
data class Setup(
    val platform: String,
    val stage: Stage = Stage.Options,
    val pairingId: String? = null,
    val qr: String? = null,
    /** Telegram: the t.me link that opens the new bot. */
    val link: String? = null,
    /** `@username` of the Telegram bot, or the linked WhatsApp account. */
    val handle: String? = null,
    val ownerId: String? = null,
    /** WhatsApp: the bridge is starting or installing (no QR yet). */
    val preparing: Boolean = false,
    val mode: String = "bot",
    val allowed: String? = null,
    val problem: Problem? = null,
    val detail: String? = null,
) {
    enum class Stage { Options, Waiting, Ready, Applying, Failed }
}

/** One-time things the page shows as a toast, a sound and a buddy one-shot; a new [n] fires it. */
sealed interface Flash {
    val n: Long
    data class Connected(val platform: String, override val n: Long) : Flash
    data class Saved(override val n: Long) : Flash
    data class Approved(val name: String, override val n: Long) : Flash
    data class Removed(override val n: Long) : Flash
    data class Cleared(override val n: Long) : Flash
    data class Restarted(val ok: Boolean, override val n: Long) : Flash
    data class Failed(val problem: Problem, val detail: String?, override val n: Long) : Flash
}

/** A Result that lets a timeout through as a failure but never swallows a real cancellation. */
private suspend inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: TimeoutCancellationException) {
    Result.failure(e)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/**
 * State and intents of More ▸ Messaging for one bot (profile), hermes-api.md §2.6 Messaging platforms.
 * Switches and pairing answers are optimistic; `platforms.changed` / `pairing.changed` refetch.
 */
@Stable
class MessagingModel(private val store: AppStore, private val scope: CoroutineScope) {
    private val api get() = store.api

    var profile by mutableStateOf("default"); private set
    var loading by mutableStateOf(true); private set
    var loadProblem by mutableStateOf<Problem?>(null); private set
    var platforms by mutableStateOf<List<MsgPlatform>>(emptyList()); private set
    var pending by mutableStateOf<List<MsgPairingUser>>(emptyList()); private set
    var approved by mutableStateOf<List<MsgPairingUser>>(emptyList()); private set
    var needsRestart by mutableStateOf(false); private set
    var restartBusy by mutableStateOf(false); private set
    var restarting by mutableStateOf(false); private set
    var setup by mutableStateOf<Setup?>(null); private set
    var flash by mutableStateOf<Flash?>(null); private set

    /** Platform ids (saves) and `test:<id>` with a call in flight. */
    val busy = mutableStateMapOf<String, Boolean>()
    val tests = mutableStateMapOf<String, MsgTest>()
    private val enabledOverride = mutableStateMapOf<String, Boolean>()
    private var loadJob: Job? = null
    private var setupJob: Job? = null
    private var serial = 0L

    // ------------------------------------------------------------------ reads

    fun platform(id: String): MsgPlatform? = platforms.firstOrNull { it.id == id }
    fun enabled(p: MsgPlatform): Boolean = enabledOverride[p.id] ?: p.enabled
    private fun inUse(p: MsgPlatform) = p.enabled || p.configured || enabledOverride[p.id] == true

    /** On or set up, by name. */
    fun connected(): List<MsgPlatform> = platforms.filter(::inUse)

    /** Everything else, popular apps first (stable sort: the rest keep the server's order). */
    fun available(): List<MsgPlatform> = platforms.filterNot(::inUse).sortedBy { POPULAR.indexOf(it.id).let { i -> if (i < 0) POPULAR.size else i } }

    val restartNeeded: Boolean get() = !restarting && (needsRestart || platforms.any { it.state == "pending_restart" })

    fun approvedOn(id: String): List<MsgPairingUser> = approved.filter { it.platform == id }
    fun pendingOn(id: String): List<MsgPairingUser> = pending.filter { it.platform == id }

    // ------------------------------------------------------------------ loading

    /** Loads [initialProfile], then refetches on change events and after a reconnect for as long as the page is shown. */
    suspend fun run(initialProfile: String) {
        load(initialProfile)
        coroutineScope {
            launch {
                store.gateway.events.collect { e ->
                    when (e.type) {
                        "platforms.changed" -> refreshPlatforms()
                        "pairing.changed" -> refreshPairing()
                    }
                }
            }
            launch { store.gateway.state.drop(1).collect { if (it is LinkState.Ready && !restarting) load() } }
        }
    }

    fun load(newProfile: String = profile) {
        if (newProfile != profile) {
            cancelSetup()
            platforms = emptyList(); pending = emptyList(); approved = emptyList()
            tests.clear(); enabledOverride.clear(); busy.clear()
            needsRestart = false; restartBusy = false
        }
        profile = newProfile
        loadJob?.cancel()
        loadJob = scope.launch {
            loading = true
            coroutineScope {
                val plat = async { attempt { api.messagingPlatforms(newProfile) } }
                val pair = async { attempt { api.messagingPairing(newProfile) } }
                plat.await().onSuccess { platforms = it }
                pair.await().onSuccess { pending = it.pending; approved = it.approved }
                loadProblem = plat.await().exceptionOrNull()?.let(::problemOf)
            }
            loading = false
        }
    }

    private fun refreshPlatforms() {
        val p = profile
        scope.launch { attempt { api.messagingPlatforms(p) }.onSuccess { if (p == profile) platforms = it } }
    }

    private fun refreshPairing() {
        val p = profile
        scope.launch { attempt { api.messagingPairing(p) }.onSuccess { if (p == profile) { pending = it.pending; approved = it.approved } } }
    }

    // ------------------------------------------------------------------ settings

    fun setEnabled(id: String, on: Boolean) {
        enabledOverride[id] = on
        val p = profile
        scope.launch {
            attempt { api.messagingUpdate(p, id, enabled = on) }.onSuccess(::applied).onFailure(::fail)
            attempt { api.messagingPlatforms(p) }.onSuccess { if (p == profile) platforms = it }
            if (enabledOverride[id] == on) enabledOverride.remove(id)
        }
    }

    /** Saves typed values ([env]) and removes saved ones ([clear]), turning the platform on with [enable] (first setup); true when saved. */
    suspend fun save(id: String, env: Map<String, String>, clear: List<String>, enable: Boolean? = null): Boolean {
        if (env.isEmpty() && clear.isEmpty()) return true
        val p = profile
        busy[id] = true
        val r = attempt { api.messagingUpdate(p, id, enabled = enable, env = env, clearEnv = clear) }
        busy.remove(id)
        r.onSuccess { applied(it); emit { n -> Flash.Saved(n) } }.onFailure(::fail)
        tests.remove(id)
        attempt { api.messagingPlatforms(p) }.onSuccess { if (p == profile) platforms = it }
        return r.isSuccess
    }

    fun clearValue(id: String, key: String) {
        scope.launch { save(id, emptyMap(), listOf(key)) }
    }

    /** A save applies live when a multiplexer serves the profile (re-read after ~4 s); else it waits for a restart. */
    private fun applied(r: MsgSaved) {
        if (r.hotServed) scope.launch { delay(4_000); refreshPlatforms() } else needsRestart = true
    }

    fun test(id: String) {
        val key = "test:$id"
        if (busy[key] == true) return
        busy[key] = true
        tests.remove(id)
        val p = profile
        scope.launch {
            attempt { api.messagingTest(p, id) }
                .onSuccess { tests[id] = it }
                .onFailure { tests[id] = MsgTest(ok = false); fail(it) }
            busy.remove(key)
        }
    }

    // ------------------------------------------------------------------ pairing

    fun approve(u: MsgPairingUser) {
        val rid = u.requestId ?: return
        val before = pending to approved
        pending = pending - u
        approved = approved + u.copy(requestId = null)
        val p = profile
        scope.launch {
            attempt { api.messagingApprove(p, u.platform, rid) }
                .onSuccess { emit { n -> Flash.Approved(u.userName?.takeIf { it.isNotBlank() } ?: u.userId, n) } }
                .onFailure { pending = before.first; approved = before.second; fail(it) }
            refreshPairing()
        }
    }

    fun revoke(u: MsgPairingUser) {
        val before = approved
        approved = approved.filterNot { it.platform == u.platform && it.userId == u.userId }
        val p = profile
        scope.launch {
            attempt { api.messagingRevoke(p, u.platform, u.userId) }
                .onSuccess { emit { n -> Flash.Removed(n) } }
                .onFailure { approved = before; fail(it) }
            refreshPairing()
        }
    }

    fun clearPending() {
        val before = pending
        pending = emptyList()
        val p = profile
        scope.launch {
            attempt { api.messagingClearPending(p) }
                .onSuccess { emit { n -> Flash.Cleared(n) } }
                .onFailure { pending = before; fail(it) }
        }
    }

    // ------------------------------------------------------------------ restart

    fun restart() {
        if (restarting) return
        restarting = true
        needsRestart = false; restartBusy = false
        scope.launch {
            attempt { api.messagingRestart(profile) }
                .onSuccess { pid -> followRestart(pid) }
                .onFailure { restarting = false; needsRestart = true; fail(it) }
        }
    }

    /**
     * Follows `gateway-restart` until its process ends, then reloads. The API is served by the engine that restarts, so
     * failed polls are expected: the server going away and answering again also counts as done.
     */
    private suspend fun followRestart(pid: Long?) {
        restarting = true
        var ok: Boolean? = null
        var wentAway = false
        // ponytail: 1.5 s polls, about 45 s at most; then reload whatever state the engine is in.
        for (i in 0 until 30) {
            delay(1_500)
            val r = attempt { api.messagingRestartStatus() }
            val st = r.getOrNull()
            if (st != null) {
                if (!st.running && (pid == null || st.pid == pid)) { ok = (st.exitCode ?: 0) == 0; break }
                if (wentAway) break
            } else {
                val e = r.exceptionOrNull()
                if (e is RestError && wentAway) break
                if (e !is RestError) wentAway = true
            }
        }
        store.gateway.awaitReady(15_000)
        restarting = false
        if (ok == false) needsRestart = true
        emit { n -> Flash.Restarted(ok != false, n) }
        load()
    }

    // ------------------------------------------------------------------ QR setups

    fun beginSetup(platform: String) {
        if (setup?.stage == Setup.Stage.Applying) return
        cancelSetup()
        setup = Setup(platform)
    }

    private fun update(f: (Setup) -> Setup) { setup = setup?.let(f) }

    private fun failSetup(e: Throwable?, problem: Problem = problemOf(e), detail: String? = (e as? RestError)?.detail) {
        update { it.copy(stage = Setup.Stage.Failed, problem = problem, detail = detail, preparing = false) }
    }

    fun startTelegram(botName: String) {
        setupJob?.cancel()
        setup = Setup("telegram", Setup.Stage.Waiting, preparing = true)
        val p = profile
        setupJob = scope.launch {
            val r = attempt { api.messagingTelegramStart(p, botName) }.getOrElse { return@launch failSetup(it) }
            val id = r.pairingId ?: return@launch failSetup(null)
            update { it.copy(pairingId = id, qr = r.qrPayload ?: r.deepLink, link = r.deepLink, handle = r.suggestedUsername?.let(::at), preparing = false) }
            val until = nowMillis() + 11 * 60_000
            while (nowMillis() < until) {
                delay(2_000)
                val st = attempt { api.messagingTelegramStatus(p, id) }
                val e = st.exceptionOrNull()
                if (e is RestError && (e.status == 404 || e.status == 410)) return@launch failSetup(e, Problem.Gone, null)
                val v = st.getOrNull() ?: continue
                if (v.status == "ready") {
                    update { it.copy(stage = Setup.Stage.Ready, handle = v.botUsername?.let(::at) ?: it.handle, ownerId = v.ownerUserId) }
                    return@launch
                }
            }
            failSetup(null, Problem.Gone, null)
        }
    }

    /** Owner plus any extra numeric Telegram user ids typed in [extra]. */
    fun applyTelegram(extra: String) {
        val s = setup ?: return
        val id = s.pairingId ?: return
        val ids = (listOfNotNull(s.ownerId) + extra.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() && it.all(Char::isDigit) }).distinct()
        if (ids.isEmpty()) return update { it.copy(problem = Problem.Invalid, detail = null) }
        update { it.copy(stage = Setup.Stage.Applying, problem = null) }
        val p = profile
        setupJob = scope.launch {
            attempt { api.messagingTelegramApply(p, id, ids) }
                .onSuccess { r -> setup = null; afterApply(r, "telegram") }
                .onFailure { e -> update { it.copy(stage = Setup.Stage.Ready, problem = problemOf(e), detail = (e as? RestError)?.detail) }; fail(e) }
        }
    }

    fun startWhatsapp(mode: String, allowed: String, reset: Boolean) {
        setupJob?.cancel()
        setup = Setup("whatsapp", Setup.Stage.Waiting, preparing = true, mode = mode, allowed = allowed.ifBlank { null })
        val p = profile
        setupJob = scope.launch {
            var r: MsgWhatsappSession = attempt { api.messagingWhatsappStart(p, mode, allowed, reset) }.getOrElse { return@launch failSetup(it) }
            val id = r.pairingId ?: return@launch failSetup(null)
            update { it.copy(pairingId = id) }
            val until = nowMillis() + 11 * 60_000
            while (nowMillis() < until) {
                when (r.status) {
                    "connected" -> {
                        val who = listOfNotNull(r.accountName, r.accountPhone).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }
                        return@launch update { it.copy(stage = Setup.Stage.Ready, preparing = false, qr = null, handle = who) }
                    }
                    "error" -> return@launch failSetup(null, Problem.Other, r.error)
                    "expired", "cancelled" -> return@launch failSetup(null, Problem.Gone, null)
                    "waiting" -> update { it.copy(preparing = r.qrPayload.isNullOrBlank(), qr = r.qrPayload ?: it.qr) }
                    else -> update { it.copy(preparing = true) }
                }
                delay(1_500)
                val st = attempt { api.messagingWhatsappStatus(p, id) }
                val e = st.exceptionOrNull()
                if (e is RestError && (e.status == 404 || e.status == 410)) return@launch failSetup(e, Problem.Gone, null)
                st.getOrNull()?.let { r = it }
            }
            failSetup(null, Problem.Gone, null)
        }
    }

    fun applyWhatsapp() {
        val s = setup ?: return
        val id = s.pairingId ?: return
        update { it.copy(stage = Setup.Stage.Applying, problem = null) }
        val p = profile
        setupJob = scope.launch {
            attempt { api.messagingWhatsappApply(p, id, s.mode, s.allowed) }
                .onSuccess { r -> setup = null; afterApply(r, "whatsapp") }
                .onFailure { e -> update { it.copy(stage = Setup.Stage.Ready, problem = problemOf(e), detail = (e as? RestError)?.detail) }; fail(e) }
        }
    }

    private suspend fun afterApply(r: MsgSetupApplied, platform: String) {
        emit { n -> Flash.Connected(platform, n) }
        when {
            r.restartStarted -> followRestart(r.restartPid)
            r.needsRestart -> { needsRestart = true; restartBusy = r.restartBusy; load() }
            else -> load()
        }
    }

    /**
     * Stops a QR setup and tells the backend to drop it. An apply already underway is left to finish: its answer says
     * whether the backend started a restart, which the page then follows.
     */
    fun cancelSetup() {
        val s = setup ?: return
        if (s.stage == Setup.Stage.Applying) return
        setupJob?.cancel(); setupJob = null
        setup = null
        val id = s.pairingId ?: return
        val p = profile
        scope.launch {
            attempt { if (s.platform == "telegram") api.messagingTelegramCancel(p, id) else api.messagingWhatsappCancel(p, id) }
        }
    }

    // ------------------------------------------------------------------ flashes

    private fun emit(make: (Long) -> Flash) { flash = make(++serial) }
    private fun fail(e: Throwable) = emit { n -> Flash.Failed(problemOf(e), (e as? RestError)?.detail, n) }
}

private fun at(name: String) = "@" + name.trim().removePrefix("@")
