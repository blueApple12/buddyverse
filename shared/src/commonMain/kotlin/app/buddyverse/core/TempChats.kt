package app.buddyverse.core

import app.buddyverse.core.api.ModelOptions
import kotlinx.serialization.Serializable

/** A model on a provider (ids as `/api/model/options` lists them). */
@Serializable
data class ModelChoice(val provider: String, val model: String)

/**
 * Quick chats: one-off questions to the brain outside any project. Each is a normal saved session that works and learns
 * like any brain chat; Hermes auto-titles it (no fixed title: a create-time title is stored as the user's and blocks
 * auto-titling). Nothing ever deletes one on its own: Close archives it (undoable), only the session menu's Delete removes it.
 *
 * Marker: Hermes keeps no free-form per-session field (`PATCH /api/sessions/{id}` takes only title/archived/hidden/pinned/
 * unread; `session.create` refuses unknown params), so a quick chat is the owner's own definition, read from server
 * truth on phone and PC alike: a session of the brain (default profile, never in a project) that is not its Bot Chat.
 */
object TempChats {
    /** Sonnet 5.5 on the Claude subscription provider (ids from `/api/model/options`). */
    const val MODEL = "claude-sonnet-5-5[1m]"
    const val PROVIDER = "claude-subscription-directsdk-experimental"
    val SONNET = ModelChoice(PROVIDER, MODEL)

    /** Settings ▸ Temp chat model "Automatic" (also what a missing pref means): [resolve]. */
    val AUTO = ModelChoice("auto", "auto")

    /** Last Automatic result, so a fresh chat shows the right model before the engine answers. */
    var lastAuto: ModelChoice? = null
        private set

    /** What a new quick chat shows before the engine's model list is known: the user's pick, else the last Automatic
     *  result, else null (the engine's own default). */
    fun startModel(pref: ModelChoice?): ModelChoice? = pref?.takeIf { it != AUTO } ?: lastAuto

    /**
     * What a new quick chat starts on: the user's pick in Settings ▸ Temp chat model ([pref]); Automatic (null/[AUTO]) =
     * Sonnet on the Claude subscription provider when it is signed in ([opts] lists only signed-in providers), else the
     * engine's own default model/provider. Never a provider the user doesn't have; null = let the engine choose.
     * The model chip still switches it per chat.
     */
    fun resolve(pref: ModelChoice?, opts: ModelOptions?): ModelChoice? {
        if (pref != null && pref != AUTO) return pref
        if (opts == null) return startModel(null)
        val sub = opts.providers.firstOrNull { it.slug == PROVIDER }
        val auto = sub?.let { ModelChoice(PROVIDER, bestSonnet(it.models) ?: MODEL) }
            ?: opts.model?.takeIf { it.isNotBlank() }?.let { m -> ModelChoice(opts.provider.orEmpty(), m) }
        return auto.also { lastAuto = it }
    }

    /** [MODEL] when listed, else the newest Sonnet id listed. */
    fun bestSonnet(ids: List<String>): String? =
        MODEL.takeIf { it in ids } ?: ids.filter { "sonnet" in it }.maxWithOrNull(compareBy { id -> Regex("""\d+""").findAll(id).map { it.value.padStart(4, '0') }.joinToString("") })

    /** A brain session that isn't the brain's Bot Chat. [sessionId] null = a fresh chat not created yet. */
    fun isQuick(profile: String, sessionId: String?, bots: List<Bot>): Boolean {
        val brain = bots.firstOrNull { it.isDefault } ?: return false
        return profile == brain.profile && (sessionId == null || sessionId != brain.botChatId)
    }

    fun isQuick(row: SessionRow, bots: List<Bot>): Boolean = row.title != Hermes.BOT_CHAT && isQuick(row.profile, row.id, bots)
}
