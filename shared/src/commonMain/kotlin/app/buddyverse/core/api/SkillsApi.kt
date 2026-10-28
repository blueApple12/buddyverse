package app.buddyverse.core.api

import app.buddyverse.core.Hermes
import app.buddyverse.core.bool
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.obj
import app.buddyverse.core.params
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Owner: More ▸ Skills (ui/more/SkillsPage.kt). hermes-api.md §2.5 Skills (+ hub, curator) and §2.6 Skills.
// List / content / toggle already live in Hermes.kt (skills, skillContent, toggleSkill).

private fun e(v: String) = v.encodeURLParameter()

// ---- installed skills: create, edit, delete ----

/** `POST /api/skills`: [content] is the whole SKILL.md with frontmatter. 400 `{detail}` on a bad name or body. */
suspend fun Hermes.createSkill(name: String, content: String, category: String?, profile: String): JsonObject? =
    rest.post("/api/skills?profile=${e(profile)}", params("name" to name, "content" to content, "category" to category, "profile" to profile)).obj

/** `PUT /api/skills/content`: full rewrite of SKILL.md. */
suspend fun Hermes.saveSkillContent(name: String, content: String, profile: String): JsonObject? =
    rest.put("/api/skills/content?profile=${e(profile)}", params("name" to name, "content" to content, "profile" to profile)).obj

/** Delete = archive (`DELETE /api/learning/node {id: name}`). A pinned skill answers 400 "… is pinned …". */
suspend fun Hermes.archiveSkill(name: String, profile: String): JsonElement =
    rest.delete("/api/learning/node?profile=${e(profile)}", params("id" to name))

// ---- hub ----

suspend fun Hermes.hubOfficial(profile: String): List<JsonObject> =
    rest.get("/api/skills/hub/official?profile=${e(profile)}").obj.list("skills").mapNotNull { it.obj }

/** `{sources: [{id, label, searchable, rate_limited?, available?}], index_available, featured[], installed{}}`. */
suspend fun Hermes.hubSources(profile: String): JsonObject? = rest.get("/api/skills/hub/sources?profile=${e(profile)}").obj

/** `{results: HubSkill[], installed: {identifier: …}, timed_out[]}`; the server gives up after 30 s. */
suspend fun Hermes.hubSearch(query: String, profile: String, source: String = "all", limit: Int = 30): JsonObject? =
    rest.get("/api/skills/hub/search?q=${e(query)}&source=${e(source)}&limit=$limit&profile=${e(profile)}").obj

/** `{name, description, source, identifier, trust_level, repo, tags, skill_md, files[]}`. */
suspend fun Hermes.hubPreview(identifier: String, profile: String): JsonObject? =
    rest.get("/api/skills/hub/preview?identifier=${e(identifier)}&profile=${e(profile)}").obj

/** `{verdict, summary, policy: allow|ask|block, findings[{severity, category, file, line, description}], severity_counts}`. */
suspend fun Hermes.hubScan(identifier: String, profile: String): JsonObject? =
    rest.get("/api/skills/hub/scan?identifier=${e(identifier)}&profile=${e(profile)}").obj

/** Background action: `{ok, pid, name}`; follow it with [awaitAction]. */
suspend fun Hermes.hubInstall(identifier: String, profile: String): JsonObject? =
    rest.post("/api/skills/hub/install?profile=${e(profile)}", params("identifier" to identifier, "profile" to profile), timeoutMs = 300_000).obj

suspend fun Hermes.hubUninstall(name: String, profile: String): JsonObject? =
    rest.post("/api/skills/hub/uninstall?profile=${e(profile)}", params("name" to name, "profile" to profile)).obj

suspend fun Hermes.hubUpdateAll(profile: String): JsonObject? =
    rest.post("/api/skills/hub/update?profile=${e(profile)}", params(), timeoutMs = 300_000).obj

// ---- curator (background skill upkeep) ----

/** `{enabled, paused, interval_hours, last_run_at, min_idle_hours, stale_after_days, archive_after_days}`. */
suspend fun Hermes.curator(profile: String): JsonObject? = rest.get("/api/curator?profile=${e(profile)}").obj

suspend fun Hermes.curatorPause(paused: Boolean, profile: String): JsonObject? =
    rest.put("/api/curator/paused?profile=${e(profile)}", params("paused" to paused)).obj

/** Destructive (archives and rewrites skills). Background action `curator-run`. */
suspend fun Hermes.curatorRun(profile: String): JsonObject? = rest.post("/api/curator/run?profile=${e(profile)}", params(), timeoutMs = 300_000).obj

// ---- background actions ----

/** `{name, running, exit_code, pid, lines[]}`; 404 for an unknown name. */
suspend fun Hermes.actionStatus(name: String, profile: String, lines: Int = 200): JsonObject? =
    rest.get("/api/actions/${e(name)}/status?lines=$lines&profile=${e(profile)}").obj

/**
 * Polls an action every 1.2 s until `running:false` (desktop pattern, hermes-api.md §2.6). Returns the final status, or null when
 * it is still running after [maxPolls]. A status with no exit code yet is only trusted after a few polls (the action may not have started).
 */
suspend fun Hermes.awaitAction(name: String, profile: String, maxPolls: Int = 250, onLines: (List<String>) -> Unit = {}): JsonObject? {
    repeat(maxPolls) { i ->
        delay(1_200)
        val st = runCatching { actionStatus(name, profile) }.getOrNull() ?: return@repeat
        onLines(st.list("lines").mapNotNull { (it as? JsonPrimitive)?.content })
        if (st.bool("running") == false && (st.long("exit_code") != null || i >= 3)) return st
    }
    return null
}
