package app.buddyverse.ui.more.projects

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.AppStore
import app.buddyverse.core.Hermes
import app.buddyverse.core.RestError
import app.buddyverse.core.RpcError
import app.buddyverse.core.api.Project
import app.buddyverse.core.child
import app.buddyverse.core.double
import app.buddyverse.core.list
import app.buddyverse.core.nowMillis
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.str
import app.buddyverse.design.Feedback
import app.buddyverse.design.Haptic
import app.buddyverse.design.Sound
import app.buddyverse.i18n.ProjectsPageStrings
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.Toasts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

// ---- page-private calls: hermes-api.md §2.5 "Projects and workspaces" (WS legacy, per profile's projects.db) ----

private suspend fun Hermes.projectsCall(method: String, profile: String, vararg p: Pair<String, Any?>): JsonObject? =
    gateway.call(method, params("profile" to profile, *p)).obj

/** A `ProjectsPayload` (`projects.list`, `.archive`, `.delete`): every project, archived included, and the active one. */
@Immutable
data class ProfileProjects(val projects: List<Project>, val activeId: String?)

fun payloadOf(profile: String, o: JsonObject?) = ProfileProjects(o.list("projects").mapNotNull { Project.from(profile, it.obj) }, o.str("active_id"))

/** A git root the PC found (`projects.discover_repos`): one tap fills the new project's folder. */
@Immutable
data class Repo(val root: String, val label: String, val lastActive: Double)

@Immutable
data class ProjectDraft(val name: String = "", val folder: String = "", val description: String = "", val color: String? = null, val owner: String = "")

fun draftOf(p: Project) = ProjectDraft(p.name, p.primaryPath.orEmpty(), p.description.orEmpty(), p.color, p.profile)

enum class ProjectFilter { Active, Archived }

/** Last segment of a Windows or POSIX path: `C:\code\mods\` → `mods`. */
fun folderName(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

/** Paths compare without case, slash style or a trailing slash (Windows host). */
fun samePath(a: String, b: String): Boolean {
    fun n(s: String) = s.replace('\\', '/').trimEnd('/').lowercase()
    return n(a) == n(b)
}

val Project.title: String get() = name.ifBlank { primaryPath?.let(::folderName).orEmpty() }.ifBlank { id }
val Project.key: String get() = "$profile/$id"

/** `#rrggbb`: what the Desktop stores in `ProjectInfo.color`. */
fun hex(c: Color): String = "#" + (c.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0')

/** The rows of [filter]: local intents ([archivedNow] overrides, [gone] = deleted) win over the last listing; newest first. */
fun rowsOf(all: List<Project>, filter: ProjectFilter, archivedNow: Map<String, Boolean>, gone: Set<String>): List<Project> =
    all.filter { it.key !in gone && (archivedNow[it.key] ?: it.archived) == (filter == ProjectFilter.Archived) }
        .sortedByDescending { it.createdAt }

private fun Throwable.why(): String = when (this) {
    is RestError -> detail
    is RpcError -> message ?: reason
    else -> message ?: toString()
}

/**
 * State and intents of More ▸ Projects across every bot (each Hermes profile has its own projects.db).
 * Writes run on the store's scope, so they finish even if the page closes. Archive, restore, delete, "current" and
 * folder edits change the list in the same frame; a failed call puts it back and says why.
 */
@Stable
class ProjectsModel(private val store: AppStore, private val toasts: Toasts, private val fb: Feedback) {
    private val api get() = store.api
    lateinit var t: ProjectsPageStrings
    var undoLabel = "Undo"

    var byProfile by mutableStateOf<Map<String, ProfileProjects>>(emptyMap()); private set
    var loaded by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var unsupported by mutableStateOf(false); private set
    var archivedNow by mutableStateOf<Map<String, Boolean>>(emptyMap()); private set
    var gone by mutableStateOf<Set<String>>(emptySet()); private set
    var inFlight by mutableStateOf(0); private set
    /** Serial = timestamp, so a buddy composed later can skip a stale cue. */
    var cue by mutableStateOf<BuddyCue?>(null); private set
    var failedAt by mutableStateOf(0L); private set
    var repos by mutableStateOf<List<Repo>>(emptyList()); private set
    private var reposOf: String? = null
    private var serial = 0

    val all: List<Project> get() = byProfile.values.flatMap { it.projects }
    fun rows(f: ProjectFilter) = rowsOf(all, f, archivedNow, gone)
    fun isCurrent(p: Project) = byProfile[p.profile]?.activeId == p.id
    fun find(key: String?) = key?.let { k -> all.firstOrNull { it.key == k } }

    fun load(profiles: List<String>) { store.scope.launch { refresh(profiles) } }

    /** Every profile in parallel; a profile that fails keeps what it showed. Only the newest refresh lands. */
    suspend fun refresh(profiles: List<String>) {
        if (profiles.isEmpty()) return
        val mine = ++serial
        val results = coroutineScope {
            profiles.map { p ->
                async {
                    val r: Result<ProfileProjects> = try { Result.success(payloadOf(p, api.projects(p))) }
                        catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
                    p to r
                }
            }.awaitAll()
        }
        if (mine != serial) return
        val errors = results.mapNotNull { it.second.exceptionOrNull() }
        val next = buildMap<String, ProfileProjects> { for ((p, r) in results) (r.getOrNull() ?: byProfile[p])?.let { put(p, it) } }
        byProfile = next
        loaded = true
        unsupported = next.isEmpty() && errors.isNotEmpty() && errors.all { it is RpcError && it.unknownMethod }
        error = if (next.isEmpty()) errors.firstOrNull()?.why() else null
    }

    private fun react(shot: OneShot) { cue = BuddyCue(nowMillis(), shot) }
    private fun ouch() { failedAt = nowMillis(); fb.sound(Sound.Bonk); fb.haptic(Haptic.Reject) }

    /** One write. On failure: [rollback], the bonk, and the reason inline ([inline]) or as a toast. */
    private fun run(rollback: () -> Unit = {}, inline: ((String) -> Unit)? = null, block: suspend () -> Unit) {
        inFlight++
        store.scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                rollback(); ouch()
                if (inline != null) inline(e.why()) else toasts.show(ToastData(e.why()))
            } finally { inFlight-- }
        }
    }

    private fun setPayload(profile: String, o: JsonObject?) {
        if (o?.get("projects") != null) byProfile = byProfile + (profile to payloadOf(profile, o))
    }

    private fun patch(profile: String, id: String, change: (Project) -> Project) {
        val pp = byProfile[profile] ?: return
        byProfile = byProfile + (profile to pp.copy(projects = pp.projects.map { if (it.id == id) change(it) else it }))
    }

    /** A `{project}` answer replaces that row. */
    private fun setProject(profile: String, o: JsonObject?) {
        val np = Project.from(profile, o.child("project")) ?: return
        patch(profile, np.id) { np }
    }

    /** Archive (or restore) now; the undo toast flips it back. */
    fun archive(p: Project, restore: Boolean = false, spec: BuddySpec? = null, announce: Boolean = true) {
        val k = p.key
        val want = !restore
        archivedNow = archivedNow + (k to want)
        fb.haptic(Haptic.Confirm)
        react(if (restore) OneShot.Arrive else OneShot.Done)
        if (announce) toasts.show(
            ToastData(
                if (restore) t.restored(p.title) else t.archivedName(p.title), undoLabel,
                onAction = { archive(p, restore = !restore, announce = false) }, buddy = spec, shot = OneShot.Wave,
            ),
        )
        // Only clear the override this call set: a quick undo's own override must survive this answer.
        fun settle() { if (archivedNow[k] == want) archivedNow = archivedNow - k }
        run(rollback = ::settle) {
            setPayload(p.profile, api.projectsCall("projects.archive", p.profile, "id" to p.id, "restore" to restore.takeIf { it }))
            settle()
        }
    }

    fun delete(p: Project, spec: BuddySpec?) {
        val k = p.key
        gone = gone + k
        react(OneShot.Startle)
        run(rollback = { gone = gone - k }) {
            setPayload(p.profile, api.projectsCall("projects.delete", p.profile, "id" to p.id))
            gone = gone - k
            toasts.show(ToastData(t.deleted(p.title), buddy = spec, shot = OneShot.Wave))
        }
    }

    /** `projects.set_active`: new chats of that bot start in this project (one per bot). */
    fun setCurrent(p: Project, on: Boolean) {
        val before = byProfile[p.profile] ?: return
        byProfile = byProfile + (p.profile to before.copy(activeId = p.id.takeIf { on }))
        fb.haptic(Haptic.Confirm)
        react(OneShot.Nod)
        run(rollback = { byProfile[p.profile]?.let { byProfile = byProfile + (p.profile to it.copy(activeId = before.activeId)) } }) {
            val r = api.projectsCall("projects.set_active", p.profile, "id" to p.id.takeIf { on })
            byProfile[p.profile]?.let { byProfile = byProfile + (p.profile to it.copy(activeId = r.str("active_id"))) }
        }
    }

    /** [done] gets null on success, else the reason (shown in the sheet). */
    fun create(d: ProjectDraft, done: (String?) -> Unit) {
        val name = d.name.trim()
        if (name.isEmpty()) { done(t.needName); return }
        val owner = d.owner
        run(inline = done) {
            val r = api.projectsCall(
                "projects.create", owner, "name" to name, "primary_path" to d.folder.trim().ifEmpty { null },
                "description" to d.description.trim().ifEmpty { null }, "color" to d.color,
            )
            Project.from(owner, r.child("project"))?.let { np ->
                val pp = byProfile[owner] ?: ProfileProjects(emptyList(), null)
                byProfile = byProfile + (owner to pp.copy(projects = pp.projects.filter { it.id != np.id } + np))
            }
            fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm); react(OneShot.Learned)
            toasts.show(ToastData(t.created(name)))
            done(null)
        }
    }

    /** Name, description and color (`projects.update`; "" clears description and color). */
    fun save(p: Project, d: ProjectDraft, done: (String?) -> Unit) {
        val name = d.name.trim()
        if (name.isEmpty()) { done(t.needName); return }
        run(inline = done) {
            setProject(p.profile, api.projectsCall(
                "projects.update", p.profile, "id" to p.id, "name" to name, "description" to d.description.trim(), "color" to (d.color ?: ""),
            ))
            fb.haptic(Haptic.Confirm); react(OneShot.Nod)
            toasts.show(ToastData(t.saved))
            done(null)
        }
    }

    fun addFolder(p: Project, path: String, done: (String?) -> Unit) {
        val clean = path.trim()
        if (clean.isEmpty()) return
        run(inline = done) {
            setProject(p.profile, api.projectsCall("projects.add_folder", p.profile, "id" to p.id, "path" to clean))
            fb.haptic(Haptic.Confirm)
            done(null)
        }
    }

    fun removeFolder(p: Project, path: String) {
        val before = p
        patch(p.profile, p.id) { cur ->
            val left = cur.folders.filterNot { samePath(it, path) }
            cur.copy(folders = left, primaryPath = if (cur.primaryPath?.let { samePath(it, path) } == true) left.firstOrNull() else cur.primaryPath)
        }
        fb.haptic(Haptic.Reject)
        run(rollback = { patch(p.profile, p.id) { before } }) {
            setProject(p.profile, api.projectsCall("projects.remove_folder", p.profile, "id" to p.id, "path" to path))
        }
    }

    fun setPrimary(p: Project, path: String) {
        val before = p
        patch(p.profile, p.id) { it.copy(primaryPath = path) }
        run(rollback = { patch(p.profile, p.id) { before } }) {
            setProject(p.profile, api.projectsCall("projects.set_primary", p.profile, "id" to p.id, "path" to path))
        }
    }

    /** Git roots the PC already knows (cached scan, no disk walk), newest first. */
    fun loadRepos(profile: String) {
        if (reposOf == profile) return
        reposOf = profile
        repos = emptyList()
        store.scope.launch {
            val r = try { api.projectsCall("projects.discover_repos", profile, "scan" to false) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (reposOf != profile) return@launch
            repos = r.list("repos").mapNotNull { it.obj }.mapNotNull { o ->
                val root = o.str("root")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Repo(root, o.str("label")?.takeIf { it.isNotBlank() } ?: folderName(root), o.double("last_active") ?: 0.0)
            }.sortedByDescending { it.lastActive }
        }
    }
}
