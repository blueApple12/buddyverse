package app.buddyverse.ui.more.projects

import app.buddyverse.buddy.BuddyColors
import app.buddyverse.core.WireJson
import app.buddyverse.core.api.Project
import app.buddyverse.core.obj
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectsModelTest {
    private fun p(id: String, archived: Boolean = false, at: Double = 0.0, path: String? = null) =
        Project("default", id, "", null, null, path, listOfNotNull(path), archived, at)

    @Test fun rowsHonorLocalIntentsAndSortNewestFirst() {
        val all = listOf(p("a", at = 1.0), p("b", at = 3.0), p("c", archived = true, at = 2.0), p("d", at = 4.0))
        // b archived just now, c restored just now, d deleted.
        val active = rowsOf(all, ProjectFilter.Active, mapOf("default/b" to true, "default/c" to false), setOf("default/d"))
        assertEquals(listOf("c", "a"), active.map { it.id })
        assertEquals(listOf("b"), rowsOf(all, ProjectFilter.Archived, mapOf("default/b" to true, "default/c" to false), emptySet()).map { it.id })
    }

    @Test fun pathsAndTitles() {
        assertEquals("mods", folderName("C:\\code\\mods\\"))
        assertEquals("app", folderName("/home/u/app"))
        assertTrue(samePath("C:\\Code\\Mods\\", "c:/code/mods"))
        assertEquals("mods", p("x", path = "C:\\code\\mods").title)
        assertEquals("x", p("x").title)
    }

    @Test fun hexRoundTripsThePalette() {
        BuddyColors.palette.forEach { assertEquals(it, BuddyColors.parse(hex(it))) }
        assertEquals("#1084fe", hex(BuddyColors.palette[7]))
    }

    @Test fun payloadKeepsArchivedAndActive() {
        val o = WireJson.parseToJsonElement(
            """{"projects":[{"id":"p_1","name":"mods","primary_path":"C:\\m","archived":true,"created_at":5,"folders":[{"path":"C:\\m","is_primary":true}]}],"active_id":"p_1"}""",
        ).obj
        val r = payloadOf("brain", o)
        assertEquals("p_1", r.activeId)
        assertEquals(listOf("C:\\m"), r.projects.single().folders)
        assertTrue(r.projects.single().archived)
    }
}
