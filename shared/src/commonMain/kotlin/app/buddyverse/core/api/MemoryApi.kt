package app.buddyverse.core.api

import app.buddyverse.core.Hermes
import app.buddyverse.core.child
import app.buddyverse.core.list
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.str
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Owner: More ▸ Memory (ui/more/MemoryPage.kt, ui/more/memory). hermes-api.md §2.5 Memory + Memory providers, §2.4 Learning.
// Graph / node read, edit and delete already live in Hermes.kt (learningGraph, learningNode, editMemory, deleteMemory).

private fun e(v: String) = v.encodeURLParameter()

/** `GET /api/memory`: `{active: "<provider>"|"", providers: [{name, description, available, configured, status, setup}], builtin_files: {memory, user}}`. */
suspend fun Hermes.memoryStatus(profile: String): JsonObject? = rest.get("/api/memory?profile=${e(profile)}").obj

/** `PUT /api/memory/provider`: [name] `""` = built-in only. 400 when unknown or not `ready`. Applies to new sessions. */
suspend fun Hermes.setMemoryProvider(name: String, profile: String): JsonObject? =
    rest.put("/api/memory/provider?profile=${e(profile)}", params("provider" to name)).obj

/**
 * Provider config schema. [declared] = the desktop surface (`?surface=declared`, plugins with a declared schema; others answer `fields: []`);
 * otherwise the plugin's own fields plus `setup`. Field: `{key, label, kind: text|secret|select|boolean|bool|integer|number|json, description,
 * placeholder, required?, value (secrets ""), is_set, options[{value,label}], url?}`.
 */
suspend fun Hermes.memoryProviderConfig(name: String, profile: String, declared: Boolean): JsonObject? =
    rest.get("/api/memory/providers/${e(name)}/config?${if (declared) "surface=declared&" else ""}profile=${e(profile)}").obj

/** Save values. [declared] only saves; otherwise it also makes the provider active (400 if it isn't ready). Secrets go to the profile env. */
suspend fun Hermes.saveMemoryProviderConfig(name: String, values: Map<String, String>, profile: String, declared: Boolean): JsonObject? =
    rest.put("/api/memory/providers/${e(name)}/config?${if (declared) "surface=declared&" else ""}profile=${e(profile)}", params("values" to values)).obj

/** `POST …/setup`: saves [values] and installs the provider's dependencies. `{ok, provider, results[{kind,name,status,error?}], status}`. */
suspend fun Hermes.setupMemoryProvider(name: String, values: Map<String, String>, profile: String): JsonObject? =
    rest.post("/api/memory/providers/${e(name)}/setup?profile=${e(profile)}", params("values" to values)).obj

/** OAuth (sign-in runs in a browser on the PC). Status: `{auth, connected, detail, state: idle|pending|connected|error}`; 404 = no OAuth. */
suspend fun Hermes.memoryOauthStart(name: String, profile: String): JsonObject? =
    rest.post("/api/memory/providers/${e(name)}/oauth/start?profile=${e(profile)}").obj

suspend fun Hermes.memoryOauthStatus(name: String, profile: String): JsonObject? =
    rest.get("/api/memory/providers/${e(name)}/oauth/status?profile=${e(profile)}").obj

/** Destructive: deletes MEMORY.md ([target] `memory`), USER.md (`user`) or both (`all`). `{ok, deleted[]}`. */
suspend fun Hermes.resetMemory(target: String, profile: String): JsonObject? =
    rest.post("/api/memory/reset?profile=${e(profile)}", params("target" to target)).obj

/** The `memory.*` config section: memory_enabled, user_profile_enabled, write_approval, memory_char_limit, user_char_limit, provider… */
suspend fun Hermes.memoryConfig(profile: String): JsonObject? = rest.get("/api/config?profile=${e(profile)}").obj.child("memory")

/** Sparse deep-merged write of `memory.*` keys (`PUT /api/config`). Applies to new sessions. */
suspend fun Hermes.setMemoryConfig(fields: Map<String, Any?>, profile: String): JsonElement =
    rest.put("/api/config?profile=${e(profile)}", params("config" to mapOf("memory" to fields)))

/**
 * Writes waiting for a yes (`memory.write_approval` on: background reviews stage them). No REST exists; it is the `/memory` command
 * on one of the bot's sessions ([arg] `pending`, `approve <id|all>`, `reject <id|all>`). Returns the command's text output.
 */
suspend fun Hermes.memoryReview(sessionId: String, profile: String, arg: String): String =
    slashExec(sessionId, profile, "/memory $arg").str("output").orEmpty()

/** For the More hub's Memory tile: entries in USER.md + MEMORY.md. */
suspend fun Hermes.memoryCount(profile: String): Int = learningGraph(profile).list("nodes").count { it.obj.str("kind") == "memory" }
