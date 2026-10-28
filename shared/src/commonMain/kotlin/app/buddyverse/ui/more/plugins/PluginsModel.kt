package app.buddyverse.ui.more.plugins

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
import app.buddyverse.core.WireJson
import app.buddyverse.core.api.CatalogPlugin
import app.buddyverse.core.api.PluginField
import app.buddyverse.core.api.PluginRow
import app.buddyverse.core.api.SuggestedPlugin
import app.buddyverse.core.api.pluginCatalog
import app.buddyverse.core.api.pluginCatalogLookup
import app.buddyverse.core.api.pluginInstall
import app.buddyverse.core.api.pluginList
import app.buddyverse.core.api.pluginPresence
import app.buddyverse.core.api.pluginRemove
import app.buddyverse.core.api.pluginSettings
import app.buddyverse.core.api.pluginSuggestions
import app.buddyverse.core.api.pluginToggle
import app.buddyverse.core.api.pluginUpdate
import app.buddyverse.core.api.setEnvKey
import app.buddyverse.core.bool
import app.buddyverse.core.child
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.obj
import app.buddyverse.core.str
import app.buddyverse.design.Feedback
import app.buddyverse.design.Haptic
import app.buddyverse.design.NoFeedback
import app.buddyverse.design.Sound
import app.buddyverse.i18n.APP_NAME
import app.buddyverse.i18n.PluginsPageStrings
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.Toasts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

enum class PluginsTab { Installed, Browse }

// ---- words: the app is the user's own, so engine and vendor names never reach the screen (design §0)

private val clauseBreak = Regex("(?<=[.;,—])\\s+")
private val engineName = Regex("\\bHermes(?: Agent)?\\b")

/** Engine text without its branding: the engine's name becomes the app's, clauses about the vendor are dropped, `` `hermes x` `` → `` `x` ``. */
fun brandFree(text: String): String {
    if ("Nous" !in text && "Hermes" !in text && "`hermes " !in text) return text.trim()
    return text.lines().joinToString("\n") { line ->
        line.split(clauseBreak).filterNot { "Nous" in it }.joinToString(" ")
            .replace(engineName, APP_NAME).replace("`hermes ", "`").trim().trimEnd(',', ';', '—').trim()
    }.trim()
}

/** A plugin's display name: `agent-importers` → "Agent importers"; engine/vendor words dropped (`hermes-lang-he` → "Lang he"). */
fun pluginTitle(name: String): String {
    val words = name.split('-', '_', ' ').filter { it.isNotBlank() && it.lowercase() !in BRAND_WORDS }
    val t = words.joinToString(" ").ifBlank { name }
    return t.replaceFirstChar { it.uppercase() }
}
private val BRAND_WORDS = setOf("hermes", "nous")

fun Throwable.why(): String = brandFree(when (this) {
    is RestError -> detail
    is RpcError -> message ?: reason
    else -> message ?: toString()
})

// ---- settings form: every non-secret value is edited as text and typed on save (Desktop `plugin-settings-form.tsx`)

private val Pretty = Json { prettyPrint = true }

/** What a control shows before the user touches it. Secrets always start empty (their value never leaves the PC). */
fun initialText(f: PluginField): String {
    val v = f.value
    return when (f.type) {
        "boolean" -> if ((v as? JsonPrimitive)?.booleanOrNull == true) "true" else "false"
        "enum" -> (v as? JsonPrimitive)?.content ?: f.choices.firstOrNull().orEmpty()
        "json" -> v?.let { Pretty.encodeToString(JsonElement.serializer(), it) }.orEmpty()
        "secret" -> ""
        else -> (v as? JsonPrimitive)?.content ?: v?.toString().orEmpty()
    }
}

fun initialDraft(fields: List<PluginField>): Map<String, String> = fields.associate { it.key to initialText(it) }

enum class BadKind { Number, Choice, Json }

class BadField(val label: String, val kind: BadKind) : IllegalArgumentException(label)

/** The two payloads of a save: typed non-secret values that CHANGED, and non-blank secrets keyed by their `.env` name. */
data class SettingsChanges(val values: Map<String, JsonElement>, val secrets: Map<String, String>)

/** Throws [BadField] for the first value that doesn't fit its type. */
fun collectChanges(fields: List<PluginField>, draft: Map<String, String>): SettingsChanges {
    val values = LinkedHashMap<String, JsonElement>()
    val secrets = LinkedHashMap<String, String>()
    for (f in fields) {
        val raw = draft[f.key].orEmpty()
        if (f.type == "secret") {
            val env = f.env
            if (raw.isNotBlank() && env != null) secrets[env] = raw.trim()
            continue
        }
        if (raw == initialText(f)) continue
        values[f.key] = when (f.type) {
            "boolean" -> JsonPrimitive(raw == "true")
            "enum" -> if (raw in f.choices) JsonPrimitive(raw) else throw BadField(f.label, BadKind.Choice)
            "number" -> raw.trim().let { n -> n.toLongOrNull()?.let { JsonPrimitive(it) } ?: n.toDoubleOrNull()?.let { JsonPrimitive(it) } }
                ?: throw BadField(f.label, BadKind.Number)
            "json" -> runCatching { WireJson.parseToJsonElement(raw.ifBlank { "null" }) }.getOrNull()
                ?.takeIf { it is JsonObject || it is JsonArray } ?: throw BadField(f.label, BadKind.Json)
            else -> JsonPrimitive(raw)
        }
    }
    return SettingsChanges(values, secrets)
}

// ---- outcomes the page asks the user about

/** What came back from an install that the user should see: keys to fill in, notes, a restart. */
@Immutable
data class InstallResult(val title: String, val missingEnv: List<String>, val notes: List<String>, val note: String?)

/** An update the engine held back: a wider pin that needs consent, or a safety scan. [canGo] = it may be retried with the user's OK. */
@Immutable
data class UpdateAsk(val row: PluginRow, val scan: Boolean, val canGo: Boolean, val lines: List<String>, val accepted: Boolean)

private val SEVERITY = listOf("critical", "high", "medium", "low")

/** Scan findings, worst first, one line each, capped so the sheet stays readable. */
fun findingLines(findings: List<JsonObject>, more: (Int) -> String, cap: Int = 8): List<String> {
    val sorted = findings.sortedBy { f -> SEVERITY.indexOf(f.str("severity")).let { if (it < 0) SEVERITY.size else it } }
    val lines = sorted.take(cap).map { f ->
        "${f.str("severity").orEmpty().uppercase()}  ${f.str("file").orEmpty()}:${f.long("line") ?: 0}  ${brandFree(f.str("description")?.ifBlank { null } ?: f.str("category").orEmpty())}"
    }
    return if (sorted.size > cap) lines + more(sorted.size - cap) else lines
}

private val BARE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

private fun JsonObject?.strings(key: String): List<String> = list(key).mapNotNull { (it as? JsonPrimitive)?.content }

/**
 * State and intents of More ▸ Plugins for one bot ([profile]). Every call runs on the store's scope, so an install keeps going (and
 * toasts) if the page is left. Switches are optimistic; removal waits out an undo window first.
 */
@Stable
class PluginsModel(private val store: AppStore) {
    private val api get() = store.api
    // Bound each composition.
    var fb: Feedback = NoFeedback
    var toasts: Toasts? = null
    var t: PluginsPageStrings = PluginsPageStrings()
    var undoLabel = "Undo"

    var profile by mutableStateOf<String?>(null); private set
    var rows by mutableStateOf<List<PluginRow>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var unsupported by mutableStateOf(false); private set
    /** Rows waiting out their undo window. */
    var hidden by mutableStateOf<Set<String>>(emptySet()); private set
    /** Row ids and catalog names with a call in flight. */
    var busy by mutableStateOf<Set<String>>(emptySet()); private set
    /** Row ids with an update in flight: only these say "Updating…" (a switch flip stays quiet and never dims). */
    var updating by mutableStateOf<Set<String>>(emptySet()); private set
    var catalog by mutableStateOf<List<CatalogPlugin>?>(null); private set
    var catalogError by mutableStateOf<String?>(null); private set
    var suggested by mutableStateOf<List<SuggestedPlugin>>(emptyList()); private set
    var cue by mutableStateOf<BuddyCue?>(null); private set
    /** True for a moment after a failure: the buddy looks hurt. */
    var hurt by mutableStateOf(false); private set
    var ask by mutableStateOf<UpdateAsk?>(null)
    var result by mutableStateOf<InstallResult?>(null)

    val listed: List<PluginRow> get() = rows.filter { it.listed && it.id !in hidden }
    fun row(id: String): PluginRow? = rows.firstOrNull { it.id == id }
    /** A catalog name is installed when a row came from it or carries its name. */
    fun installed(name: String): Boolean = rows.any { it.catalogName == name || it.name == name }

    private var hurtJob: Job? = null
    private fun react(shot: OneShot) { cue = BuddyCue((cue?.serial ?: 0L) + 1, shot) }
    private fun ouch() {
        fb.sound(Sound.Bonk); fb.haptic(Haptic.Reject)
        hurt = true
        hurtJob?.cancel()
        hurtJob = store.scope.launch { delay(2_500); hurt = false }
    }
    private fun toast(text: String) = toasts?.show(ToastData(text))
    private fun fail(text: String) { ouch(); toast(text) }
    private fun patch(id: String, f: (PluginRow) -> PluginRow) { rows = rows.map { if (it.id == id) f(it) else it } }

    fun load(p: String) {
        if (p != profile) {
            profile = p; rows = emptyList(); loaded = false; error = null; unsupported = false; hidden = emptySet(); suggested = emptyList()
        }
        store.scope.launch { refresh(p) }
    }

    fun retry() { profile?.let(::load) }

    private suspend fun refresh(p: String) {
        try {
            val list = api.pluginList(p)
            if (p != profile) return
            rows = list; loaded = true; error = null; unsupported = false
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (p != profile) return
            loaded = true
            if (e is RpcError && e.unknownMethod) unsupported = true else error = e.why()
        }
    }

    /** The extra line a toggle / install / update result asks for (hermes-api.md §2.6 Plugins, Notes). */
    private fun noteOf(r: JsonObject): String? {
        val act = r.child("activation")
        val dead = act.child("live_now").list("mcp_servers").mapNotNull { it.obj }.filter { it.bool("connected") == false }.mapNotNull { it.str("name") }
        return when {
            r.bool("restart_required") == true || act.strings("missing_in").isNotEmpty() -> t.restartNeeded
            r.bool("cleared_memory_provider") == true -> t.memoryCleared
            dead.isNotEmpty() -> t.couldntStart(dead.joinToString(", "))
            act.child("deferred")?.values?.any { (it as? JsonArray)?.isNotEmpty() == true } == true -> t.nextChat
            else -> null
        }
    }

    fun toggle(r: PluginRow, on: Boolean) {
        val p = profile ?: return
        if (!r.toggleable || r.id in busy) return
        patch(r.id) { it.copy(status = if (on) "enabled" else "disabled") }
        busy = busy + r.id
        if (on) react(OneShot.Hop)
        store.scope.launch {
            try {
                val res = api.pluginToggle(p, r.key, on)
                if (p == profile) res.child("plugin")?.let(PluginRow::from)?.let { fresh -> patch(r.id) { fresh } }
                noteOf(res)?.let(::toast)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (p == profile) patch(r.id) { it.copy(status = r.status) }
                fail(e.why())
            } finally { busy = busy - r.id }
        }
    }

    /** Catalog install ([catalogName] = its name) or a link install (null). [onError] shows the reason inline; default is a toast. */
    fun install(identifier: String, catalogName: String?, title: String, onDone: () -> Unit = {}, onError: ((String) -> Unit)? = null) {
        val p = profile ?: return
        val id = catalogName ?: identifier
        if (id in busy) return
        busy = busy + id
        store.scope.launch {
            try {
                val res = api.pluginInstall(p, identifier, catalogName)
                refresh(p)
                val name = res.str("plugin_name")?.let(::pluginTitle) ?: title
                val missing = res.strings("missing_env")
                val notes = (res.strings("warnings") + res.strings("known_issues")).map(::brandFree)
                fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm); react(OneShot.Learned)
                if (missing.isNotEmpty() || notes.isNotEmpty()) result = InstallResult(name, missing, notes, noteOf(res))
                else toast(listOfNotNull(t.installedToast(name), noteOf(res)).joinToString(". "))
                onDone()
            } catch (e: TimeoutCancellationException) {
                // The engine may still finish: say so and look again a little later.
                toast(t.stillInstalling(title)); onDone()
                store.scope.launch { delay(45_000); if (p == profile) refresh(p) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                ouch()
                if (onError != null) onError(e.why()) else toast(e.why())
            } finally { busy = busy - id }
        }
    }

    /** What the user typed in "Install from a link": a link or `owner/repo` installs as is; a bare name is looked up in the catalog first. */
    fun installTyped(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        val p = profile ?: return
        val id = text.trim()
        if (!BARE_NAME.matches(id)) {
            install(id, null, pluginTitle(id.trimEnd('/').substringAfterLast('/').removeSuffix(".git")), onDone, onError)
            return
        }
        if (id in busy) return
        busy = busy + id
        store.scope.launch {
            val found = try { api.pluginCatalogLookup(p, id) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                busy = busy - id; ouch(); onError(e.why()); return@launch
            }
            busy = busy - id
            when (found.str("status")) {
                "found" -> found.child("entry").str("name").orEmpty().ifBlank { id }.let { n -> install(n, n, pluginTitle(n), onDone, onError) }
                "unavailable" -> { ouch(); onError(t.catalogUnavailable) }
                else -> { ouch(); onError(t.unknownName) }
            }
        }
    }

    /** Update, asking before each override the engine wants: a wider pin ([accept]) or code the scan flagged as caution ([force]). */
    fun update(r: PluginRow, accept: Boolean = false, force: Boolean = false) {
        val p = profile ?: return
        if (r.id in busy) return
        busy = busy + r.id
        updating = updating + r.id
        store.scope.launch {
            // A held-back update changed nothing: ask only once the row is free again, so the user's "yes" can't hit the busy guard.
            var held: UpdateAsk? = null
            try {
                val res = api.pluginUpdate(p, r.name, accept, force)
                val title = pluginTitle(r.name)
                when {
                    res.bool("consent_required") == true && !accept ->
                        held = UpdateAsk(r, scan = false, canGo = true, lines = res.strings("delta_lines").map(::brandFree), accepted = false)
                    res.bool("scan_blocked") == true -> {
                        val caution = res.str("scan_verdict") == "caution"
                        if (!caution) ouch()
                        held = UpdateAsk(r, scan = true, canGo = caution && !force,
                            lines = findingLines(res.list("scan_findings").mapNotNull { it.obj }, t.andMore), accepted = accept)
                    }
                    res.bool("ok") == false -> throw IllegalStateException(res.str("error") ?: t.update)
                    res.bool("unchanged") == true -> toast(t.upToDate(title))
                    else -> {
                        fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm); react(OneShot.Done)
                        toast(listOfNotNull(t.updated(title), noteOf(res)).joinToString(". "))
                    }
                }
                if (held == null) refresh(p)
            } catch (e: TimeoutCancellationException) {
                toast(t.stillInstalling(pluginTitle(r.name)))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e.why()) }
            finally { busy = busy - r.id; updating = updating - r.id }
            held?.let { ask = it }
        }
    }

    /** The user said yes to [a]: retry with the consent or the force it asked for. */
    fun go(a: UpdateAsk) {
        ask = null
        if (a.scan) update(a.row, accept = a.accepted, force = true) else update(a.row, accept = true)
    }

    /** Optimistic removal with an undo window; the engine is only asked once the toast is gone. */
    fun remove(r: PluginRow) {
        val p = profile ?: return
        hidden = hidden + r.id
        fb.haptic(Haptic.Reject)
        val title = pluginTitle(r.name)
        val job = store.scope.launch {
            delay(4_500)
            try {
                val res = api.pluginRemove(p, r.name)
                if (res.bool("cleared_memory_provider") == true) toast(t.memoryCleared)
                refresh(p)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e.why()) }
            hidden = hidden - r.id
        }
        toasts?.show(ToastData(t.removed(title), undoLabel, onAction = { job.cancel(); hidden = hidden - r.id }))
    }

    /** Secrets go to `.env` first, then the typed values through `plugins.manage settings`. [done] gets null or the reason. */
    fun saveSettings(r: PluginRow, draft: Map<String, String>, done: (String?) -> Unit) {
        val p = profile ?: return
        val changes = try { collectChanges(r.settings, draft) } catch (e: BadField) {
            done(when (e.kind) { BadKind.Number -> t.needNumber(e.label); BadKind.Choice -> t.needChoice(e.label); BadKind.Json -> t.needJson(e.label) })
            return
        }
        if (changes.values.isEmpty() && changes.secrets.isEmpty()) { done(null); return }
        busy = busy + r.id
        store.scope.launch {
            try {
                for ((env, v) in changes.secrets) api.setEnvKey(p, env, v)
                // A secret-only save still sends the (empty) settings call: that is what tells the plugin its settings changed.
                val res = api.pluginSettings(p, r.key, JsonObject(changes.values))
                val fresh = res.child("plugin")?.let(PluginRow::from)
                if (p == profile) { if (fresh != null) patch(r.id) { fresh } else refresh(p) }
                fb.haptic(Haptic.Confirm); react(OneShot.Nod); toast(t.saved)
                done(null)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { ouch(); done(e.why()) }
            finally { busy = busy - r.id }
        }
    }

    /** Keys an install reported missing, written to the bot's `.env`. Blank values are skipped. */
    fun saveKeys(values: Map<String, String>, done: (String?) -> Unit) {
        val p = profile ?: return
        store.scope.launch {
            try {
                for ((k, v) in values) if (v.isNotBlank()) api.setEnvKey(p, k, v.trim())
                fb.haptic(Haptic.Confirm); react(OneShot.Nod); toast(t.saved)
                done(null)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { ouch(); done(e.why()) }
        }
    }

    fun loadCatalog() {
        val p = profile ?: return
        catalogError = null
        store.scope.launch {
            if (catalog == null) {
                try { catalog = pluginCatalog() } catch (e: CancellationException) { throw e } catch (e: Exception) { catalogError = e.why() }
            }
            if (suggested.isEmpty()) runCatching { api.pluginSuggestions(p) }.getOrNull()?.let { if (p == profile) suggested = it }
        }
    }

    /** What this PC lacks to run [name] ("needs NVIDIA App"), or null. */
    suspend fun presence(name: String): String? {
        val p = profile ?: return null
        return try { api.pluginPresence(p, listOf(name))[name]?.str("sentence")?.ifBlank { null }?.let(::brandFree) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }
}
