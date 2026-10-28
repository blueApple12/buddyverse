package app.buddyverse

import app.buddyverse.design.DesktopBack
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopBackTest {
    private val log = mutableListOf<String>()
    private fun add(name: String, on: Boolean) = DesktopBack.Handler(on) { log += name }.also { DesktopBack.handlers += it }

    @AfterTest fun clear() = DesktopBack.handlers.clear()

    @Test fun escClosesWhatOpenedLast() {
        val sheet = add("sheet", false) // composed first (closed), like a sheet under the Connect screen
        val tab = add("tab", false)     // the tab handler, composed later
        DesktopBack.setEnabled(tab, true)
        DesktopBack.setEnabled(sheet, true) // the sheet opens: it goes on top
        assertTrue(DesktopBack.back())
        assertEquals(listOf("sheet"), log)
        DesktopBack.setEnabled(sheet, false)
        DesktopBack.back()
        assertEquals(listOf("sheet", "tab"), log)
    }

    @Test fun nothingEnabledIsNoBack() {
        add("off", false)
        assertFalse(DesktopBack.back())
    }
}
