package app.buddyverse.ui.more.kanban

import app.buddyverse.core.WireJson
import app.buddyverse.core.obj
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KanbanLogicTest {
    @Test fun slugsAreValidAndUnique() {
        assertEquals("my-big-board", slugOf("  My Big  Board! ", emptySet()))
        assertEquals("board", slugOf("לוח בית", emptySet()))
        assertEquals("board-3", slugOf("לוח", setOf("board", "board-2")))
        assertEquals("default-2", slugOf("Default", setOf("default")))
        assertTrue(Regex("^[a-z0-9][a-z0-9\\-_]{0,63}$").matches(slugOf("_-x".repeat(40), emptySet())))
    }

    @Test fun oneTapMovesAreAllowedMoves() {
        for (s in listOf("triage", "todo", "scheduled", "ready", "running", "blocked", "review", "done")) {
            val to = primaryMove(s) ?: continue
            assertTrue(to in movesFrom(s), "$s -> $to")
        }
        assertEquals("todo", primaryMove("running"))
        assertTrue("running" !in movesFrom("ready"))
        assertNull(primaryMove("archived"))
    }

    @Test fun parsesBoardCardAndDetail() {
        val card = KTask.from(WireJson.parseToJsonElement(
            """{"id":"t_1","title":"Fix","status":"running","priority":2,"assignee":"","consecutive_failures":1,
               "progress":{"done":1,"total":3},"comment_count":4,"current_run_started_at":100,"warnings":{"count":2}}""",
        ).obj!!)!!
        assertNull(card.assignee)
        assertEquals(listOf<Any?>(1, 3, 4, 100L, 2), listOf<Any?>(card.doneChildren, card.totalChildren, card.comments, card.runStartedAt, card.warnings))
        val d = KDetail.from(WireJson.parseToJsonElement(
            """{"task":{"id":"t_1","body":"b"},"comments":[{"author":"user","body":"hi","created_at":5}],
               "events":[{"id":1,"kind":"created"},{"id":2,"kind":"spawned"}],"links":{"parents":["t_0"],"children":[]},
               "link_tasks":[{"id":"t_0","title":"Plan","status":"done"}],"runs":[{"id":7,"status":"done","error":"boom","started_at":1}]}""",
        ).obj)!!
        assertEquals("spawned", d.events.first().kind)
        assertEquals(KLink("t_0", "Plan", "done"), d.parents.single())
        assertTrue(d.runs.single().failed)
    }
}
