package app.buddyverse.ui.more.models

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.AppStore
import app.buddyverse.core.RestError
import app.buddyverse.core.RpcError
import app.buddyverse.core.api.HelperSlot
import app.buddyverse.core.api.LocalCatalogEntry
import app.buddyverse.core.api.OwnEndpoint
import app.buddyverse.core.api.OwnEndpointDraft
import app.buddyverse.core.api.EnvKey
import app.buddyverse.core.api.PcHardware
import app.buddyverse.core.api.HubFiles
import app.buddyverse.core.api.HubHit
import app.buddyverse.core.api.CredentialCheck
import app.buddyverse.core.api.LocalModelJob
import app.buddyverse.core.api.LocalRuntime
import app.buddyverse.core.api.DefaultModelInfo
import app.buddyverse.core.api.PickedModel
import app.buddyverse.core.api.ModelDefaults
import app.buddyverse.core.api.modelsApi
import app.buddyverse.core.api.modelsDebrand
import app.buddyverse.core.api.ProviderLogin
import app.buddyverse.core.api.ProviderRow
import app.buddyverse.core.api.SubagentRouting
import app.buddyverse.core.api.Team
import app.buddyverse.core.api.TeamSlot
import app.buddyverse.core.api.Teams
import app.buddyverse.core.nowMillis
import app.buddyverse.design.Feedback
import app.buddyverse.design.Haptic
import app.buddyverse.design.Sound
import app.buddyverse.i18n.ModelsPageStrings
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.Toasts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where a picked model goes. */
sealed interface PickTarget {
    data object Main : PickTarget
    data class Helper(val task: String) : PickTarget
    data object Fallback : PickTarget
    /** The model subagents run on (null pick = the bot's own). */
    data object Subagent : PickTarget
    /** A new advisor in team [team]. */
    data class TeamAdvisor(val team: String) : PickTarget
    /** The model that combines team [team]'s answers. */
    data class TeamCombiner(val team: String) : PickTarget
}

/** How a provider signs in. */
enum class Access { Device, External, Key, Pc }

/** One provider as the page shows it: the model catalog row and/or the sign-in row of the same id. */
@Immutable
data class ProviderEntry(val slug: String, val name: String, val provider: ProviderRow?, val oauth: ProviderLogin?, val access: Access) {
    val connected: Boolean get() = provider?.authenticated == true || oauth?.loggedIn == true
}

/** A running phone sign-in (device code): show [code], open [url], the PC finishes it. */
@Immutable
data class DeviceFlow(val id: String, val name: String, val sessionId: String, val code: String, val url: String)

/** A pick the PC wants confirmed first (cost, data policy, a large context jump). */
@Immutable
data class PendingPick(val target: PickTarget, val ref: PickedModel?, val message: String)

private fun Throwable.reason(): String = modelsDebrand(
    when (this) {
        is RestError -> detail
        is RpcError -> message ?: reason
        else -> message ?: toString()
    },
)

private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
    try { Result.success(block()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }

/**
 * State and intents of More ▸ Models for one bot ([profile]). Every change is optimistic (the UI moves at touch-down),
 * runs on the store's scope (it survives leaving the page) and re-reads the PC afterwards; a failure says why and rolls back.
 */
@Stable
class ModelsModel(private val store: AppStore, private val toasts: Toasts, private val fb: Feedback) {
    private val api get() = store.api.modelsApi
    lateinit var t: ModelsPageStrings
    var undoLabel = "Undo"

    var profile by mutableStateOf<String?>(null); private set
    var loaded by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var unsupported by mutableStateOf(false); private set
    var info by mutableStateOf<DefaultModelInfo?>(null); private set
    var providers by mutableStateOf<List<ProviderRow>>(emptyList()); private set
    var settings by mutableStateOf<ModelDefaults?>(null); private set
    var helpers by mutableStateOf<List<HelperSlot>>(emptyList()); private set
    var oauth by mutableStateOf<List<ProviderLogin>>(emptyList()); private set
    var env by mutableStateOf<List<EnvKey>>(emptyList()); private set
    var endpoints by mutableStateOf<List<OwnEndpoint>>(emptyList()); private set
    var local by mutableStateOf<LocalRuntime?>(null); private set
    var hardware by mutableStateOf<PcHardware?>(null); private set
    var catalog by mutableStateOf<List<LocalCatalogEntry>?>(null); private set
    var catalogError by mutableStateOf<String?>(null); private set
    var jobs by mutableStateOf<List<LocalModelJob>>(emptyList()); private set
    /** Team answers (Mixture of Agents presets); null when the PC has none to offer. */
    var teams by mutableStateOf<Teams?>(null); private set
    var routing by mutableStateOf<SubagentRouting?>(null); private set
    /** Rows waiting out their undo window: `env:KEY`, `ep:id`, `local:id`. */
    var hidden by mutableStateOf<Set<String>>(emptySet()); private set
    /** In-flight writes by tag, for per-row "working" looks. */
    var busy by mutableStateOf<Set<String>>(emptySet()); private set
    var flow by mutableStateOf<DeviceFlow?>(null); private set
    var pending by mutableStateOf<PendingPick?>(null); private set
    var cue by mutableStateOf<BuddyCue?>(null); private set
    var failedAt by mutableStateOf(0L); private set

    val working: Boolean get() = busy.isNotEmpty() || flow != null || jobs.any { it.status == "running" }

    /** Every provider once: catalog rows with their sign-in row attached, plus sign-in-only rows. Current, then connected, first. */
    val entries: List<ProviderEntry>
        get() {
            val byId = oauth.associateBy { it.id }
            val rows = providers.map { p -> entry(p.slug, p.name, p, byId[p.slug]) } +
                oauth.filter { o -> providers.none { it.slug == o.id } }.map { o -> entry(o.id, o.name, null, o) }
            return rows.sortedWith(compareByDescending<ProviderEntry> { it.provider?.current == true }.thenByDescending { it.connected }.thenBy { it.name.lowercase() })
        }

    private fun entry(slug: String, name: String, p: ProviderRow?, o: ProviderLogin?): ProviderEntry {
        val access = when {
            o?.flow == "device_code" -> Access.Device
            o?.flow == "external" -> Access.External
            p?.authType == "api_key" && keysFor(slug, p).isNotEmpty() -> Access.Key
            else -> Access.Pc
        }
        return ProviderEntry(slug, name, p, o, access)
    }

    /** The provider's own `.env` entries: its main key first, then the rest (addresses, regions). */
    fun keysFor(slug: String, p: ProviderRow? = providers.firstOrNull { it.slug == slug }): List<EnvKey> =
        env.filter { (it.provider == slug || it.key == p?.keyEnv) && "env:${it.key}" !in hidden }
            .sortedWith(compareByDescending<EnvKey> { it.key == p?.keyEnv }.thenByDescending { it.primary }.thenByDescending { it.isPassword })

    fun providerName(slug: String): String =
        providers.firstOrNull { it.slug == slug }?.name ?: oauth.firstOrNull { it.id == slug }?.name
            ?: endpoints.firstOrNull { it.id == slug || "custom:${it.name.lowercase()}" == slug.lowercase() }?.name ?: slug

    /** Providers you can pick a model from right now. */
    val pickable: List<ProviderRow> get() = providers.filter { it.authenticated && it.models.isNotEmpty() }

    private fun react(shot: OneShot) { cue = BuddyCue(nowMillis(), shot) }
    private fun ouch() { failedAt = nowMillis(); fb.sound(Sound.Bonk); fb.haptic(Haptic.Reject) }
    private fun fail(msg: String) { ouch(); toasts.show(ToastData(msg)) }
    private fun yay(msg: String, shot: OneShot = OneShot.Done, sound: Boolean = false) {
        fb.haptic(Haptic.Confirm); if (sound) fb.sound(Sound.Bloop); react(shot); toasts.show(ToastData(msg))
    }

    fun load(p: String, refresh: Boolean = false) {
        if (p != profile) {
            profile = p; loaded = false; error = null; unsupported = false; info = null; providers = emptyList(); settings = null
            helpers = emptyList(); oauth = emptyList(); env = emptyList(); endpoints = emptyList(); hidden = emptySet(); pending = null
            teams = null; routing = null
            flow?.let { f -> store.scope.launch { attempt { api.loginCancel(f.sessionId) } } }
            flow = null
        }
        store.scope.launch { refreshAll(p, refresh, withHardware = hardware == null) }
    }

    private suspend fun refreshAll(p: String, refresh: Boolean = false, withHardware: Boolean = false) = coroutineScope {
        val i = async { attempt { api.defaultModel(p) } }
        val o = async { attempt { api.catalog(p, refresh) } }
        val s = async { attempt { api.defaults(p) } }
        val a = async { attempt { api.helpers(p) } }
        val oa = async { attempt { api.logins(p) } }
        val e = async { attempt { api.envKeys(p) } }
        val ep = async { attempt { api.endpoints(p) } }
        val tm = async { attempt { api.teams(p) } }
        val rt = async { attempt { api.subagentRouting(p) } }
        val hw = if (withHardware) async { attempt { api.localHardware() } } else null
        val ir = i.await()
        if (p != profile) return@coroutineScope
        ir.onSuccess { info = it; error = null; unsupported = false }.onFailure {
            if (it is RestError && it.status == 404) { unsupported = true } else { error = it.reason() }
        }
        o.await().onSuccess { providers = it.providers }
        s.await().onSuccess { settings = it }
        a.await().onSuccess { helpers = it }
        oa.await().onSuccess { oauth = it }
        e.await().onSuccess { env = it }
        ep.await().onSuccess { endpoints = it }
        tm.await().onSuccess { teams = it }
        rt.await().onSuccess { routing = it }
        hw?.await()?.onSuccess { hardware = it }
        loaded = true
        refreshLocal()
    }

    /** Local runtime and jobs; a job that just ended says so and refreshes what it changed. */
    suspend fun refreshLocal() {
        val st = attempt { api.localStatus() }.getOrNull()
        val js = attempt { api.localJobs() }.getOrNull()
        if (st != null) local = st
        if (js == null) return
        val before = jobs.associateBy { it.id }
        jobs = js
        val ended = js.filter { j -> before[j.id]?.active == true && !j.active }
        for (j in ended) {
            if (j.status == "done") yay(t.ready(j.target.ifBlank { t.localEngine }), OneShot.Learned, sound = true)
            else fail(j.error ?: t.failedJob(j.target))
        }
        if (ended.isNotEmpty()) {
            catalog = null
            val p = profile
            if (p != null) attempt { api.defaultModel(p) }.onSuccess { if (p == profile) { info = it } }
            if (st == null) attempt { api.localStatus() }.onSuccess { local = it }
        }
    }

    /** Fast while a job runs, slow otherwise. */
    val pollMs: Long get() = if (jobs.any { it.active }) 1_500 else 8_000

    private fun write(tag: String, rollback: () -> Unit = {}, block: suspend (String) -> Unit) {
        val p = profile ?: return
        busy = busy + tag
        store.scope.launch {
            try { block(p) } catch (e: CancellationException) { throw e } catch (e: Exception) { rollback(); fail(e.reason()) }
            finally { busy = busy - tag }
            if (p == profile) refreshAll(p)
        }
    }

    // ---- model choices

    fun pick(target: PickTarget, ref: PickedModel?, confirmed: Boolean = false) {
        pending = null
        val p = profile ?: return
        when (target) {
            PickTarget.Main -> {
                ref ?: return
                val old = info
                info = info?.copy(model = ref.model, provider = ref.provider)
                write("main", { info = old }) { assign(p, PickTarget.Main, ref, confirmed) { info = old } }
            }
            is PickTarget.Helper -> {
                val old = helpers
                helpers = helpers.map { if (it.task == target.task) it.copy(provider = ref?.provider ?: "auto", model = ref?.model.orEmpty()) else it }
                write("helper:${target.task}", { helpers = old }) { assign(p, target, ref, confirmed) { helpers = old } }
            }
            PickTarget.Fallback -> {
                ref ?: return
                val s = settings ?: return
                if (ref in s.fallbacks) return
                val next = s.fallbacks + ref
                settings = s.copy(fallbacks = next)
                write("fallbacks", { settings = s }) { api.patchConfig(it, mapOf("fallback_providers" to fallbackList(next))); yay(t.saved, OneShot.Nod) }
            }
            PickTarget.Subagent -> {
                val s = settings ?: return
                settings = s.copy(subagent = ref)
                write("sub:model", { settings = s }) {
                    api.patchConfig(it, mapOf("delegation" to mapOf("provider" to ref?.provider.orEmpty(), "model" to ref?.model.orEmpty())))
                    yay(t.saved, OneShot.Nod)
                }
            }
            is PickTarget.TeamAdvisor -> {
                ref ?: return
                editTeam(target.team) { it.copy(advisors = it.advisors + TeamSlot(ref.provider, ref.model)) }
            }
            is PickTarget.TeamCombiner -> {
                ref ?: return
                editTeam(target.team) { it.copy(combiner = it.combiner.copy(provider = ref.provider, model = ref.model)) }
            }
        }
    }

    private suspend fun assign(p: String, target: PickTarget, ref: PickedModel?, confirmed: Boolean, rollback: () -> Unit) {
        val r = when (target) {
            PickTarget.Main -> api.assign(p, "main", ref!!.provider, ref.model, confirmed = confirmed)
            is PickTarget.Helper -> api.assign(p, "auxiliary", ref?.provider ?: "auto", ref?.model.orEmpty(), task = target.task, confirmed = confirmed)
            PickTarget.Fallback, PickTarget.Subagent, is PickTarget.TeamAdvisor, is PickTarget.TeamCombiner -> return
        }
        if (r.confirm != null) { rollback(); fb.haptic(Haptic.LongPress); pending = PendingPick(target, ref, r.confirm.ifBlank { t.confirmCost }); return }
        yay(if (target == PickTarget.Main) t.newChatsUse(ref!!.model) else t.saved, if (target == PickTarget.Main) OneShot.Learned else OneShot.Nod)
    }

    fun confirmPending() { pending?.let { pick(it.target, it.ref, confirmed = true) } }
    fun dropPending() { pending = null }

    fun resetHelpers() {
        val old = helpers
        helpers = helpers.map { it.copy(provider = "auto", model = "") }
        write("helpers", { helpers = old }) { api.assign(it, "auxiliary", "auto", "", task = "__reset__"); yay(t.saved, OneShot.Nod) }
    }

    fun removeFallback(ref: PickedModel) {
        val s = settings ?: return
        val next = s.fallbacks - ref
        settings = s.copy(fallbacks = next)
        fb.haptic(Haptic.Tick)
        write("fallbacks", { settings = s }) { api.patchConfig(it, mapOf("fallback_providers" to fallbackList(next))) }
    }

    fun moveFallback(ref: PickedModel, up: Boolean) {
        val s = settings ?: return
        val i = s.fallbacks.indexOf(ref)
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in s.fallbacks.indices) return
        val next = s.fallbacks.toMutableList().apply { add(j, removeAt(i)) }
        settings = s.copy(fallbacks = next)
        fb.haptic(Haptic.Tick)
        write("fallbacks", { settings = s }) { api.patchConfig(it, mapOf("fallback_providers" to fallbackList(next))) }
    }

    private fun fallbackList(l: List<PickedModel>) = l.map { mapOf("provider" to it.provider, "model" to it.model) }

    fun setReasoning(level: String) {
        val s = settings ?: return
        if (s.reasoning == level) return
        settings = s.copy(reasoning = level)
        react(OneShot.Nod)
        write("reasoning", { settings = s }) { api.patchConfig(it, mapOf("agent" to mapOf("reasoning_effort" to level))) }
    }

    /** "" normal · `priority` fast · `ultrafast`. */
    fun setSpeed(tier: String) {
        val s = settings ?: return
        if (s.serviceTier == tier) return
        settings = s.copy(serviceTier = tier)
        react(if (tier.isEmpty()) OneShot.Nod else OneShot.Hop)
        write("speed", { settings = s }) { api.patchConfig(it, mapOf("agent" to mapOf("service_tier" to tier))) }
    }

    /** 0 = automatic. */
    fun setContext(tokens: Long) {
        val s = settings ?: return
        settings = s.copy(contextLength = tokens)
        write("context", { settings = s }) { api.patchConfig(it, mapOf("model_context_length" to tokens)); yay(t.saved, OneShot.Nod) }
    }

    // ---- subagents (delegate_task children): their model, thinking, how many at once, how deep, where they run

    /** "" = same as the bot. */
    fun setSubReasoning(level: String) {
        val s = settings ?: return
        if (s.subReasoning == level) return
        settings = s.copy(subReasoning = level)
        react(OneShot.Nod)
        write("sub:reasoning", { settings = s }) { api.patchConfig(it, mapOf("delegation" to mapOf("reasoning_effort" to level))) }
    }

    fun setSubParallel(n: Int) {
        val s = settings ?: return
        if (s.subParallel == n) return
        settings = s.copy(subParallel = n)
        react(OneShot.Hop)
        write("sub:parallel", { settings = s }) { api.patchConfig(it, mapOf("delegation" to mapOf("max_concurrent_children" to n))) }
    }

    fun setSubDepth(n: Int) {
        val s = settings ?: return
        if (s.subDepth == n) return
        settings = s.copy(subDepth = n)
        react(OneShot.Nod)
        write("sub:depth", { settings = s }) { api.patchConfig(it, mapOf("delegation" to mapOf("max_spawn_depth" to n))) }
    }

    fun setSubRoute(name: String) {
        val r = routing ?: return
        if (r.configured == name) return
        routing = r.copy(configured = name)
        fb.haptic(Haptic.Tick)
        write("sub:route", { routing = r }) { api.setSubagentRoute(it, name); yay(t.saved, OneShot.Nod) }
    }

    // ---- team answers: advisors answer, one model combines (Mixture of Agents presets)

    /** Saves [next] at once, or after the undo window when [undo] names what was removed. */
    private fun saveTeams(next: Teams, undo: String? = null) {
        val old = teams ?: return
        val p = profile ?: return
        teams = next
        if (undo == null) {
            write("teams", { teams = old }) { teams = api.saveTeams(it, next) }
            return
        }
        fb.haptic(Haptic.Reject)
        val job = store.scope.launch {
            delay(4_500)
            busy = busy + "teams"
            // Saves what is shown now, so an edit made during the undo window is kept too.
            try { api.saveTeams(p, teams ?: next) } catch (e: CancellationException) { throw e } catch (e: Exception) { teams = old; fail(e.reason()) }
            finally { busy = busy - "teams" }
            if (p == profile) refreshAll(p)
        }
        toasts.show(ToastData(undo, undoLabel, onAction = { job.cancel(); teams = old }))
    }

    private fun editTeam(name: String, f: (Team) -> Team) {
        val all = teams ?: return
        saveTeams(all.copy(teams = all.teams.map { if (it.name == name) f(it) else it }))
    }

    fun setAdvisorOn(team: String, index: Int, on: Boolean) {
        fb.haptic(Haptic.Tick)
        editTeam(team) { tm -> tm.copy(advisors = tm.advisors.mapIndexed { i, a -> if (i == index) a.copy(enabled = on) else a }) }
    }

    /** The PC refuses a team without a complete advisor, so the last one stays. */
    fun removeAdvisor(team: String, index: Int) {
        val tm = teams?.teams?.firstOrNull { it.name == team } ?: return
        if (tm.advisors.count { it.complete } <= 1 && tm.advisors.getOrNull(index)?.complete == true) { fail(t.teamNeedsAdvisor); return }
        val gone = tm.advisors.getOrNull(index) ?: return
        val all = teams ?: return
        saveTeams(all.copy(teams = all.teams.map { if (it.name == team) it.copy(advisors = it.advisors.filterIndexed { i, _ -> i != index }) else it }),
            undo = t.removed(gone.model))
    }

    fun setTeamOn(team: String, on: Boolean) { fb.haptic(Haptic.Tick); editTeam(team) { it.copy(enabled = on) } }

    fun makeDefaultTeam(team: String) {
        val all = teams ?: return
        if (all.defaultTeam == team) return
        react(OneShot.Nod)
        saveTeams(all.copy(defaultTeam = team))
    }

    /** A new team starts as the bot's own model advising and combining; the PC refuses empty slots. Null = the name can't be used. */
    fun addTeam(name: String): String? {
        val n = name.trim()
        val all = teams ?: return t.teamExists
        if (n.isEmpty()) return t.needName
        if (all.teams.any { it.name.equals(n, ignoreCase = true) }) return t.teamExists
        val i = info ?: return t.noProviders
        if (i.provider.isBlank() || i.model.isBlank() || i.provider == "moa") return t.noProviders
        val me = TeamSlot(i.provider, i.model)
        react(OneShot.Learned)
        saveTeams(all.copy(teams = all.teams + Team(n, listOf(me), me, true, null)))
        return null
    }

    fun deleteTeam(team: String) {
        val all = teams ?: return
        if (all.teams.size <= 1) { fail(t.lastTeam); return }
        val rest = all.teams.filter { it.name != team }
        saveTeams(Teams(if (all.defaultTeam == team) rest.first().name else all.defaultTeam, all.activeTeam.takeIf { it != team }.orEmpty(), rest),
            undo = t.removed(team))
    }

    /** New chats answer as this team (the main model becomes the `moa` provider's preset). */
    fun useTeam(team: String) = pick(PickTarget.Main, PickedModel("moa", team))

    // ---- providers: keys and sign-in

    /** Saves changed [values] (blank = unchanged). The main key is tested first where the PC can; a refused key is not saved. */
    fun saveKeys(e: ProviderEntry?, values: Map<String, String>, done: (String?) -> Unit) {
        val changed = values.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        if (changed.isEmpty()) { done(null); return }
        val p = profile ?: return
        val tag = "keys:${e?.slug ?: changed.keys.first()}"
        busy = busy + tag
        store.scope.launch {
            try {
                val main = e?.let { en -> keysFor(en.slug).firstOrNull { it.isPassword }?.key }?.takeIf { it in changed }
                if (main != null) {
                    val check = api.checkKey(main, changed.getValue(main))
                    if (!check.ok && check.reachable) { ouch(); done(check.message.ifBlank { t.keyRefused }); return@launch }
                }
                for ((k, v) in changed) api.setEnv(p, k, v, providerSetup = env.firstOrNull { it.key == k }?.category == "provider")
                done(null)
                yay(if (e != null) t.connected(e.name) else t.saved, OneShot.Learned, sound = e != null)
            } catch (x: CancellationException) { throw x } catch (x: Exception) { ouch(); done(x.reason()) }
            finally { busy = busy - tag }
            if (p == profile) refreshAll(p)
        }
    }

    /** Optimistic removal with an undo window; the PC call happens only once the toast is gone. */
    fun removeKey(v: EnvKey) {
        val p = profile ?: return
        val tag = "env:${v.key}"
        hidden = hidden + tag
        fb.haptic(Haptic.Reject)
        val job = store.scope.launch {
            delay(4_500)
            try { api.deleteEnv(p, v.key) } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e.reason()) }
            hidden = hidden - tag
            if (p == profile) refreshAll(p)
        }
        toasts.show(ToastData(t.removed(v.label), undoLabel, onAction = { job.cancel(); hidden = hidden - tag }))
    }

    /** The real value of a saved key; null when the PC refuses (rate limit) — the reason is toasted. */
    suspend fun reveal(v: EnvKey): String? {
        val p = profile ?: return null
        return attempt { api.revealEnv(p, v.key) }.onFailure { fail(it.reason()) }.getOrNull()
    }

    /** Device-code sign-in: the code shows on the phone, the provider page opens in the browser, the PC finishes it. */
    fun signIn(e: ProviderEntry) {
        val p = profile ?: return
        val o = e.oauth ?: return
        if (flow != null) return
        busy = busy + "signin:${o.id}"
        store.scope.launch {
            var sid: String? = null
            var ok = false
            try {
                val s = api.loginStart(o.id, p)
                sid = s.sessionId
                flow = DeviceFlow(o.id, e.name, s.sessionId, s.userCode, s.verificationUrl)
                busy = busy - "signin:${o.id}"
                val until = nowMillis() + s.expiresIn * 1000
                while (flow?.sessionId == s.sessionId && nowMillis() < until) {
                    val r = api.loginPoll(o.id, s.sessionId, p)
                    when (r.status) {
                        "approved" -> { ok = true; break }
                        "pending" -> Unit
                        "cancelled" -> break
                        else -> throw IllegalStateException(r.error ?: t.signInFailed)
                    }
                }
                if (ok) yay(t.signedIn(e.name), OneShot.Done, sound = true)
            } catch (x: CancellationException) { throw x } catch (x: Exception) {
                if (flow?.sessionId == sid || sid == null) fail(x.reason())
            } finally {
                busy = busy - "signin:${o.id}"
                if (flow?.sessionId == sid) flow = null
                val started = sid
                if (!ok && started != null) attempt { api.loginCancel(started) }
            }
            if (p == profile) refreshAll(p)
        }
    }

    fun cancelSignIn() {
        val f = flow ?: return
        flow = null
        fb.haptic(Haptic.Tick)
        store.scope.launch { attempt { api.loginCancel(f.sessionId) } }
    }

    fun signOut(e: ProviderEntry) {
        val o = e.oauth ?: return
        fb.haptic(Haptic.Reject)
        write("signout:${o.id}") { api.logout(o.id, it); toasts.show(ToastData(t.signedOut(e.name))) }
    }

    // ---- custom endpoints

    suspend fun checkEndpoint(d: OwnEndpointDraft): Result<CredentialCheck> = attempt { api.checkEndpoint(d) }

    fun saveEndpoint(d: OwnEndpointDraft, done: (String?) -> Unit) {
        val err = when {
            d.name.isBlank() -> t.needName
            d.baseUrl.isBlank() -> t.needAddress
            d.model.isBlank() -> t.needModel
            else -> null
        }
        if (err != null) { ouch(); done(err); return }
        val p = profile ?: return
        busy = busy + "ep"
        store.scope.launch {
            try { api.saveEndpoint(p, d); done(null); yay(if (d.id.isBlank()) t.added(d.name.trim()) else t.saved, OneShot.Learned, sound = d.id.isBlank()) }
            catch (x: CancellationException) { throw x } catch (x: Exception) { ouch(); done(x.reason()) }
            finally { busy = busy - "ep" }
            if (p == profile) refreshAll(p)
        }
    }

    fun useEndpoint(ep: OwnEndpoint) {
        val old = info
        info = info?.copy(model = ep.model, provider = ep.id)
        write("ep:${ep.id}", { info = old }) { api.activateEndpoint(it, ep.id); yay(t.newChatsUse(ep.model), OneShot.Learned) }
    }

    fun deleteEndpoint(ep: OwnEndpoint) {
        val p = profile ?: return
        val tag = "ep:${ep.id}"
        hidden = hidden + tag
        fb.haptic(Haptic.Reject)
        val job = store.scope.launch {
            delay(4_500)
            try { api.deleteEndpoint(p, ep.id) } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e.reason()) }
            hidden = hidden - tag
            if (p == profile) refreshAll(p)
        }
        toasts.show(ToastData(t.removed(ep.name), undoLabel, onAction = { job.cancel(); hidden = hidden - tag }))
    }

    // ---- local models (one runtime for the whole PC; "use" sets this bot's default)

    private fun localWrite(tag: String, block: suspend (String) -> Unit) {
        val p = profile ?: return
        busy = busy + tag
        store.scope.launch {
            try { block(p) } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e.reason()) }
            finally { busy = busy - tag }
            refreshLocal()
        }
    }

    fun loadCatalog(force: Boolean = false) {
        if (catalog != null && !force) return
        catalogError = null
        store.scope.launch {
            attempt { api.localCatalog() }.onSuccess { catalog = it }.onFailure { catalogError = it.reason() }
        }
    }

    fun installRuntime() = localWrite("runtime") { api.localInstallRuntime(it); fb.haptic(Haptic.Confirm); react(OneShot.Hop) }
    fun quickstart(modelId: String? = null) = localWrite("quickstart") { api.localQuickstart(it, modelId); fb.haptic(Haptic.Confirm); react(OneShot.Hop) }

    fun download(m: LocalCatalogEntry) = localWrite("dl:${m.id}") {
        if (api.localDownload(m.id) == null) toasts.show(ToastData(t.alreadyHere(m.name))) else { fb.haptic(Haptic.Confirm); react(OneShot.Hop) }
    }

    fun downloadBrowsed(repo: String, f: HubFiles) = localWrite("dl:$repo/${f.label}") {
        if (api.localDownloadBrowsed(repo, f.paths) == null) toasts.show(ToastData(t.alreadyHere(f.label))) else { fb.haptic(Haptic.Confirm); react(OneShot.Hop) }
    }

    fun pause(j: LocalModelJob) = localWrite("job:${j.id}") { api.localPause(j.id) }
    fun resume(j: LocalModelJob) = localWrite("job:${j.id}") { api.localResume(j.id) }

    fun setServer(on: Boolean) {
        val old = local
        local = local?.copy(serverRunning = on)
        localWrite("server") {
            try { api.localServer(on) } catch (e: Exception) { local = old; throw e }
        }
    }

    fun useLocal(modelId: String) = localWrite("local:$modelId") { api.localActivate(it, modelId); fb.haptic(Haptic.Confirm); react(OneShot.Hop) }
    fun eject(modelId: String) = localWrite("local:$modelId") { api.localEject(modelId); toasts.show(ToastData(t.unloaded)) }

    fun deleteLocal(modelId: String) {
        val tag = "local:$modelId"
        hidden = hidden + tag
        fb.haptic(Haptic.Reject)
        val job = store.scope.launch {
            delay(4_500)
            try { api.localDelete(modelId) } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e.reason()) }
            hidden = hidden - tag
            catalog = null
            refreshLocal()
        }
        toasts.show(ToastData(t.removed(modelId), undoLabel, onAction = { job.cancel(); hidden = hidden - tag }))
    }

    suspend fun searchHub(query: String): Result<List<HubHit>> = attempt { api.localSearch(query.trim()) }
    suspend fun repoFiles(repo: String): Result<List<HubFiles>> = attempt { api.localRepoFiles(repo) }
    fun errorOf(e: Throwable): String = e.reason()

    fun sideload(path: String, done: (String?) -> Unit) {
        if (path.isBlank()) return
        busy = busy + "sideload"
        store.scope.launch {
            try { val id = api.localSideload(path.trim()); done(null); yay(t.added(id ?: path.trim()), OneShot.Learned, sound = true) }
            catch (x: CancellationException) { throw x } catch (x: Exception) { ouch(); done(x.reason()) }
            finally { busy = busy - "sideload" }
            refreshLocal()
        }
    }
}
