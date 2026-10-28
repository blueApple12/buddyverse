package app.buddyverse.ui.more.artifacts

import app.buddyverse.core.WireJson
import app.buddyverse.core.api.artifactFilePath
import app.buddyverse.core.obj
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtifactScanTest {
    private val session = ScanSession("s1", "default", "Sales chart", 1_767_225_600_000, null)

    private fun scan(vararg rows: String): List<Artifact> {
        val into = LinkedHashMap<String, Artifact>()
        scanPage(session, rows.map { WireJson.parseToJsonElement(it).obj!! }, into, 0)
        return into.values.toList()
    }

    @Test fun repliesYieldImagesFilesAndLinks() {
        val found = scan(
            """{"role":"assistant","timestamp":1767225600,"content":"Done: ![chart](/home/u/out/chart.png) and C:\\out\\report.pdf, see https://example.com/docs. MEDIA:/tmp/song.mp3."}""",
            """{"role":"user","content":"/home/u/secret.png"}""",
        )
        val byValue = found.associateBy { it.value }
        assertEquals(ArtifactKind.Image, byValue.getValue("/home/u/out/chart.png").kind)
        assertEquals(ArtifactKind.File, byValue.getValue("C:\\out\\report.pdf").kind)
        assertEquals(ArtifactKind.Link, byValue.getValue("https://example.com/docs").kind)
        assertEquals(ArtifactKind.File, byValue.getValue("/tmp/song.mp3").kind)
        assertEquals("chart.png", byValue.getValue("/home/u/out/chart.png").label)
        assertTrue("/home/u/secret.png" !in byValue) // user rows never count
        assertEquals(1_767_225_600_000, found.first().timestampMs)
    }

    @Test fun toolResultsCountOnlyUnderArtifactKeys() {
        val found = scan(
            """{"role":"tool","tool_name":"write_file","content":"{\"path\":\"/w/notes.md\",\"bytes\":12}"}""",
            """{"role":"tool","tool_name":"read_file","content":"{\"path\":\"/w/other.md\"}"}""",
            """{"role":"tool","tool_name":"terminal","content":"{\"output\":\"Downloading https://files.pythonhosted.org/packages/x/pkg-1.0-py3-none-any.whl\\nwrote /w/plot.png\"}"}""",
        ).map { it.value }
        assertTrue("/w/notes.md" in found) // a producer tool's path
        assertTrue("/w/other.md" !in found) // a reader's path is not something it made
        assertTrue("/w/plot.png" in found) // shell output is scanned like prose
        assertTrue(found.none { it.endsWith(".whl") }) // pip noise
    }

    @Test fun longFencesBecomeMadeArtifacts() {
        val page = "<!doctype html><html><head><title>Budget</title></head><body>" + "x".repeat(200) + "</body></html>"
        val reply = "Here:\n```html\n$page\n```\nand a short one:\n```kotlin\nval x = 1\n```"
        val made = scan(WireJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject { put("role", kotlinx.serialization.json.JsonPrimitive("assistant")); put("content", kotlinx.serialization.json.JsonPrimitive(reply)) }))
            .filter { it.kind == ArtifactKind.Made }
        assertEquals(1, made.size)
        assertEquals(MadeKind.Html, made.single().made)
        assertEquals("Budget", made.single().label)
        assertEquals("budget.html", made.single().fileName())
    }

    @Test fun mediaTagsAndPaths() {
        assertEquals(listOf("/tmp/a.pdf"), mediaTagValues("open MEDIA:/tmp/a.pdf."))
        assertEquals("/x/y.png", normalizeValue(" \"`/x/y.png`\" "))
        assertEquals("/x/y.png", normalizeValue("/x/y.png)."))
        assertEquals("C:\\out\\a b.png", artifactFilePath("file:///C:/out/a%20b.png"))
        assertEquals("/home/u/a.png", artifactFilePath("file:///home/u/a.png"))
    }
}
