package app.buddyverse.ui.more.tools

import app.buddyverse.i18n.APP_NAME
import kotlin.test.Test
import kotlin.test.assertEquals

/** Real provider/toolset strings from the live gateway (2026-10-26). */
class BrandFreeTest {
    @Test fun vendorOnlyTextVanishes() = assertEquals("", brandFree("Nous Subscription"))

    @Test fun vendorClauseIsDropped() {
        assertEquals("Full search + extract; supports keyless cloud, direct API",
            brandFree("Full search + extract; supports keyless cloud, direct API, and Nous tool-gateway routing."))
        assertEquals("Krea 3 with moodboards.", brandFree("Krea 3 with moodboards. Direct key or managed Nous Subscription gateway."))
    }

    @Test fun engineNameBecomesOurs() {
        assertEquals("Zig headless browser spawned by $APP_NAME, text-only", brandFree("Zig headless browser spawned by Hermes, text-only"))
        assertEquals("A2A support for $APP_NAME — both directions", brandFree("A2A support for Hermes Agent — both directions"))
    }

    @Test fun plainTextAndPathsUntouched() {
        assertEquals("Headless Chromium, no API key needed", brandFree("Headless Chromium, no API key needed"))
        assertEquals("C:\\Users\\a\\AppData\\Local\\hermes\\tools", brandFree("C:\\Users\\a\\AppData\\Local\\hermes\\tools"))
    }
}
