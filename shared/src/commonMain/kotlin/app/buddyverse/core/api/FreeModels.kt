package app.buddyverse.core.api

import app.buddyverse.core.Hermes
import app.buddyverse.core.RestError
import app.buddyverse.core.WireJson
import app.buddyverse.core.child
import app.buddyverse.core.list
import app.buddyverse.core.nowMillis
import app.buddyverse.core.obj
import app.buddyverse.core.platformHttpClient
import app.buddyverse.core.str
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// The model sheet's OpenRouter section lists only free chat models, read live from OpenRouter's public catalog
// (the engine's own list for `openrouter` is mostly paid).

const val OPENROUTER = "openrouter"
private const val OPENROUTER_MODELS = "https://openrouter.ai/api/v1/models"

/** Free rows that aren't chat models (music generation, safety classifiers). */
private val NOT_CHAT = Regex("""^google/lyria-|content-safety|guard""", RegexOption.IGNORE_CASE)

private fun zero(v: String?): Boolean = v?.trim()?.toDoubleOrNull() == 0.0

/** Ids of the free chat models in an OpenRouter `/api/v1/models` body; the `openrouter/free` router first. */
fun openRouterFreeIds(body: String): List<String> =
    WireJson.parseToJsonElement(body).obj.list("data").mapNotNull { e ->
        val m = e.obj
        val id = m.str("id") ?: return@mapNotNull null
        val price = m.child("pricing")
        if (!zero(price.str("prompt")) || !zero(price.str("completion"))) return@mapNotNull null
        val out = m.child("architecture").list("output_modalities").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (out.isNotEmpty() && out != listOf("text")) return@mapNotNull null
        if (NOT_CHAT.containsMatchIn(id)) return@mapNotNull null
        id
    }.distinct().sortedBy { if (it == "openrouter/free") 0 else 1 }

/** The OpenRouter section with [free] ids; null (catalog unreachable) keeps the engine's ids that end in `:free`. */
fun ModelOptions.withFreeOpenRouter(free: List<String>?): ModelOptions = copy(providers = providers.mapNotNull { p ->
    if (p.slug != OPENROUTER) return@mapNotNull p
    val ids = free ?: (p.featured + p.models).distinct().filter { it.endsWith(":free") }
    if (ids.isEmpty()) null else p.copy(models = ids, featured = ids)
})

/** What every model picker shows: the engine's options with OpenRouter cut to its free models. */
suspend fun Hermes.pickerOptions(profile: String, sessionId: String?): ModelOptions {
    val o = modelOptions(profile, sessionId)
    // OpenRouter: only its free chat models, live from openrouter.ai (fallback: the engine's `:free` ids).
    return if (o.providers.none { it.slug == OPENROUTER }) o
    else o.withFreeOpenRouter(try { openRouterFreeModels() } catch (e: CancellationException) { throw e } catch (e: Exception) { null })
}

private val openRouterHttp by lazy { platformHttpClient() }
private var freeCache: Pair<Long, List<String>>? = null

/** Free OpenRouter models, cached in memory for 10 minutes. Throws when the catalog can't be read. */
suspend fun openRouterFreeModels(): List<String> {
    freeCache?.let { (at, ids) -> if (nowMillis() - at < 10 * 60_000) return ids }
    val ids = withTimeoutOrNull(20_000) {
        val r = openRouterHttp.get(OPENROUTER_MODELS)
        if (!r.status.isSuccess()) throw RestError(r.status.value, "openrouter")
        withContext(Dispatchers.Default) { openRouterFreeIds(r.bodyAsText()) }
    } ?: throw IllegalStateException("openrouter timed out")
    if (ids.isEmpty()) throw IllegalStateException("openrouter: no free models")
    freeCache = nowMillis() to ids
    return ids
}
