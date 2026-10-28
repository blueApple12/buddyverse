package app.buddyverse.ui.more.files

import app.buddyverse.core.api.FileEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilesLogicTest {
    @Test fun crumbsWalkWindowsAndPosixPaths() {
        assertEquals(listOf("C:\\" to "C:\\", "Users" to "C:\\Users", "alex" to "C:\\Users\\alex"), crumbs("C:\\Users\\alex"))
        assertEquals(listOf("/" to "/", "home" to "/home", "u" to "/home/u"), crumbs("/home/u"))
        assertEquals("C:\\Users\\a.txt", join("C:\\Users", "a.txt"))
        assertEquals("/home/a.txt", join("/home/", "a.txt"))
    }

    @Test fun namesStayValidAndUnique() {
        assertTrue(validName("notes.md"))
        assertFalse(validName("a/b"))
        assertFalse(validName(".."))
        assertEquals("a (2).txt", uniqueName("a.txt", setOf("a.txt", "a (1).txt")))
        assertEquals("b.txt", uniqueName("b.txt", setOf("a.txt")))
    }

    @Test fun sizesAndKinds() {
        assertEquals("512 B", sizeText(512))
        assertEquals("1.5 KB", sizeText(1536))
        assertEquals("4 MB", sizeText(4L * 1024 * 1024))
        assertEquals(Kind.Image, FileEntry("a.PNG", "/a.PNG", false).kind)
        assertEquals(Kind.Code, FileEntry("b.kt", "/b.kt", false).kind)
        assertEquals(Kind.Folder, FileEntry("src", "/src", true).kind)
        assertTrue(FileEntry(".git", "/.git", true).hiddenish)
    }
}
