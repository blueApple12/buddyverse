package app.buddyverse.core

import app.buddyverse.core.api.ModelOptions
import app.buddyverse.core.api.ModelProvider
import app.buddyverse.core.api.openRouterFreeIds
import app.buddyverse.core.api.withFreeOpenRouter
import kotlin.test.Test
import kotlin.test.assertEquals

class FreeModelsTest {
    private val sample = """
        {"data":[
          {"id":"anthropic/claude-x","pricing":{"prompt":"0.000003","completion":"0.000015"},"architecture":{"output_modalities":["text"]}},
          {"id":"nvidia/nemotron-3.5-lightning:free","pricing":{"prompt":"0","completion":"0"},"architecture":{"output_modalities":["text"]}},
          {"id":"half/free-in:free","pricing":{"prompt":"0","completion":"0.000001"},"architecture":{"output_modalities":["text"]}},
          {"id":"google/lyria-3-pro-preview","pricing":{"prompt":"0","completion":"0"},"architecture":{"output_modalities":["text","audio"]}},
          {"id":"google/lyria-9","pricing":{"prompt":"0","completion":"0"},"architecture":{"output_modalities":["text"]}},
          {"id":"some/image-gen","pricing":{"prompt":"0","completion":"0"},"architecture":{"output_modalities":["image"]}},
          {"id":"nvidia/nemotron-3.5-content-safety:free","pricing":{"prompt":"0","completion":"0"},"architecture":{"output_modalities":["text"]}},
          {"id":"google/gemma-4-31b-it:free","pricing":{"prompt":"0.0","completion":"0"}},
          {"id":"openrouter/free","pricing":{"prompt":"0","completion":"0"},"architecture":{"output_modalities":["text"]}},
          {"id":"broken/no-price"}
        ]}
    """.trimIndent()

    @Test
    fun keepsOnlyFreeChatModelsRouterFirst() {
        assertEquals(
            listOf("openrouter/free", "nvidia/nemotron-3.5-lightning:free", "google/gemma-4-31b-it:free"),
            openRouterFreeIds(sample),
        )
    }

    private val opts = ModelOptions(listOf(
        ModelProvider("anthropic", "Anthropic", true, listOf("claude-a"), listOf("claude-a"), null),
        ModelProvider("openrouter", "OpenRouter", false, listOf("x/paid", "y/model:free"), listOf("x/paid"), null),
    ), "claude-a", "anthropic")

    @Test
    fun replacesOpenRouterListAndFallsBackToFreeSuffix() {
        val live = opts.withFreeOpenRouter(listOf("openrouter/free", "a/b:free"))
        assertEquals(listOf("openrouter/free", "a/b:free"), live.providers[1].models)
        assertEquals(listOf("claude-a"), live.providers[0].models)
        assertEquals(listOf("y/model:free"), opts.withFreeOpenRouter(null).providers[1].models)
    }
}
