package app.buddyverse.ui.project

import app.buddyverse.core.Room
import app.buddyverse.core.api.Project
import app.buddyverse.core.api.livingPageNames
import app.buddyverse.core.api.pageSections
import app.buddyverse.core.api.projectKey
import app.buddyverse.core.api.projectRoom
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectLogicTest {
    private fun p(name: String, path: String?, slug: String? = null) = Project("default", "id", name, null, null, path, emptyList(), false, 0.0, slug)

    @Test fun sameFolderIsTheSameProject() {
        assertEquals(projectKey(p("Mods", "C:\\code\\Mods\\")), projectKey(p("mods (copy)", "c:\\code\\mods")))
        assertEquals("name:study app", projectKey(p(" Study App ", null)))
    }

    @Test fun pageNamesFollowTheVaultRule() {
        assertEquals(listOf("second-brain"), livingPageNames(p("Second Brain", null)))
        assertEquals(listOf("study-app", "study app/x".replace(" ", "-").replace("/", "-")), livingPageNames(p("study app/x", null, "study-app")))
    }

    @Test fun pageSectionsSkipFrontmatter() {
        val md = "---\ntitle: x\n---\n# Title\n\n## Now\n- build the room\n\n## Next\n- ship\n## Done\nold\n"
        val s = pageSections(md)
        assertEquals(listOf("Now", "Next", "Done"), s.map { it.first })
        assertEquals("- build the room", s[0].second)
    }

    @Test fun roomIsFoundByName() {
        val rooms = listOf(Room("default", "r2", "Mods", listOf("a", "b"), 2.0), Room("default", "r1", "mods", listOf("a"), 1.0))
        assertEquals("r2", projectRoom(rooms, p(" mods", null))?.roomId)
    }
}
