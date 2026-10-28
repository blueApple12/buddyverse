package app.buddyverse.ui.home

import androidx.compose.ui.graphics.Color
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.core.Bot
import app.buddyverse.core.BotStatus
import app.buddyverse.core.ChatTarget
import app.buddyverse.core.Room
import app.buddyverse.core.api.Project
import app.buddyverse.core.chat.BotItem
import app.buddyverse.core.chat.ChatState
import app.buddyverse.core.chat.Prompt
import app.buddyverse.core.chat.PromptKind
import app.buddyverse.core.chat.Step
import app.buddyverse.core.chat.StepStatus
import app.buddyverse.core.chat.WorkItem
import app.buddyverse.i18n.EnglishStrings
import app.buddyverse.i18n.HomeStrings
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeModelTest {
    private fun bot(p: String, default: Boolean = false, last: Double = 0.0, status: BotStatus = BotStatus.Idle, pinned: Boolean = false) =
        Bot(p, p, "", default, BuddySpec(p, p, BuddyShape.Blob, Color.Red), null, "chat-$p", "hi", last, status, pinned, false)

    private fun project(p: String, id: String, path: String?, archived: Boolean = false) =
        Project(p, id, id, null, null, path, emptyList(), archived, 0.0)

    /** A shared engine reports the same helper for every profile: it shows once, on the bot that owns its session. */
    @Test fun helpersGoToTheirOwnerOnce() {
        val sa = app.buddyverse.core.api.LiveSubagent("sa1", "ping", "chat-apptest")
        val loose = app.buddyverse.core.api.LiveSubagent("sa2", null, null)
        val raw = mapOf("default" to listOf(sa, loose), "apptest" to listOf(sa, loose), "secondbrain" to listOf(sa, loose))
        val out = attributeHelpers(raw, mapOf("chat-apptest" to "apptest"), "default")
        assertEquals(listOf("sa1"), out["apptest"]?.map { it.id })
        assertEquals(listOf("sa2"), out["default"]?.map { it.id })
        assertEquals(null, out["secondbrain"])
        // Only one profile reports it: it stays there.
        assertEquals(listOf("sa2"), attributeHelpers(mapOf("x" to listOf(loose), "y" to emptyList()), emptyMap(), "y")["x"]?.map { it.id })
    }

    @Test fun rosterOrder() {
        val bots = listOf(bot("old", last = 1.0), bot("brain", default = true), bot("pin", pinned = true), bot("ask", status = BotStatus.NeedsYou), bot("new", last = 9.0))
        val room = Room("default", "r1", "team", listOf("old", "new"), 5.0)
        assertEquals(listOf("bot:brain", "bot:pin", "bot:ask", "bot:new", "room:r1", "bot:old"), rosterOf(bots, listOf(room)).map { it.key })
    }

    @Test fun localPinResorts() {
        val bots = listOf(bot("brain", default = true), bot("a", last = 9.0), bot("b", last = 1.0))
        assertEquals(listOf("brain", "b", "a"), applyLocal(bots, mapOf("b" to true), emptyMap(), emptyMap()).map { it.profile })
        assertEquals(listOf("brain", "b"), applyLocal(bots, emptyMap(), emptyMap(), mapOf("a" to true)).map { it.profile })
    }

    /** A project is just an agent: nested folders make sub-agents, everything else hangs on the brain. */
    @Test fun agentParentsNestByFolder() {
        val bots = listOf(bot("default", default = true), bot("study"), bot("studyapp"), bot("tutor"), bot("secondbrain"), bot("loose"))
        // The target data: each bot holds only its own project.
        val tidy = mapOf(
            "default" to listOf(project("default", "p0", "C:\\Users\\alex")),
            "study" to listOf(project("study", "s", "C:\\Users\\alex\\study")),
            "studyapp" to listOf(project("studyapp", "a", "C:\\Users\\alex\\study\\app")),
            "tutor" to listOf(project("tutor", "t", "C:\\Users\\alex\\study\\tutor")),
            "secondbrain" to listOf(project("secondbrain", "sb", "C:\\Users\\alex\\buddyverse"), project("secondbrain", "old", "C:\\Users", archived = true)),
        )
        assertEquals(
            mapOf("study" to "default", "studyapp" to "study", "tutor" to "study", "secondbrain" to "default", "loose" to "default"),
            agentParents(bots, tidy),
        )
        // The live PC today: bots hold extra projects; all three share the shallowest home, so none nests under another.
        val messy = mapOf(
            "study" to listOf(project("study", "s", "C:\\Users\\alex\\study"), project("study", "a", "C:\\Users\\alex\\study\\study-app"), project("study", "t", "C:\\Users\\alex\\study\\tutor")),
            "studyapp" to listOf(project("studyapp", "a", "C:\\Users\\alex\\study\\study-app"), project("studyapp", "s", "C:\\Users\\alex\\study")),
            "tutor" to listOf(project("tutor", "s", "C:\\Users\\alex\\study"), project("tutor", "t", "C:\\Users\\alex\\study\\tutor")),
            "secondbrain" to listOf(project("secondbrain", "sb", "C:\\Users\\alex\\buddyverse")),
        )
        assertEquals(setOf("default"), agentParents(bots, messy).values.toSet())
        // Case, `/` vs `\`, trailing separators; a sibling prefix ("studyx") is not a child; deeper nesting recurses.
        val mixed = mapOf(
            "study" to listOf(project("study", "s", "c:/users/ALEX/Study/")),
            "studyapp" to listOf(project("studyapp", "a", "C:\\Users\\alex\\study\\app\\")),
            "tutor" to listOf(project("tutor", "t", "C:/Users/alex/study/app/tutor")),
            "loose" to listOf(project("loose", "x", "C:\\Users\\alex\\studyx")),
        )
        assertEquals(
            mapOf("study" to "default", "studyapp" to "study", "tutor" to "studyapp", "secondbrain" to "default", "loose" to "default"),
            agentParents(bots, mixed),
        )
    }

    @Test fun stageWithoutParentsPutsBotsOnTheRing() {
        val bots = listOf(bot("brain", default = true), bot("a"), bot("b"))
        val g = stageGraph(bots, emptyMap(), 360f)
        assertEquals(NodeKind.Brain, g.nodes[0].kind)
        assertEquals(3, g.nodes.size)
        val brain = g.nodes[0]
        for (n in g.nodes.drop(1)) {
            assertEquals(0, n.via)
            assertTrue(hypot(n.x - brain.x, n.y - brain.y) >= (brain.size + n.size) / 2f, "${n.key} overlaps the brain")
        }
    }

    /** brain → study → (studyapp, tutor), brain → secondbrain: no project nodes, each agent links to its parent, sizes by depth. */
    @Test fun stageIsATreeOfAgents() {
        val bots = listOf(bot("default", default = true), bot("study"), bot("studyapp"), bot("tutor"), bot("secondbrain"))
        val g = stageGraph(bots, mapOf("study" to "default", "studyapp" to "study", "tutor" to "study", "secondbrain" to "default"), 360f).nodes
        assertEquals(5, g.size)
        assertEquals(listOf(NodeKind.Brain) + List(4) { NodeKind.Agent }, g.map { it.kind })
        fun at(p: String) = g.indexOfFirst { it.key == "bot:$p" }
        assertEquals(listOf(0, 0, at("study"), at("study")), listOf("study", "secondbrain", "studyapp", "tutor").map { g[at(it)].via })
        assertEquals(listOf(52f, 52f, 40f, 40f), listOf("study", "secondbrain", "studyapp", "tutor").map { g[at(it)].size })
        // Children sit beyond their parent, away from the brain.
        val brain = g[0]
        for (c in listOf("studyapp", "tutor")) {
            val n = g[at(c)]; val p = g[n.via]
            assertTrue(hypot(n.x - brain.x, n.y - brain.y) > hypot(p.x - brain.x, p.y - brain.y), "$c is not beyond study")
        }
        // A parent missing from the stage (hidden) leaves its agent on the brain; deeper agents are 34.
        val deep = stageGraph(bots.dropLast(1) + bot("x"), mapOf("studyapp" to "study", "tutor" to "studyapp", "x" to "gone"), 360f).nodes
        assertEquals(0, deep.first { it.key == "bot:x" }.via)
        assertEquals(34f, deep.first { it.key == "bot:tutor" }.size)
    }

    @Test fun helpersOpenWhereTheyRunAndGroupsShowTheBusiestMember() {
        val b = bot("a")
        assertEquals(ChatTarget("a"), helperTarget(b, null))
        assertEquals(ChatTarget("a"), helperTarget(b, "chat-a"))
        assertEquals(ChatTarget("a", "s9"), helperTarget(b, "s9"))

        val members = listOf(bot("x"), bot("y"), bot("z"))
        val live = mapOf(
            "x" to BotLive(Mood.Working, LineKind.Working, "Read a"),
            "y" to BotLive(Mood.NeedsYou, LineKind.NeedsYou, "asks"),
            "z" to BotLive(Mood.Idle, LineKind.Preview, "hi"),
        )
        assertEquals("y", roomLead(members, live)?.profile)
        assertEquals(null, roomLead(listOf(bot("z")), live))
    }

    @Test fun lineTwoByState() {
        val s = EnglishStrings
        val h = HomeStrings()
        val idle = bot("a", last = 10.0)
        assertEquals(LineKind.Preview, liveOf(idle, emptyList(), s, h, 10.0, 0, 0).kind)
        assertTrue(liveOf(idle, emptyList(), s, h, 5.0, 0, 0).unread)

        val ask = Prompt(PromptKind.Approval, "q1", 1, command = "gradle build")
        val waiting = listOf(ChatTarget("a") to ChatState(profile = "a", prompts = listOf(ask)))
        val l = liveOf(idle, waiting, s, h, null, 0, 0)
        assertEquals(Mood.NeedsYou, l.mood)
        assertEquals("wants to run gradle build", l.text)
        assertEquals(listOf("ask:q1"), asksOf(listOf(idle), waiting).map { it.key })

        val streaming = listOf(ChatTarget("a") to ChatState(profile = "a", running = true, items = listOf(BotItem("k", "x".repeat(80) + "\nend", true, 0))))
        val t = liveOf(idle, streaming, s, h, null, 0, 0)
        assertEquals(LineKind.Streaming, t.kind)
        assertEquals(60, t.text.length)
        assertTrue(t.text.endsWith("end"))

        val work = WorkItem("w", listOf(Step("1", "read_file", "app/build.gradle", StepStatus.Running)), "", 0)
        val w = liveOf(idle, listOf(ChatTarget("a") to ChatState(profile = "a", running = true, items = listOf(work))), s, h, null, 2, 0)
        assertEquals(Mood.Working, w.mood)
        assertEquals("Read build.gradle · 2 helpers", w.text)
    }
    @Test fun stageNamesEveryNodeAndNoTwoNodesOverlap() {
        val many = listOf(bot("brain", default = true)) + (1..10).map { bot("b$it") }
        val cases = listOf(
            listOf(bot("default", default = true), bot("secondbrain")) to emptyMap(),
            // brain + 2 roots + 2 children: the live PC once each bot holds only its own project.
            listOf(bot("default", default = true), bot("study"), bot("studyapp"), bot("tutor"), bot("secondbrain")) to
                mapOf("studyapp" to "study", "tutor" to "study"),
            (listOf(bot("brain", default = true)) + (1..6).map { bot("b$it") }) to emptyMap(),
            many.take(9) to mapOf("b2" to "b1", "b3" to "b1", "b4" to "b3", "b5" to "b3", "b7" to "b6"),
            many to mapOf("b2" to "b1", "b3" to "b1", "b4" to "b1", "b5" to "b1", "b7" to "b6", "b8" to "b7", "b9" to "b8"),
        )
        for ((bots, parents) in cases) {
            val g = stageGraph(bots, parents, 380f)
            val n = g.nodes
            for (a in n) {
                assertTrue(!a.label.isNullOrBlank(), "${a.key} has no name")
                assertTrue(a.x - a.boxWidth / 2 >= -.01f && a.x + a.boxWidth / 2 <= 380.01f && a.y - a.touch / 2 >= -.01f && a.y + a.touch / 2 + a.labelHeight <= g.height + .01f, "${a.key} off stage")
            }
            for (i in n.indices) for (j in i + 1 until n.size) {
                val a = n[i]; val b = n[j]
                val apartX = kotlin.math.abs(a.x - b.x) >= (a.boxWidth + b.boxWidth) / 2
                val apartY = a.y + a.touch / 2 + a.labelHeight <= b.y - b.touch / 2 || b.y + b.touch / 2 + b.labelHeight <= a.y - a.touch / 2
                assertTrue(apartX || apartY, "${a.key} overlaps ${b.key} (${bots.size} bots)")
            }
        }
    }
}
