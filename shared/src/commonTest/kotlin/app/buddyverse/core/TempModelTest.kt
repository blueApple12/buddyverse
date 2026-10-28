package app.buddyverse.core

import app.buddyverse.core.api.ModelOptions
import app.buddyverse.core.api.ModelProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TempModelTest {
    private val sonnet = ModelChoice("claude-subscription-directsdk-experimental", "claude-sonnet-5-5[1m]")
    private fun prov(slug: String, vararg models: String) = ModelProvider(slug, slug, false, models.toList(), emptyList(), null)
    private val sub = prov("claude-subscription-directsdk-experimental", "claude-opus-5-5[1m]", "claude-sonnet-5-5[1m]")
    private val openrouter = prov("openrouter", "openrouter/free", "google/gemini-3-pro")

    @Test fun ownerPickWins() {
        val pick = ModelChoice("openrouter", "openrouter/free")
        assertEquals(pick, TempChats.resolve(pick, ModelOptions(listOf(sub), "x", "y")))
        assertEquals(pick, TempChats.startModel(pick))
    }

    @Test fun automaticIsSubscriptionSonnetWhenSignedIn() {
        assertEquals(sonnet, TempChats.resolve(null, ModelOptions(listOf(openrouter, sub), "openrouter/free", "openrouter")))
        assertEquals(sonnet, TempChats.resolve(TempChats.AUTO, ModelOptions(listOf(sub), null, null)))
        // Signed in but the list lacks 5.5: the newest Sonnet it has.
        assertEquals(ModelChoice(sub.slug, "claude-sonnet-4-6"),
            TempChats.resolve(null, ModelOptions(listOf(prov(sub.slug, "claude-sonnet-4-5", "claude-sonnet-4-6", "claude-opus-4-6")), null, null)))
    }

    @Test fun automaticElseEngineDefault() {
        // A stranger without the Claude subscription: the engine's own default, never a provider they don't have.
        assertEquals(ModelChoice("openrouter", "openrouter/free"), TempChats.resolve(null, ModelOptions(listOf(openrouter), "openrouter/free", "openrouter")))
        assertEquals(ModelChoice("openrouter", "openrouter/free"), TempChats.startModel(null)) // remembered for the next fresh chat
        // No default model at all: null = the engine decides.
        assertNull(TempChats.resolve(null, ModelOptions(emptyList(), null, null)))
        assertNull(TempChats.resolve(null, ModelOptions(listOf(openrouter), "", "openrouter")))
    }

    @Test fun bestSonnet() {
        assertEquals("claude-sonnet-5-5[1m]", TempChats.bestSonnet(listOf("claude-sonnet-4-6", "claude-sonnet-5-5[1m]")))
        assertEquals("claude-sonnet-4-6", TempChats.bestSonnet(listOf("claude-sonnet-4-5", "claude-sonnet-4-6")))
        assertNull(TempChats.bestSonnet(listOf("claude-opus-4-6")))
    }

    @Test fun prefsWithoutTempModelStillDecode() {
        val p = WireJson.decodeFromString(Prefs.serializer(), """{"sounds":false}""")
        assertNull(p.tempModel)
        val q = p.copy(tempModel = ModelChoice("openrouter", "openrouter/free"))
        assertEquals(q, WireJson.decodeFromString(Prefs.serializer(), WireJson.encodeToString(Prefs.serializer(), q)))
    }
}
