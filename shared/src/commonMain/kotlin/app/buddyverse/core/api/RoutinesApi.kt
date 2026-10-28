package app.buddyverse.core.api

// Owner: Routines (ui/routines). Extra Hermes calls and store intents for this area go here as extension functions
// (`suspend fun Hermes.x(...)`, `fun AppStore.y(...)`), never into Hermes.kt or AppStore.kt.

import app.buddyverse.core.AppStore
import app.buddyverse.core.Hermes
import app.buddyverse.core.RestError
import app.buddyverse.core.list
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.str
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

private fun q(v: String) = v.encodeURLParameter()

/** `GET /api/cron/delivery-targets` (hermes-api.md §2.5): `{id, name, home_target_set}` rows. */
suspend fun Hermes.cronDeliveryTargets(profile: String): List<JsonObject> =
    rest.get("/api/cron/delivery-targets?profile=${q(profile)}").obj.list("targets").mapNotNull { it.obj }

/** Template gallery: `{key, title, description, fields[]}` rows. */
suspend fun Hermes.cronBlueprints(profile: String): List<JsonObject> =
    rest.get("/api/cron/blueprints?profile=${q(profile)}").obj.list("blueprints").mapNotNull { it.obj }

suspend fun Hermes.cronInstantiate(profile: String, key: String, values: Map<String, String>): JsonObject? =
    rest.post("/api/cron/blueprints/instantiate?profile=${q(profile)}", params("blueprint" to key, "values" to values)).obj

/**
 * Create (POST body) or edit (`updates`) with every editor field: name, prompt, schedule, deliver, skills, model.
 * [AppStore.saveRoutine] only knows name/prompt/schedule and always delivers `local`.
 */
suspend fun AppStore.saveRoutineFull(existing: JsonObject?, profile: String, fields: Map<String, Any?>): Result<JsonObject?> = try {
    val job = if (existing == null) api.cronCreate(profile, fields)
    else api.cronUpdate(existing.str("id")!!, existing.str("profile") ?: profile, fields)
    refreshRoutines()
    Result.success(job)
} catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }

/** The server's own words for a failed call (FastAPI `detail`), else the exception text. */
fun Throwable.routineError(): String = (this as? RestError)?.detail ?: message ?: "error"
