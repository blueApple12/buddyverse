package app.buddyverse.ui.more.skills

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.AppStore
import app.buddyverse.core.RestError
import app.buddyverse.core.api.archiveSkill
import app.buddyverse.core.api.awaitAction
import app.buddyverse.core.api.createSkill
import app.buddyverse.core.api.curator
import app.buddyverse.core.api.curatorPause
import app.buddyverse.core.api.curatorRun
import app.buddyverse.core.api.forOtherBots
import app.buddyverse.core.api.hubInstall
import app.buddyverse.core.api.hubOfficial
import app.buddyverse.core.api.hubPreview
import app.buddyverse.core.api.hubScan
import app.buddyverse.core.api.hubSearch
import app.buddyverse.core.api.hubSources
import app.buddyverse.core.api.hubUninstall
import app.buddyverse.core.api.hubUpdateAll
import app.buddyverse.core.api.saveSkillContent
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
import app.buddyverse.i18n.EnglishStrings
import app.buddyverse.i18n.SkillsPageStrings
import app.buddyverse.i18n.Strings
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.Toasts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One installed skill (`GET /api/skills` row). */
@Immutable
data class Skill(val name: String, val description: String, val category: String, val enabled: Boolean, val usage: Long, val provenance: String) {
    /** Learned/local and hub skills are the user's to edit; bundled ones come back on update, external ones live elsewhere. */
    val editable: Boolean get() = provenance == "agent" || provenance == "hub"

    companion object {
        fun from(o: JsonObject): Skill? = Skill(
            name = o.str("name") ?: return null, description = o.str("description").orEmpty(), category = o.str("category").orEmpty(),
            enabled = o.bool("enabled") != false, usage = o.long("usage") ?: 0, provenance = o.str("provenance").orEmpty(),
        )
    }
}

/** One hub catalog / search row (`HubSkill`). */
@Immutable
data class HubSkill(
    val name: String, val description: String, val source: String, val identifier: String, val trust: String,
    val repo: String, val tags: List<String>, val installed: Boolean,
) {
    companion object {
        fun from(o: JsonObject, installedIds: Set<String>): HubSkill? {
            val id = o.str("identifier") ?: return null
            return HubSkill(
                name = o.str("name") ?: id.substringAfterLast('/'), description = o.str("description").orEmpty(), source = o.str("source").orEmpty(),
                identifier = id, trust = o.str("trust_level").orEmpty(), repo = o.str("repo").orEmpty(),
                tags = o.list("tags").mapNotNull { (it as? JsonPrimitive)?.content }, installed = o.bool("installed") == true || id in installedIds,
            )
        }
    }
}

enum class SkillsTab { Installed, Hub, Upkeep }

/** SKILL.md without its `---` frontmatter, for reading. */
fun skillBody(md: String): String {
    val t = md.trimStart { it.isWhitespace() || it.code == 0xFEFF }
    if (!t.startsWith("---")) return t.trim()
    val close = t.indexOf("\n---", 3)
    if (close < 0) return t.trim()
    val after = t.indexOf('\n', close + 4)
    return if (after < 0) "" else t.substring(after + 1).trim()
}

/** A new SKILL.md: frontmatter (name, quoted one-line description) then the instructions. */
fun skillMarkdown(name: String, description: String, body: String): String {
    val d = description.replace(Regex("\\s+"), " ").trim().replace("\\", "\\\\").replace("\"", "\\\"")
    return "---\nname: $name\ndescription: \"$d\"\n---\n\n${body.trim()}\n"
}

/** Server text (logs, errors, scan notes) without the engine's vendor names: the app is the user's own (design: no vendor branding). */
fun unbrand(text: String): String = brandWords.replace(text, "engine")
private val brandWords = Regex("""(?i)\b(hermes[- ]agent|hermes|nous ?research|nous)\b""")

/** Skill names as the user types them: lowercase, spaces become dashes, only a-z 0-9 . _ -. */
fun cleanSkillName(raw: String): String = raw.lowercase().replace(' ', '-').filter { it in 'a'..'z' || it in '0'..'9' || it in "-_." }

/**
 * State and intents of More ▸ Skills. Skills are global: one shared pool on disk, read through the default bot ([profile]); hub
 * installs go through it too (they land in its skills dir, the shared pool). Switches are per-profile config, so a toggle is written
 * to every bot. Loads run in the page scope [ui]; writes and background actions run in the
 * app scope so they finish (and toast) even if the page is left. Every write is optimistic where the server allows an undo-free guess.
 */
@Stable
class SkillsModel(private val store: AppStore, private val ui: CoroutineScope) {
    // Bound each composition (language, toasts, the bot's look).
    var fb: Feedback = NoFeedback
    var toasts: Toasts? = null
    var t: SkillsPageStrings = SkillsPageStrings()
    var s: Strings = EnglishStrings
    var spec: BuddySpec? = null

    var profile by mutableStateOf(""); private set
    var tab by mutableStateOf(SkillsTab.Installed); private set
    var query by mutableStateOf("")

    var skills by mutableStateOf<List<Skill>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    var loadError by mutableStateOf<String?>(null); private set

    var hubQuery by mutableStateOf(""); private set
    var hubItems by mutableStateOf<List<HubSkill>>(emptyList()); private set
    var hubLoading by mutableStateOf(false); private set
    var hubError by mutableStateOf<String?>(null); private set
    /** Search source filter: `all` or one searchable hub source id (`GET /api/skills/hub/sources`). */
    var hubSource by mutableStateOf("all"); private set
    var hubSources by mutableStateOf<List<String>>(emptyList()); private set
    private var sourcesFor = ""
    private var official: List<HubSkill> = emptyList()
    private var officialFor = ""
    private var hubJob: Job? = null

    var curator by mutableStateOf<JsonObject?>(null); private set
    var curatorError by mutableStateOf<String?>(null); private set

    /** Background actions in flight, `"<profile>|<key>"` → last output lines. Keys: a hub identifier, `rm:<name>`, `update`, `curator`. */
    val running = mutableStateMapOf<String, List<String>>()
    var cue by mutableStateOf<BuddyCue?>(null); private set
    private var cues = 0L
    private var failing by mutableStateOf(false)
    private var failJob: Job? = null
    private var changedJob: Job? = null

    /** The bot's buddy on this page: red after a failure, at work while anything runs, thinking while loading. */
    val mood: Mood get() = when {
        failing -> Mood.Error
        running.keys.any { it.startsWith("$profile|") } -> Mood.Working
        !loaded || hubLoading -> Mood.Thinking
        else -> Mood.Idle
    }

    fun busy(key: String): Boolean = "$profile|$key" in running
    fun lines(key: String): List<String> = running["$profile|$key"].orEmpty()

    fun select(p: String) {
        if (p == profile) return
        profile = p
        skills = emptyList(); loaded = false; loadError = null
        hubJob?.cancel(); hubLoading = false; hubItems = emptyList(); hubError = null
        hubSource = "all"; hubSources = emptyList()
        curator = null; curatorError = null
        play(OneShot.Hop)
        refreshSkills()
        show(tab)
    }

    /** Shows [next]; the hub and curator load the first time their tab is opened for this bot. */
    fun show(next: SkillsTab) {
        tab = next
        if (next == SkillsTab.Hub && hubItems.isEmpty() && !hubLoading) setHubQuery(hubQuery)
        if (next == SkillsTab.Hub && sourcesFor != profile) loadSources()
        if (next == SkillsTab.Upkeep && curator == null) loadCurator()
    }

    /** Plays a one-shot on the bot's buddy. */
    fun play(shot: OneShot) { cue = BuddyCue(++cues, shot) }

    // ------------------------------------------------------------------ installed

    fun refreshSkills() {
        val p = profile
        ui.launch {
            try {
                val list = store.api.skills(p).mapNotNull(Skill::from).sortedWith(compareBy<Skill>({ it.category.ifEmpty { "~" } }, { it.name }))
                if (p == profile) { skills = list; loadError = null; loaded = true }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (p == profile) { loadError = e.text(); loaded = true }
            }
        }
    }

    /** `skills.changed`: a skill folder moved on the PC (also after our own writes). */
    fun onSkillsChanged() {
        changedJob?.cancel()
        changedJob = ui.launch { delay(600); refreshSkills() }
    }

    fun toggle(sk: Skill, on: Boolean) {
        val p = profile
        skills = skills.map { if (it.name == sk.name) it.copy(enabled = on) else it }
        play(if (on) OneShot.Learned else OneShot.Nod)
        store.scope.launch {
            try { store.api.toggleSkill(sk.name, on, p) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (p == profile) skills = skills.map { if (it.name == sk.name) it.copy(enabled = !on) else it }
                fail(e.text())
                return@launch
            }
            val missed = store.forOtherBots(p) { store.api.toggleSkill(sk.name, on, it) }
            if (missed.isNotEmpty()) fail(t.notOnBots(missed.joinToString(", ")))
        }
    }

    suspend fun content(name: String): String = store.api.skillContent(name, profile).str("content").orEmpty()

    /** Null on success, else what to show under the form. */
    suspend fun create(name: String, description: String, category: String, body: String): String? = try {
        val r = store.api.createSkill(name, skillMarkdown(name, description, body), category.trim().ifEmpty { null }, profile)
        if (r.bool("success") == false) r.str("error") ?: r.str("message") ?: s.error
        else { refreshSkills(); done(t.created(name), OneShot.Learned); null }
    } catch (e: CancellationException) { throw e } catch (e: Exception) { e.text() }

    suspend fun save(name: String, content: String): String? = try {
        val r = store.api.saveSkillContent(name, content, profile)
        if (r.bool("success") == false) r.str("error") ?: r.str("message") ?: s.error
        else { refreshSkills(); done(t.saved, OneShot.Nod); null }
    } catch (e: CancellationException) { throw e } catch (e: Exception) { e.text() }

    /** Delete = archive (agent skills). Removed at once; a refusal (e.g. pinned) brings it back. */
    fun archive(sk: Skill) {
        val p = profile
        skills = skills.filterNot { it.name == sk.name }
        store.scope.launch {
            try {
                store.api.archiveSkill(sk.name, p)
                done(t.deletedName(sk.name), OneShot.Wave)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (p == profile) refreshSkills()
                fail(if (e is RestError && "pinned" in e.detail) t.pinned else e.text())
            }
        }
    }

    // ------------------------------------------------------------------ hub

    @kotlin.jvm.JvmName("pickHubQuery")
    fun setHubQuery(q: String) {
        hubQuery = q
        val src = hubSource
        hubJob?.cancel()
        hubJob = ui.launch {
            if (q.isNotBlank()) delay(450)
            loadHub(profile, q.trim(), src)
        }
    }

    fun setSource(id: String) {
        if (id == hubSource) return
        hubSource = id
        setHubQuery(hubQuery)
    }

    /** Searchable, reachable sources for the filter chips; the filter just stays hidden when this fails. */
    private fun loadSources() {
        val p = profile
        sourcesFor = p
        ui.launch {
            try {
                val ids = store.api.hubSources(p).list("sources").mapNotNull { it.obj }
                    .filter { it.bool("searchable") == true && it.bool("available") != false }.mapNotNull { it.str("id") }
                if (p == profile) hubSources = ids
            } catch (e: CancellationException) { throw e } catch (_: Exception) { if (p == profile) sourcesFor = "" }
        }
    }

    private suspend fun loadHub(p: String, q: String, source: String) {
        hubLoading = true
        try {
            val items = if (q.isEmpty()) {
                if (officialFor != p || official.isEmpty()) { official = store.api.hubOfficial(p).mapNotNull { HubSkill.from(it, emptySet()) }; officialFor = p }
                official
            } else {
                val r = withTimeoutOrNull(45_000) { store.api.hubSearch(q, p, source) }
                val ids = r.child("installed")?.keys.orEmpty()
                r.list("results").mapNotNull { it.obj?.let { o -> HubSkill.from(o, ids) } }.distinctBy { it.identifier }
            }
            if (p == profile) { hubItems = items; hubError = null }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (p == profile) hubError = e.text()
        } finally { hubLoading = false }
    }

    /** A hub row counts as installed when the catalog says so or this bot has a hub skill of that name. */
    fun isInstalled(h: HubSkill): Boolean = h.installed || skills.any { it.name == h.name && it.provenance == "hub" }

    suspend fun preview(h: HubSkill): JsonObject? = runCatching { store.api.hubPreview(h.identifier, profile) }.getOrNull()
    suspend fun scan(h: HubSkill): JsonObject? = runCatching { store.api.hubScan(h.identifier, profile) }.getOrNull()

    fun install(h: HubSkill) = action(h.identifier, start = { store.api.hubInstall(h.identifier, it) }) { p, err ->
        if (err != null) return@action fail(err)
        markHub(h.name, true)
        if (p == profile) refreshSkills()
        done(t.installedName(h.name), OneShot.Learned)
    }

    fun uninstall(name: String) = action("rm:$name", start = { store.api.hubUninstall(name, it) }) { p, err ->
        if (p == profile) refreshSkills()
        if (err != null) return@action fail(err)
        markHub(name, false)
        done(t.removedName(name), OneShot.Wave)
    }

    fun updateAll() = action("update", start = { store.api.hubUpdateAll(it) }) { p, err ->
        if (p == profile) refreshSkills()
        if (err != null) fail(err) else done(t.updated, OneShot.Done)
    }

    private fun markHub(name: String, installed: Boolean) {
        fun List<HubSkill>.mark() = map { if (it.name == name) it.copy(installed = installed) else it }
        official = official.mark(); hubItems = hubItems.mark()
    }

    // ------------------------------------------------------------------ curator

    fun loadCurator() {
        val p = profile
        ui.launch {
            try {
                val c = store.api.curator(p)
                if (p == profile) { curator = c; curatorError = null }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (p == profile) curatorError = e.text() }
        }
    }

    fun setCuratorPaused(paused: Boolean) {
        val p = profile
        val before = curator ?: return
        curator = JsonObject(before + ("paused" to JsonPrimitive(paused)))
        play(if (paused) OneShot.Yawn else OneShot.Stretch)
        store.scope.launch {
            try { store.api.curatorPause(paused, p) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (p == profile) curator = before
                fail(e.text())
            }
        }
    }

    fun runCurator() = action("curator", start = { store.api.curatorRun(it) }) { p, err ->
        if (p == profile) { refreshSkills(); loadCurator() }
        if (err != null) fail(err) else done(t.tidied, OneShot.Done)
    }

    // ------------------------------------------------------------------ background actions

    /** Starts a background action for this bot, mirrors its output under [key], then calls [finish] with null (exit 0) or what went wrong. */
    private fun action(key: String, start: suspend (String) -> JsonObject?, finish: (String, String?) -> Unit) {
        val p = profile
        val k = "$p|$key"
        if (k in running) return
        running[k] = emptyList()
        play(OneShot.Nod)
        store.scope.launch {
            val err = try {
                val r = start(p)
                val name = r.str("name")
                if (r.bool("ok") == false || name == null) unbrand(r.str("detail") ?: r.str("message") ?: s.error)
                else {
                    val st = store.api.awaitAction(name, p) { if (k in running) running[k] = it.filter(String::isNotBlank).takeLast(6).map(::unbrand) }
                    when {
                        st == null -> t.stillRunning
                        st.long("exit_code") == 0L -> null
                        else -> st.list("lines").mapNotNull { (it as? JsonPrimitive)?.content }.lastOrNull { it.isNotBlank() }?.let(::unbrand) ?: s.error
                    }
                }
            } catch (e: CancellationException) { running.remove(k); throw e } catch (e: Exception) { e.text() }
            running.remove(k)
            finish(p, err)
        }
    }

    // ------------------------------------------------------------------ feedback

    private fun done(text: String, shot: OneShot) {
        play(shot)
        fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm)
        toasts?.show(ToastData(text, buddy = spec, shot = shot))
    }

    private fun fail(text: String) {
        failing = true
        fb.sound(Sound.Bonk); fb.haptic(Haptic.Reject)
        toasts?.show(ToastData(text))
        failJob?.cancel()
        failJob = store.scope.launch { delay(2_500); failing = false }
    }

    private fun Throwable.text(): String = unbrand(((this as? RestError)?.detail ?: message).orEmpty().ifBlank { s.error }.take(200))
}
