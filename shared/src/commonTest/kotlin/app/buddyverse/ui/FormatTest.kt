package app.buddyverse.ui

import app.buddyverse.i18n.EnglishStrings
import app.buddyverse.i18n.HebrewStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormatTest {
    @Test
    fun modelIdsReadAsPeopleSayThem() {
        assertEquals("Opus 5.5 · 1M", modelLabel("claude-opus-5-5[1m]"))
        assertEquals("Opus 5.5", modelLabel("anthropic/claude-opus-5-5"))
        assertEquals("Sonnet 4.6", modelLabel("anthropic/claude-sonnet-4.6"))
        assertEquals("Sonnet 4.5", modelLabel("claude-sonnet-4-5-20250929"))
        assertEquals("Opus 4", modelLabel("claude-opus-4-20250514"))
        assertEquals("Sonnet 3.5", modelLabel("claude-3-5-sonnet-20241022"))
        assertEquals("Opus 3", modelLabel("claude-3-opus-20240229"))
        assertEquals("Fable 5.1 · 1M", modelLabel("claude-fable-5-1[1m]"))
        assertEquals("gpt-5", modelLabel("openai/gpt-5"))
        assertEquals("gpt-4o · 128K", modelLabel("gpt-4o[128k]"))
        assertNull(modelLabel(" "))
        assertNull(modelLabel(null))
    }

    @Test
    fun relativeTimesUseTheUiLanguage() {
        val now = 1_000_000_000_000L
        val elevenHours = now / 1000.0 - 11 * 3600
        assertEquals("11h", ago(elevenHours, now, EnglishStrings))
        assertEquals("11 שע׳", ago(elevenHours, now, HebrewStrings))
        assertEquals("עכשיו", ago(now / 1000.0, now, HebrewStrings))
    }
}
