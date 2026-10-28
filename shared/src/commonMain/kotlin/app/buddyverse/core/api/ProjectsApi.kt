package app.buddyverse.core.api

// Owner: Project (ui/project). hermes-api.md §2.5 "Projects and workspaces": `projects.*` are WS legacy calls on each
// profile's own projects.db; `projects.project_sessions` hydrates the project's lanes. Extension functions only.

import androidx.compose.runtime.Immutable
import app.buddyverse.core.AppStore
import app.buddyverse.core.Hermes
import app.buddyverse.core.Room
import app.buddyverse.core.bool
import app.buddyverse.core.child
import app.buddyverse.core.double
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.str
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** One `ProjectInfo` from `projects.list` (hermes-api.md §2.5 "Projects and workspaces"). Owner: Project area. [Project] and [projectList] are used by Bots home and More ▸ Projects too: keep their shape. */
@Immutable
data class Project(
    val profile: String,
    val id: String,
    val name: String,
    val description: String?,
    val color: String?,
    val primaryPath: String?,
    val folders: List<String>,
    val archived: Boolean,
    val createdAt: Double,
    val slug: String? = null,
) {
    companion object {
        fun from(profile: String, o: JsonObject?): Project? = Project(
            profile = profile,
            id = o.str("id") ?: return null,
            name = o.str("name").orEmpty(),
            description = o.str("description")?.takeIf { it.isNotBlank() },
            color = o.str("color"),
            primaryPath = o.str("primary_path"),
            folders = o.list("folders").mapNotNull { it.obj.str("path") },
            archived = o.bool("archived") == true,
            createdAt = o.double("created_at") ?: 0.0,
            slug = o.str("slug")?.takeIf { it.isNotBlank() },
        )
    }
}

/** Every project of [profile], archived included (filter on [Project.archived]). */
suspend fun Hermes.projectList(profile: String): List<Project> = projects(profile).list("projects").mapNotNull { Project.from(profile, it.obj) }

private suspend fun Hermes.projectCall(method: String, profile: String, vararg p: Pair<String, Any?>): JsonObject? =
    gateway.call(method, params("profile" to profile, *p)).obj

private fun JsonObject?.project(profile: String): Project? = Project.from(profile, child("project"))

suspend fun Hermes.projectGet(profile: String, id: String): Project? = projectCall("projects.get", profile, "id" to id).project(profile)

/** `projects.update`: only the fields given change (null = keep). */
suspend fun Hermes.projectUpdate(profile: String, id: String, name: String? = null, description: String? = null, color: String? = null): Project? =
    projectCall("projects.update", profile, "id" to id, "name" to name, "description" to description, "color" to color).project(profile)

/** `projects.archive` (or bring back with [restore]); answers the whole list. */
suspend fun Hermes.projectArchive(profile: String, id: String, restore: Boolean = false) {
    projectCall("projects.archive", profile, "id" to id, "restore" to restore.takeIf { it })
}

suspend fun Hermes.projectDelete(profile: String, id: String) { projectCall("projects.delete", profile, "id" to id) }

suspend fun Hermes.projectAddFolder(profile: String, id: String, path: String): Project? =
    projectCall("projects.add_folder", profile, "id" to id, "path" to path).project(profile)

suspend fun Hermes.projectRemoveFolder(profile: String, id: String, path: String): Project? =
    projectCall("projects.remove_folder", profile, "id" to id, "path" to path).project(profile)

suspend fun Hermes.projectSetPrimary(profile: String, id: String, path: String): Project? =
    projectCall("projects.set_primary", profile, "id" to id, "path" to path).project(profile)

/** One session row of a project lane (`ProjectTreeSession`), stamped with the profile that owns it. */
@Immutable
data class ProjectSession(
    val id: String, val profile: String, val title: String?, val preview: String?, val lastActive: Double,
    val active: Boolean, val messages: Long, val source: String?, val branch: String?,
)

/** What `projects.project_sessions` says about a project: its sessions (newest first) and totals. */
@Immutable
data class ProjectWork(val sessions: List<ProjectSession>, val sessionCount: Int, val tokens: Long, val costUsd: Double)

suspend fun Hermes.projectSessions(profile: String, id: String, limit: Int = 50): ProjectWork {
    val node = projectCall("projects.project_sessions", profile, "project_id" to id, "session_limit" to limit).child("project")
    val rows = buildList {
        for (repo in node.list("repos")) for (lane in repo.obj.list("groups")) addAll(lane.obj.list("sessions"))
        addAll(node.list("previewSessions"))
    }
    val sessions = rows.mapNotNull { e ->
        val o = e.obj
        ProjectSession(
            id = o.str("id") ?: return@mapNotNull null,
            profile = o.str("profile") ?: profile,
            title = o.str("title")?.takeIf { it.isNotBlank() },
            preview = o.str("preview")?.takeIf { it.isNotBlank() },
            lastActive = o.double("last_active") ?: o.double("started_at") ?: 0.0,
            active = o.bool("is_active") == true,
            messages = o.long("message_count") ?: 0L,
            source = o.str("source"),
            branch = o.str("git_branch"),
        )
    }.distinctBy { it.id }.sortedByDescending { it.lastActive }
    return ProjectWork(sessions, node.long("sessionCount")?.toInt() ?: sessions.size, node.long("totalTokens") ?: 0L, node.double("totalCostUsd") ?: 0.0)
}

// ---------------------------------------------------------------- who works on a project, and where they talk

/**
 * The project's identity across bots (each profile has its own projects.db): its folder, else its name. The Bots home
 * stage nests agents by the same folders (`ui/home/HomeModel.kt agentParents`).
 */
fun projectKey(p: Project): String =
    p.primaryPath?.trim()?.trimEnd('/', '\\')?.lowercase()?.takeIf { it.isNotEmpty() } ?: ("name:" + p.name.trim().lowercase())

/** The project's agents: its owner first, then every other bot whose projects.db holds the same project (not archived). */
suspend fun AppStore.projectAgents(p: Project): List<String> {
    val key = projectKey(p)
    val all = api.projectsOf(bots.value.map { it.profile })
    val members = bots.value.filter { b -> b.profile != p.profile && all[b.profile].orEmpty().any { !it.archived && projectKey(it) == key } }.map { it.profile }
    return listOf(p.profile) + members
}

/** The project's room: the group chat named like the project (newest first, as `groups.list` orders them). */
fun projectRoom(rooms: List<Room>, p: Project): Room? = rooms.firstOrNull { it.name.trim().equals(p.name.trim(), ignoreCase = true) }

// ---------------------------------------------------------------- the living page (brain/projects/<page>.md)

/** The vault page's file name: the slug, else the name in lower case with spaces and slashes as dashes. */
fun livingPageNames(p: Project): List<String> = listOfNotNull(
    p.slug,
    p.name.trim().lowercase().replace(Regex("[\\s/\\\\]+"), "-").takeIf { it.isNotEmpty() },
).distinct()

/** The page's sections by heading (`## Now`, `## Next`, …), in file order; frontmatter dropped. */
fun pageSections(md: String): List<Pair<String, String>> {
    val body = if (md.startsWith("---")) md.substringAfter("\n---", md).substringAfter('\n', "") else md
    val out = ArrayList<Pair<String, String>>()
    var head: String? = null
    val buf = StringBuilder()
    fun flush() { head?.let { out += it to buf.toString().trim() }; buf.clear() }
    for (line in body.lines()) {
        if (line.startsWith("## ")) { flush(); head = line.removePrefix("## ").trim() } else if (head != null) buf.appendLine(line)
    }
    flush()
    return out
}

/** Where the brain vault may live: under the brain's workspace, or beside one of the project's parent folders. */
private fun vaultRoots(brainCwd: String?, projectPath: String?): List<String> {
    val out = ArrayList<String>()
    fun root(dir: String) { val d = dir.trimEnd('/', '\\'); if (d.isNotEmpty()) out += if (d.endsWith("brain", true)) d else "$d/brain" }
    // The vault usually lives inside the project folder itself (`<project>/brain`), so that comes first.
    projectPath?.let(::root)
    brainCwd?.let(::root)
    var p = projectPath?.trimEnd('/', '\\').orEmpty()
    repeat(3) {
        val cut = maxOf(p.lastIndexOf('/'), p.lastIndexOf('\\'))
        if (cut <= 0) return out.distinct()
        p = p.substring(0, cut)
        root(p)
    }
    return out.distinct()
}

/** A found living page: its path and text. */
@Immutable
data class LivingPage(val path: String, val text: String)

/**
 * Reads `brain/projects/<page>.md` through the PC's file routes (`/api/fs/read-text`).
 * ponytail: probes a few likely vault roots (≤ 8 reads); a vault path setting would replace the guess.
 */
suspend fun Hermes.livingPage(p: Project): LivingPage? {
    val cwd = runCatching { workspaceRoot("default") }.getOrNull()
    for (root in vaultRoots(cwd, p.primaryPath).take(5)) for (page in livingPageNames(p)) {
        val path = "$root/projects/$page.md"
        try {
            val f = readText(path, null)
            if (!f.binary && f.text.isNotBlank()) return LivingPage(f.path, f.text)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    return null
}
