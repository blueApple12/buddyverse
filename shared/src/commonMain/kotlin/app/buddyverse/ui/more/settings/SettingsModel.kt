package app.buddyverse.ui.more.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.buddyverse.core.AppStore
import app.buddyverse.core.RestError
import app.buddyverse.core.RpcError
import app.buddyverse.core.WireJson
import app.buddyverse.core.api.BillingInfo
import app.buddyverse.core.api.BillingRefusal
import app.buddyverse.core.api.ConfigField
import app.buddyverse.core.api.ConfigSchema
import app.buddyverse.core.api.MetricsConsent
import app.buddyverse.core.api.PlanInfo
import app.buddyverse.core.api.PlanPreview
import app.buddyverse.core.api.PlanTier
import app.buddyverse.core.api.at
import app.buddyverse.core.api.billingInfo
import app.buddyverse.core.api.billingStepUp
import app.buddyverse.core.api.buyCredits
import app.buddyverse.core.api.chargeStatus
import app.buddyverse.core.api.configDefaults
import app.buddyverse.core.api.configSave
import app.buddyverse.core.api.configSchema
import app.buddyverse.core.api.configValues
import app.buddyverse.core.api.metricsConsent
import app.buddyverse.core.api.planChange
import app.buddyverse.core.api.planInfo
import app.buddyverse.core.api.planPreview
import app.buddyverse.core.api.planResume
import app.buddyverse.core.api.setAutoReload
import app.buddyverse.core.api.setKeepGoing
import app.buddyverse.core.api.setMetricsConsent
import app.buddyverse.core.api.withAt
import app.buddyverse.core.nowMillis
import app.buddyverse.core.str
import app.buddyverse.core.uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun Throwable.why(): String = when (this) {
    is RestError -> detail.ifBlank { message.orEmpty() }
    is RpcError -> data.str("detail") ?: reason
    else -> message ?: toString()
}

/** "auto_archive_days" → "Auto archive days". */
internal fun ConfigField.label(): String = key.substringAfterLast('.').replace('_', ' ').replaceFirstChar { it.uppercase() }

/** The value as an editor shows it: a list one item per line, an object or a non-string list as JSON. */
internal fun textOf(v: JsonElement?, type: String): String = when {
    v == null || v is JsonNull -> ""
    v is JsonPrimitive -> v.content
    type == "list" && v is JsonArray && v.all { it is JsonPrimitive } -> v.joinToString("\n") { (it as JsonPrimitive).content }
    else -> v.toString()
}

/** Parses an editor's text back into the field's type; null = not valid for it (nothing is saved). */
internal fun parseFor(text: String, type: String, old: JsonElement?): JsonElement? = when (type) {
    "number" -> text.trim().let { t -> if (t.isEmpty()) null else t.toLongOrNull()?.let { JsonPrimitive(it) } ?: t.toDoubleOrNull()?.let { JsonPrimitive(it) } }
    "list" -> if (old is JsonArray && old.any { it !is JsonPrimitive }) runCatching { WireJson.parseToJsonElement(text) as? JsonArray }.getOrNull()
        else JsonArray(text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { JsonPrimitive(it) })
    "object" -> runCatching { WireJson.parseToJsonElement(text.ifBlank { "{}" }) as? JsonObject }.getOrNull()
    else -> JsonPrimitive(text)
}

/** Where one setting's save is. */
internal sealed interface SaveState {
    data object Saving : SaveState
    data object Saved : SaveState
    data class Failed(val why: String) : SaveState
}

/**
 * Engine settings of one bot (hermes-api.md §2.3 "Settings: config REST"): the schema, the merged values, the defaults.
 * Every change is a sparse `PUT /api/config` (debounced for typing); the screen shows the edit at once and puts it back
 * with the server's words if the save fails. Saves run on the store's scope so leaving the page doesn't drop one.
 */
@Stable
internal class EngineSettings(private val store: AppStore) {
    private val api get() = store.api
    var profile by mutableStateOf(store.bots.value.firstOrNull { it.isDefault }?.profile ?: "default"); private set
    var schema by mutableStateOf<ConfigSchema?>(null); private set
    var values by mutableStateOf<JsonObject?>(null); private set
    var defaults by mutableStateOf<JsonObject?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var metrics by mutableStateOf<MetricsConsent?>(null); private set
    /** Edits not yet confirmed by the server (they win over [values]). */
    val edits = mutableStateMapOf<String, JsonElement>()
    val saves = mutableStateMapOf<String, SaveState>()
    var onSaved: (String, SaveState) -> Unit = { _, _ -> }
    private val jobs = HashMap<String, Job>()

    fun pick(p: String) {
        if (p == profile) return
        profile = p; schema = null; values = null; edits.clear(); saves.clear(); metrics = null
    }

    suspend fun load() {
        val p = profile
        try {
            val sc = api.configSchema(p)
            val v = api.configValues(p)
            if (p != profile) return
            schema = sc; values = v; error = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { if (p == profile) error = e.why() }
        if (defaults == null) defaults = runCatching { api.configDefaults() }.getOrNull()
        runCatching { api.metricsConsent(p) }.getOrNull()?.let { if (p == profile) metrics = it }
    }

    fun valueOf(key: String): JsonElement? = edits[key] ?: values?.at(key)
    fun defaultOf(key: String): JsonElement? = defaults?.at(key)

    /** Shows [v] now and saves it after [debounceMs] (typing waits; toggles and picks go at once). */
    fun set(key: String, v: JsonElement, debounceMs: Long = 0) {
        if (edits[key] == null && values?.at(key) == v) return
        edits[key] = v
        jobs[key]?.cancel()
        val p = profile
        jobs[key] = store.scope.launch {
            if (debounceMs > 0) delay(debounceMs)
            saves[key] = SaveState.Saving
            try {
                api.configSave(p, key, v)
                if (p != profile) return@launch
                values = (values ?: JsonObject(emptyMap())).withAt(key, v)
                if (edits[key] == v) edits.remove(key)
                saves[key] = SaveState.Saved
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (p != profile) return@launch
                if (edits[key] == v) edits.remove(key)
                saves[key] = SaveState.Failed(e.why())
            }
            saves[key]?.let { onSaved(key, it) }
        }
    }

    fun reset(key: String): Boolean = defaultOf(key)?.let { set(key, it); true } ?: false

    fun setMetrics(on: Boolean) {
        val p = profile
        val old = metrics
        metrics = MetricsConsent(on, on, true)
        store.scope.launch {
            try { api.setMetricsConsent(p, on)?.let { if (p == profile) metrics = it } } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (p == profile) metrics = old
                onSaved("shared_metrics", SaveState.Failed(e.why()))
            }
        }
    }

    /** Fields matching [q] in their key, label or description. */
    fun search(q: String): List<ConfigField> {
        val t = q.trim().lowercase()
        if (t.isEmpty()) return emptyList()
        return schema?.fields.orEmpty().filter { f -> t in f.key.lowercase() || t in f.label().lowercase() || t in f.description.lowercase() }.take(60)
    }
}

/** A charge in flight: its key stays the same across retries of this one intent. */
internal data class Charge(val amount: String, val key: String, val state: String, val note: String? = null)

/**
 * Plan & credits (hermes-api.md §2.3 "Billing, subscription, usage"; WS legacy, fail-open). Upgrades finish in the browser
 * portal, downgrades are quoted (`subscription.preview`) then scheduled, money moves only on the user's tap and is polled
 * until settled. An `insufficient_scope` refusal offers the browser approval (`billing.step_up`).
 */
@Stable
internal class BillingModel(private val store: AppStore) {
    private val api get() = store.api
    var profile by mutableStateOf(store.bots.value.firstOrNull { it.isDefault }?.profile ?: "default")
    var info by mutableStateOf<BillingInfo?>(null); private set
    var plans by mutableStateOf<PlanInfo?>(null); private set
    var loaded by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    /** The last refusal that a browser approval would fix. */
    var needsStepUp by mutableStateOf(false); private set
    var stepUpCode by mutableStateOf<Pair<String, String>?>(null); private set
    var steppingUp by mutableStateOf(false); private set
    var charge by mutableStateOf<Charge?>(null); private set
    var previewFor by mutableStateOf<PlanTier?>(null); private set
    var preview by mutableStateOf<PlanPreview?>(null); private set
    var busy by mutableStateOf(false); private set
    var toast: (String) -> Unit = {}

    suspend fun load() {
        try {
            info = api.billingInfo(profile)
            plans = if (info?.loggedIn == true) runCatching { api.planInfo(profile) }.getOrNull() else null
            error = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.why() }
        loaded = true
    }

    private fun refused(e: Exception) {
        if (e is BillingRefusal && e.needsStepUp) needsStepUp = true
        toast(e.why())
    }

    private fun act(call: suspend () -> Unit) {
        if (busy) return
        busy = true
        store.scope.launch {
            try { call(); load() } catch (e: CancellationException) { throw e } catch (e: Exception) { refused(e) } finally { busy = false }
        }
    }

    fun setAutoReload(on: Boolean, threshold: String?, reloadTo: String?) =
        act { api.setAutoReload(profile, on, threshold?.trim()?.ifEmpty { null }, reloadTo?.trim()?.ifEmpty { null }) }

    fun setKeepGoing(on: Boolean) = act { api.setKeepGoing(profile, on) }
    fun resume() = act { api.planResume(profile) }

    fun quote(t: PlanTier) {
        previewFor = t; preview = null
        store.scope.launch {
            try { preview = api.planPreview(profile, t.id) } catch (e: CancellationException) { throw e } catch (e: Exception) { previewFor = null; refused(e) }
        }
    }

    fun closeQuote() { previewFor = null; preview = null }

    fun confirmQuote() {
        val t = previewFor ?: return
        act { api.planChange(profile, t.id); previewFor = null; preview = null }
    }

    /** Buys [amount] (one of the presets). A retry of an unconfirmed charge keeps the same key, so money moves once. */
    fun buy(amount: String, done: (String) -> Unit) {
        val cur = charge
        if (cur?.state == "charging") return
        val key = if (cur != null && cur.amount == amount && cur.state == "unknown") cur.key else uuid()
        charge = Charge(amount, key, "charging")
        store.scope.launch {
            val id = try { api.buyCredits(profile, amount, key) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                charge = Charge(amount, key, if (e is BillingRefusal) "failed" else "unknown", e.why()); refused(e); return@launch
            }
            if (id == null) { charge = Charge(amount, key, "unknown"); return@launch }
            // ponytail: 2 s polls for 2 minutes; past that the charge reads "not confirmed yet" and a retry reuses the key.
            val until = nowMillis() + 120_000
            var state = "unknown"
            var note: String? = null
            while (nowMillis() < until) {
                delay(2_000)
                val st = runCatching { api.chargeStatus(profile, id) }.getOrNull() ?: continue
                if (st.status == "settled" || st.status == "failed") { state = st.status ?: state; note = st.reason; break }
            }
            charge = Charge(amount, key, state, note)
            done(state)
            load()
        }
    }

    /** The browser approval for billing changes; the link and code arrive as `billing.step_up.verification`. */
    fun stepUp(onGranted: () -> Unit) {
        if (steppingUp) return
        steppingUp = true
        stepUpCode = null
        val listen = store.scope.launch {
            val e = store.gateway.events.first { it.type == "billing.step_up.verification" }
            stepUpCode = (e.payload.str("verification_url").orEmpty()) to (e.payload.str("user_code").orEmpty())
        }
        store.scope.launch {
            try {
                val ok = withTimeoutOrNull(600_000) { api.billingStepUp(profile) } == true
                if (ok) { needsStepUp = false; onGranted() }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.why()) } finally {
                listen.cancel(); steppingUp = false; stepUpCode = null
            }
        }
    }
}
