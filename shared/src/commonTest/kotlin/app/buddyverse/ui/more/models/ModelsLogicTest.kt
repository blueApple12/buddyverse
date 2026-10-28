package app.buddyverse.ui.more.models

import app.buddyverse.core.WireJson
import app.buddyverse.core.api.ProviderRow
import app.buddyverse.core.api.modelTokens
import app.buddyverse.core.api.modelsDebrand
import app.buddyverse.core.obj
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelsLogicTest {
    @Test fun tokensReadShort() {
        assertEquals("256K", modelTokens(262_144))
        assertEquals("1M", modelTokens(1_000_000))
        assertEquals("200K", modelTokens(200_000))
        assertEquals("", modelTokens(0))
    }

    @Test fun noEngineOrVendorNamesReachTheUi() {
        assertEquals("the engine signs in with the portal", modelsDebrand("Hermes Agent signs in with Nous Portal"))
    }

    @Test fun providerRowsParse() {
        val row = ProviderRow.from(WireJson.parseToJsonElement(
            """{"slug":"openrouter","name":"OpenRouter","authenticated":true,"models":["a","b"],"capabilities":{"a":{"fast":true}},
               "pricing":{"a":{"input":"$1","output":"$2"},"b":{"free":true}},"usage":{"windows":[{"label":"Week","used_percent":140}]}}""",
        ).obj)!!
        assertEquals(setOf("a"), row.fast)
        assertEquals("$1 · $2", row.prices["a"])
        assertEquals("free", row.prices["b"])
        assertEquals(100, row.usage.single().usedPercent)
    }
}
