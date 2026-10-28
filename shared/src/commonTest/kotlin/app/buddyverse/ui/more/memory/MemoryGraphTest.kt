package app.buddyverse.ui.more.memory

import app.buddyverse.core.WireJson
import app.buddyverse.core.obj
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryGraphTest {
    // Shape of a live `GET /api/learning/graph` (2026-10-26): node ids carry index + fingerprint; cards are MEMORY.md then USER.md.
    private val graph = WireJson.parseToJsonElement(
        """
        {"nodes":[
          {"id":"memory:memory:0:aaa","label":"Old note…","kind":"memory","memorySource":"memory","timestamp":100},
          {"id":"memory:memory:1:bbb","label":"New note","kind":"memory","memorySource":"memory","timestamp":200},
          {"id":"memory:profile:2:ccc","label":"Likes rounded","kind":"memory","memorySource":"profile","timestamp":150},
          {"id":"memory:profile:3:zzz","label":"Shifted","kind":"memory","memorySource":"profile"},
          {"id":"tidy-desk","label":"tidy-desk","kind":"skill","category":"ops","useCount":3,"pinned":true,"timestamp":300}],
         "memory":[
          {"source":"memory","title":"Old note…","body":"Old note, full text","fingerprint":"aaa"},
          {"source":"memory","title":"New note","body":"New note","fingerprint":"bbb"},
          {"source":"profile","title":"Likes rounded","body":"Likes rounded fonts","fingerprint":"ccc"},
          {"source":"profile","title":"x","body":"Found by fingerprint","fingerprint":"yyy"},
          {"source":"profile","title":"x","body":"Shifted entry","fingerprint":"zzz"}]}
        """,
    ).obj

    @Test
    fun factsJoinCardsNewestFirst() {
        val (facts, skills) = parseGraph(graph)
        assertEquals(listOf("memory:profile:3:zzz", "memory:profile:2:ccc", "memory:memory:1:bbb", "memory:memory:0:aaa"), facts.map { it.id })
        assertEquals("Old note, full text", facts.last().text)
        // Index 3 points at another entry; the fingerprint finds the right one.
        assertEquals("Shifted entry", facts.first().text)
        assertEquals(listOf("profile", "profile", "memory", "memory"), facts.map { it.source })
        assertEquals(1, skills.size)
        assertEquals(3L, skills[0].uses)
        assertTrue(skills[0].pinned)
    }

    @Test
    fun providersParse() {
        val st = WireJson.parseToJsonElement(
            """{"active":"","providers":[{"name":"byterover","description":"d","status":"unavailable",
               "setup":{"pip_dependencies":[],"external_dependencies":[{"name":"brv","install":"curl x | sh","check":"brv --version"}],
               "required_env":[],"dependencies_installed":false}},
              {"name":"retaindb","status":"needs_config","setup":{"required_env":["RETAINDB_API_KEY"],"dependencies_installed":true}}]}""",
        ).obj
        val ps = parseProviders(st)
        assertEquals(listOf("byterover", "retaindb"), ps.map { it.name })
        assertTrue(ps[0].depsMissing)
        assertEquals(Dep("brv", "curl x | sh"), ps[0].deps.single())
        assertEquals(listOf("RETAINDB_API_KEY"), ps[1].env)
    }
}
