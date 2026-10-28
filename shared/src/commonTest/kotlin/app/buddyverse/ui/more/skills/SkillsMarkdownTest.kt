package app.buddyverse.ui.more.skills

import kotlin.test.Test
import kotlin.test.assertEquals

class SkillsMarkdownTest {
    @Test
    fun bodyDropsFrontmatter() {
        assertEquals("Do the thing.", skillBody("---\nname: a\ndescription: \"x\"\n---\n\nDo the thing.\n"))
        assertEquals("No frontmatter", skillBody("  No frontmatter  "))
        assertEquals("---\nbroken", skillBody("---\nbroken"))
    }

    @Test
    fun newSkillRoundTrips() {
        val md = skillMarkdown("my-skill", "Says \"hi\"\nloudly", "Be loud.")
        assertEquals("---\nname: my-skill\ndescription: \"Says \\\"hi\\\" loudly\"\n---\n\nBe loud.\n", md)
        assertEquals("Be loud.", skillBody(md))
    }

    @Test
    fun namesAndBrand() {
        assertEquals("my-skill_2.0", cleanSkillName("My Skill_2.0!"))
        assertEquals("run engine -p default skills install", unbrand("run hermes -p default skills install"))
        assertEquals("engine and engine", unbrand("Hermes Agent and Nous Research"))
    }
}
