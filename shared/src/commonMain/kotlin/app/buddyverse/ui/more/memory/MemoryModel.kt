package app.buddyverse.ui.more.memory

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.AppStore
import app.buddyverse.core.ChatTarget
import app.buddyverse.core.RestError
import app.buddyverse.core.api.memoryConfig
import app.buddyverse.core.api.memoryOauthStart
import app.buddyverse.core.api.memoryOauthStatus
import app.buddyverse.core.api.memoryProviderConfig
import app.buddyverse.core.api.memoryStatus
import app.buddyverse.core.api.resetMemory
import app.buddyverse.core.api.saveMemoryProviderConfig
import app.buddyverse.core.api.setMemoryConfig
import app.buddyverse.core.api.setMemoryProvider
import app.buddyverse.core.api.setupMemoryProvider
import app.buddyverse.core.bool
import app.buddyverse.core.child
import app.buddyverse.core.double
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.obj
import app.buddyverse.core.str
import app.buddyverse.design.Feedback
import app.buddyverse.design.Haptic
import app.buddyverse.design.NoFeedback
import app.buddyverse.design.Sound
import app.buddyverse.i18n.EnglishStrings
import app.buddyverse.i18n.MemoryPageStrings
import app.buddyverse.i18n.Strings
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.Toasts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Memory sources (hermes-api.md §2.5): USER.md = what the bot knows about you, MEMORY.md = its own notes. */
const val ABOUT = "profile"
const val NOTES = "memory"

/** Entries in one file are joined by `"\n§\n"`; the engine's budget counts them. */
private const val DELIM = 3
/** The undo toast stays 4 s (AppRoot ToastHost); the delete waits it out. */
private const val UNDO_MS = 4_200L

enum class MemoryTab { About, Notes, Learned, Providers }

/** One memory entry; [id] = `memory:<source>:<index>:<fingerprint>`. [text] is the graph's card text (≤1200 chars). */
@Immutable
data class Fact(val id: String, val source: String, val text: String, val time: Double)

/** A skill the bot made itself (a learning-graph node that isn't memory); [id] = the skill name. */
@Immutable
data class LearnedSkill(val id: String, val label: String, val category: String, val uses: Long, val pinned: Boolean, val time: Double)

@Immutable
data class Dep(val name: String, val install: String)

/** An external memory plugin (`GET /api/memory` row). [status]: ready, needs_config, unavailable, missing. */
@Immutable
data class Provider(val name: String, val description: String, val status: String, val depsMissing: Boolean, val deps: List<Dep>, val env: List<String>)

/** The `memory.*` switches and budgets. */
@Immutable
data class Builtin(val memoryOn: Boolean, val userOn: Boolean, val askFirst: Boolean, val memoryLimit: Int, val userLimit: Int) {
    companion object {
        const val MEMORY_ON = "memory_enabled"
        const val USER_ON = "user_profile_enabled"
        const val ASK_FIRST = "write_approval"
        val Default = Builtin(memoryOn = true, userOn = true, askFirst = false, memoryLimit = 2200, userLimit = 1375)
        fun from(o: JsonObject): Builtin = Builtin(
            memoryOn = o.bool(MEMORY_ON) != false, userOn = o.bool(USER_ON) != false, askFirst = o.bool(ASK_FIRST) == true,
            memoryLimit = (o.long("memory_char_limit") ?: 2200L).toInt(), userLimit = (o.long("user_char_limit") ?: 1375L).toInt(),
        )
    }
}

/** One provider config field (kinds: text, secret, select, boolean/bool, integer, number, json). Secrets never carry their value. */
@Immutable
data class ProviderField(
    val key: String, val label: String, val kind: String, val description: String, val placeholder: String,
    val required: Boolean, val value: String, val isSet: Boolean, val options: List<Pair<String, String>>,
) {
    val secret: Boolean get() = kind == "secret"
    val toggle: Boolean get() = kind == "boolean" || kind == "bool"
    val number: Boolean get() = kind == "integer" || kind == "number"

    companion object {
        fun from(o: JsonObject?): ProviderField? {
            val key = o.str("key") ?: return null
            val raw = o?.get("value")
            return ProviderField(
                key = key, label = o.str("label") ?: key, kind = o.str("kind") ?: "text", description = o.str("description").orEmpty(),
                placeholder = o.str("placeholder").orEmpty(), required = o.bool("required") == true,
                value = o.str("value") ?: if (raw == null || raw is JsonNull) "" else raw.toString(), isSet = o.bool("is_set") == true,
                options = o.list("options").mapNotNull { el ->
                    el.obj?.let { op -> op.str("value")?.let { v -> v to (op.str("label") ?: v) } } ?: (el as? JsonPrimitive)?.content?.let { it to it }
                },
            )
        }
    }
}

/** What the provider sheet edits. [declared] = the desktop's declared schema (save only); else the plugin schema (save = also use). */
@Immutable
data class ProviderForm(val label: String, val fields: List<ProviderField>, val declared: Boolean, val oauth: JsonObject?)

/** What a sheet shows: a fact or a learned skill, read-only or in the editor. */
@Immutable
data class Open(val id: String, val preview: String, val skill: Boolean, val editing: Boolean = false)

/** A destructive step waiting for a yes. */
class Confirm(val title: String, val body: String, val action: String, val run: () -> Unit)

/** Learning graph (`GET /api/learning/graph`) → memory entries (newest first) and learned skills (newest first). */
fun parseGraph(g: JsonObject?): Pair<List<Fact>, List<LearnedSkill>> {
    val cards = g.list("memory").mapNotNull { it.obj }
    val facts = ArrayList<Fact>()
    val skills = ArrayList<LearnedSkill>()
    for (n in g.list("nodes").mapNotNull { it.obj }) {
        val id = n.str("id") ?: continue
        if (n.str("kind") == "memory" || id.startsWith("memory:")) {
            val parts = id.split(':')
            val fp = parts.getOrNull(3)
            // index = position in the combined card list (MEMORY.md first); the fingerprint confirms it, or finds it if the list shifted.
            val card = parts.getOrNull(2)?.toIntOrNull()?.let { cards.getOrNull(it) }?.takeIf { c -> fp == null || c.str("fingerprint").let { it == null || it == fp } }
                ?: cards.firstOrNull { fp != null && it.str("fingerprint") == fp }
            val text = card.str("body") ?: n.str("label") ?: continue
            facts += Fact(id, n.str("memorySource") ?: parts.getOrNull(1).orEmpty(), text, n.double("timestamp") ?: card.double("timestamp") ?: 0.0)
        } else {
            skills += LearnedSkill(id, n.str("label") ?: id, n.str("category").orEmpty(), n.long("useCount") ?: 0, n.bool("pinned") == true, n.double("timestamp") ?: 0.0)
        }
    }
    return facts.asReversed().toList() to skills.sortedByDescending { it.time }
}

fun parseProviders(st: JsonObject?): List<Provider> = st.list("providers").mapNotNull { el ->
    val o = el.obj ?: return@mapNotNull null
    val setup = o.child("setup")
    Provider(
        name = o.str("name") ?: return@mapNotNull null, description = o.str("description").orEmpty(), status = o.str("status").orEmpty(),
        depsMissing = setup.bool("dependencies_installed") == false,
        deps = setup.list("pip_dependencies").mapNotNull { (it as? JsonPrimitive)?.content?.let { n -> Dep(n, "") } } +
            setup.list("external_dependencies").mapNotNull { d -> d.obj?.let { Dep(it.str("name").orEmpty(), it.str("install").orEmpty()) } },
        env = setup.list("required_env").mapNotNull { (it as? JsonPrimitive)?.content },
    )
}

/** Server text without the engine's vendor names (the app is the user's own). */
fun unbrand(text: String): String = brandWords.replace(text, "engine")
private val brandWords = Regex("""(?i)\b(hermes[- ]agent|hermes|nous ?research|nous)\b""")

/**
 * State and intents of More ▸ Memory for one bot ([profile]). Loads run in the page scope [ui]; writes run in the app scope so they
 * finish (and toast) even if the page is left. Forgetting is optimistic with an undo: the delete waits out the toast.
 */
@Stable
class MemoryModel(private val store: AppStore, private val ui: CoroutineScope) {
    // Bound each composition (language, toasts, the bot's look).
    var fb: Feedback = NoFeedback
    var toasts: Toasts? = null
    var t: MemoryPageStrings = MemoryPageStrings()
    var s: Strings = EnglishStrings
    var spec: BuddySpec? = null

    var profile by mutableStateOf(""); private set
    var tab by mutableStateOf(MemoryTab.About)
    var query by mutableStateOf("")

    private var allFacts by mutableStateOf<List<Fact>>(emptyList())
    private var allLearned by mutableStateOf<List<LearnedSkill>>(emptyList())
    var loaded by mutableStateOf(false); private set
    var loadError by mutableStateOf<String?>(null); private set
    /** Entries waiting out their undo, by [hideKey]. */
    private val hidden = mutableStateListOf<String>()

    /** A memory entry by source + fingerprint (its index shifts when an earlier one goes); a skill by name. */
    private fun hideKey(p: String, id: String): String =
        id.split(':').let { if (it.size >= 4 && it[0] == "memory") "$p|${it[1]}:${it[3]}" else "$p|$id" }

    /** Null until `memory.*` config loads (switches stay hidden until then). */
    var builtin by mutableStateOf<Builtin?>(null); private set
    var providers by mutableStateOf<List<Provider>>(emptyList()); private set
    var active by mutableStateOf(""); private set
    var providersLoaded by mutableStateOf(false); private set
    var providersError by mutableStateOf<String?>(null); private set

    var cue by mutableStateOf<BuddyCue?>(null); private set
    private var cues = 0L
    private var busy by mutableIntStateOf(0)
    private var failing by mutableStateOf(false)
    private var failJob: Job? = null
    private var changedJob: Job? = null

    /** The bot's buddy here: red after a failure, at work while a write runs, thinking while loading. */
    val mood: Mood get() = when {
        failing -> Mood.Error
        busy > 0 -> Mood.Working
        !loaded -> Mood.Thinking
        else -> Mood.Idle
    }

    fun facts(source: String): List<Fact> = allFacts.filter { it.source == source && hideKey(profile, it.id) !in hidden }
    val learned: List<LearnedSkill> get() = allLearned.filter { hideKey(profile, it.id) !in hidden }

    /** Characters the file holds. ponytail: card text stops at 1200 chars, so a longer entry undercounts; exact needs one node read each. */
    fun used(source: String): Int = facts(source).let { l -> l.sumOf { it.text.length } + DELIM * (l.size - 1).coerceAtLeast(0) }
    fun limit(source: String): Int = (builtin ?: Builtin.Default).let { if (source == ABOUT) it.userLimit else it.memoryLimit }

    fun select(p: String) {
        if (p == profile) return
        profile = p
        allFacts = emptyList(); allLearned = emptyList(); loaded = false; loadError = null
        builtin = null; providers = emptyList(); active = ""; providersLoaded = false; providersError = null
        query = ""
        play(OneShot.Hop)
        refresh()
    }

    fun play(shot: OneShot) { cue = BuddyCue(++cues, shot) }

    fun refresh() {
        val p = profile
        ui.launch {
            try {
                val (f, l) = parseGraph(store.api.learningGraph(p))
                if (p != profile) return@launch
                val first = !loaded
                allFacts = f; allLearned = l; loadError = null; loaded = true
                if (first && (f.isNotEmpty() || l.isNotEmpty())) play(OneShot.Learned)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (p == profile) { loadError = e.text(); loaded = true; flashError() }
            }
        }
        ui.launch {
            try { store.api.memoryConfig(p)?.let { if (p == profile) builtin = Builtin.from(it) } } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
        ui.launch {
            try {
                val st = store.api.memoryStatus(p)
                if (p == profile) { providers = parseProviders(st); active = st.str("active").orEmpty(); providersError = null; providersLoaded = true }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (p == profile) { providersError = e.text(); providersLoaded = true }
            }
        }
    }

    /** A turn's background review or a skill folder change may have written memory: refetch, debounced. */
    fun onChanged() {
        changedJob?.cancel()
        changedJob = ui.launch { delay(800); refresh() }
    }

    // ------------------------------------------------------------------ entries

    /** Full text (the graph's card text stops at 1200 chars; a skill's is its whole SKILL.md). Null if it can't be read. */
    suspend fun content(id: String): String? = try { store.api.learningNode(id, profile).str("content") } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    /** Null on success, else what to show under the editor (empty, stale id, over budget…). */
    suspend fun save(id: String, content: String): String? {
        val p = profile
        busy++
        return try {
            val r = store.api.editMemory(id, content.trim(), p).obj
            if (r.bool("ok") == false) unbrand(r.str("message") ?: s.error)
            else { done(t.saved, OneShot.Nod); if (p == profile) refresh(); null }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { flashError(); e.text() } finally { busy-- }
    }

    /** Forget an entry (or archive a learned skill): gone at once, deleted after the undo toast unless undone. */
    fun forget(id: String, skill: Boolean) {
        val p = profile
        val key = hideKey(p, id)
        hidden += key
        fb.haptic(Haptic.Reject)
        play(OneShot.Dizzy)
        val job = store.scope.launch {
            delay(UNDO_MS)
            try {
                val r = store.api.deleteMemory(id, p).obj
                if (r.bool("ok") == false) throw IllegalStateException(r.str("message") ?: s.error)
                allFacts = allFacts.filterNot { it.id == id }; allLearned = allLearned.filterNot { it.id == id }
                if (p == profile) refresh()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                fail(if (skill && e is RestError && "pinned" in e.detail) t.pinned else e.text())
            } finally { hidden -= key }
        }
        toasts?.show(ToastData(if (skill) t.archived else t.forgotten, s.undo, { job.cancel(); hidden -= key; play(OneShot.Return) }, spec, OneShot.Nod))
    }

    /** Erase a whole file (destructive, confirmed by the caller). */
    fun forgetAll(source: String) {
        val p = profile
        busy++
        store.scope.launch {
            try {
                store.api.resetMemory(if (source == ABOUT) "user" else "memory", p)
                if (p == profile) { allFacts = allFacts.filterNot { it.source == source }; refresh() }
                done(t.erased, OneShot.Dizzy)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e.text()) } finally { busy-- }
        }
    }

    /** "Teach": the bot's own memory tool is the only way in, so ask it in its Bot Chat (the caller opens the chat). */
    fun teach(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        store.chat(ChatTarget(profile)).send(t.teachPrompt(clean))
        fb.haptic(Haptic.Confirm)
        fb.sound(Sound.Pop)
    }

    // ------------------------------------------------------------------ switches

    /** One `memory.*` switch ([Builtin.MEMORY_ON], [Builtin.USER_ON], [Builtin.ASK_FIRST]), optimistic. */
    fun setSwitch(key: String, on: Boolean) {
        val before = builtin ?: return
        val p = profile
        builtin = when (key) {
            Builtin.MEMORY_ON -> before.copy(memoryOn = on)
            Builtin.USER_ON -> before.copy(userOn = on)
            else -> before.copy(askFirst = on)
        }
        play(if (on) OneShot.Learned else OneShot.Nod)
        store.scope.launch {
            try { store.api.setMemoryConfig(mapOf(key to on), p) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (p == profile) builtin = before
                fail(e.text())
            }
        }
    }

    // ------------------------------------------------------------------ providers

    /** Select the active provider (`""` = built-in only), optimistic. 400 when it isn't ready. */
    fun use(name: String) {
        val p = profile
        val before = active
        if (name == before) return
        active = name
        busy++
        store.scope.launch {
            try { store.api.setMemoryProvider(name, p); done(t.newChats, OneShot.Learned) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (p == profile) active = before
                fail(e.text())
            } finally { busy-- }
        }
    }

    /** The provider's fields: the declared schema when it has one, else the plugin's own; plus its OAuth state (null = none). */
    suspend fun form(name: String): ProviderForm {
        val p = profile
        val declared = try { store.api.memoryProviderConfig(name, p, declared = true) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val useDeclared = declared.list("fields").isNotEmpty()
        val cfg = if (useDeclared) declared else store.api.memoryProviderConfig(name, p, declared = false)
        return ProviderForm(cfg.str("label") ?: name, cfg.list("fields").mapNotNull { ProviderField.from(it.obj) }, useDeclared, oauth(name))
    }

    suspend fun oauth(name: String): JsonObject? =
        try { store.api.memoryOauthStatus(name, profile) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    suspend fun oauthStart(name: String): String? = guarded { store.api.memoryOauthStart(name, profile) }

    /** Save the values and make it the active provider. Null on success. */
    suspend fun saveProvider(name: String, form: ProviderForm, values: Map<String, String>): String? {
        val p = profile
        return guarded {
            store.api.saveMemoryProviderConfig(name, values, p, form.declared)
            if (form.declared) store.api.setMemoryProvider(name, p)
            if (p == profile) active = name
            done(t.newChats, OneShot.Learned)
            if (p == profile) refresh()
        }
    }

    /** Save the values and install the provider's dependencies (can take minutes). Null on success, else the failed steps. */
    suspend fun install(name: String, values: Map<String, String>): String? {
        val p = profile
        var failed: String? = null
        val err = guarded {
            val r = store.api.setupMemoryProvider(name, values, p)
            if (r.bool("ok") == false) failed = r.list("results").mapNotNull { it.obj }.filter { it.str("status") == "failed" }
                .joinToString("\n") { "${it.str("name").orEmpty()}: ${it.str("error") ?: it.str("status").orEmpty()}" }.ifBlank { t.installFailed }
            if (p == profile) refresh()
        }
        return err ?: failed?.let(::unbrand)?.also { flashError() }
    }

    // ------------------------------------------------------------------ feedback

    private suspend fun guarded(block: suspend () -> Unit): String? {
        busy++
        return try { block(); null } catch (e: CancellationException) { throw e } catch (e: Exception) { flashError(); e.text() } finally { busy-- }
    }

    private fun done(text: String, shot: OneShot) {
        play(shot)
        fb.haptic(Haptic.Confirm)
        fb.sound(Sound.Bloop)
        toasts?.show(ToastData(text, buddy = spec, shot = shot))
    }

    private fun fail(text: String) {
        flashError()
        toasts?.show(ToastData(text, buddy = spec))
    }

    /** Red buddy for 2.4 s, the Reject haptic and the bonk. */
    private fun flashError() {
        fb.haptic(Haptic.Reject)
        fb.sound(Sound.Bonk)
        failing = true
        failJob?.cancel()
        failJob = ui.launch { delay(2_400); failing = false }
    }

    /** A failure as the user reads it (the server's detail, unbranded). */
    fun describe(e: Throwable): String = e.text()

    private fun Throwable.text(): String = unbrand(((this as? RestError)?.detail ?: message).orEmpty().ifBlank { s.error }.take(240))
}
