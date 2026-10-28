package app.buddyverse

import app.buddyverse.core.RpcFrame
import app.buddyverse.core.WireEvent
import app.buddyverse.core.WireJson
import app.buddyverse.core.chat.BotItem
import app.buddyverse.core.chat.ChatState
import app.buddyverse.core.chat.PromptKind
import app.buddyverse.core.chat.SendState
import app.buddyverse.core.chat.StepStatus
import app.buddyverse.core.chat.TurnReducer
import app.buddyverse.core.chat.UserItem
import app.buddyverse.core.chat.WorkItem
import app.buddyverse.core.contract.CanonicalSnapshot
import app.buddyverse.core.decodeFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurnReducerTest {
    private fun ev(json: String): WireEvent = (decodeFrame("""{"jsonrpc":"2.0","method":"event","params":$json}""") as RpcFrame.Event).event
    private fun ChatState.feed(vararg frames: String) = frames.fold(this) { s, f -> TurnReducer.apply(s, ev(f), 1000) }

    /** The canonical turn from hermes-api.md §2.2 "Example: one complete turn". */
    @Test fun canonicalTurnBuildsBubblesAndWork() {
        var s = TurnReducer.optimisticSend(ChatState(sessionId = "S"), "How big is README.md?", "sub1", 0)
        assertEquals(SendState.Sending, (s.items.single() as UserItem).state)
        val st = """"authority_epoch":7,"execution_generation":12,"admission_id":"A","replay_epoch":"E""""
        s = s.feed(
            """{"type":"session.info","session_id":"S","seq":101,"replay_epoch":"E","payload":{"running":false,"execution_generation":11,"revision":30,"pending":[{"admission_id":"A","sequence":14,"status":"queued","input_id":"sub1","text":"How big is README.md?","controls":["steer","replace"]}]}}""",
            """{$st,"type":"message.start","session_id":"S","payload":{},"seq":102}""",
            """{$st,"type":"session.info","session_id":"S","payload":{"running":true,"execution_generation":12,"revision":31,"pending":[{"admission_id":"A","status":"started","input_id":"sub1"}]},"seq":103}""",
            """{$st,"type":"reasoning.delta","session_id":"S","payload":{"text":"Need the file size; use terminal."},"seq":105}""",
            """{$st,"type":"message.delta","session_id":"S","payload":{"text":"Let me check."},"seq":106}""",
            """{$st,"type":"message.delta","session_id":"S","payload":{"text":null},"seq":107}""",
            """{$st,"type":"tool.generating","session_id":"S","payload":{"name":"terminal"},"seq":108}""",
            """{$st,"type":"tool.start","session_id":"S","payload":{"tool_id":"call_9x","name":"terminal","context":"wc -c README.md","args":{"command":"wc -c README.md"}},"seq":109}""",
        )
        assertTrue(s.running)
        val work = s.items.filterIsInstance<WorkItem>().last()
        assertEquals(StepStatus.Running, work.steps.single().status)
        assertEquals("wc -c README.md", work.steps.single().context)
        s = s.feed(
            """{$st,"type":"tool.complete","session_id":"S","payload":{"tool_id":"call_9x","name":"terminal","is_error":false,"result":"{\"output\": \"4812 README.md\", \"exit_code\": 0}","duration_s":0.21},"seq":110}""",
            """{$st,"type":"message.delta","session_id":"S","payload":{"text":"README.md is "},"seq":113}""",
            """{$st,"type":"message.delta","session_id":"S","payload":{"text":"4,812 bytes."},"seq":114}""",
        )
        assertEquals("README.md is 4,812 bytes.", s.streamingText)
        assertEquals(12, s.lastDeltaChars)
        s = s.feed(
            """{$st,"type":"message.complete","session_id":"S","seq":115,"payload":{"text":"README.md is 4,812 bytes.","status":"complete","persisted_turn":{"user_row_id":881,"final_assistant_row_id":884,"submission_id":"sub1"}}}""",
            """{"type":"session.info","session_id":"S","payload":{"running":false,"execution_generation":12,"revision":32,"pending":[]},"seq":116}""",
        )
        assertFalse(s.running)
        val kinds = s.items.map { it::class.simpleName }
        assertEquals(listOf("UserItem", "WorkItem", "BotItem", "WorkItem", "BotItem"), kinds)
        assertEquals(StepStatus.Done, s.items.filterIsInstance<WorkItem>().last().steps.single().status)
        assertEquals("Need the file size; use terminal.", (s.items[1] as WorkItem).reasoning)
        assertEquals(881L, (s.items[0] as UserItem).rowId)
        assertEquals(SendState.Sent, (s.items[0] as UserItem).state)
        assertEquals(884L, (s.items.last() as BotItem).rowId)
        assertFalse((s.items.last() as BotItem).streaming)
        assertEquals(32L, s.revision); assertEquals(12L, s.generation); assertEquals(116L, s.lastSeq)
    }

    @Test fun staleGenerationAndReplayedSeqAreDropped() {
        var s = ChatState(sessionId = "S", generation = 12, lastSeq = 50, replayEpoch = "E")
        s = s.feed("""{"type":"message.delta","session_id":"S","execution_generation":11,"seq":60,"replay_epoch":"E","payload":{"text":"old turn"}}""")
        assertTrue(s.items.isEmpty())
        s = s.feed("""{"type":"message.delta","session_id":"S","execution_generation":12,"seq":49,"replay_epoch":"E","payload":{"text":"replayed"}}""")
        assertTrue(s.items.isEmpty())
        s = s.feed("""{"type":"message.delta","session_id":"S","execution_generation":12,"seq":51,"replay_epoch":"E","payload":{"text":"new"}}""")
        assertEquals("new", s.streamingText)
    }

    @Test fun approvalCardComesAndGoes() {
        var s = ChatState(sessionId = "S")
        s = s.feed("""{"type":"approval.request","session_id":"S","execution_generation":12,"seq":1,"payload":{"kind":"approval","prompt_id":"P","execution_generation":12,"command":"rm -rf build/","description":"recursive delete","choices":["once","deny","session","always"],"timeout_s":300}}""")
        val p = s.prompts.single()
        assertEquals(PromptKind.Approval, p.kind); assertEquals("rm -rf build/", p.command); assertEquals(12L, p.generation)
        assertEquals(listOf("once", "deny", "session", "always"), p.choices)
        s = s.feed("""{"type":"approval.settled","session_id":"S","seq":2,"payload":{"prompt_id":"P","execution_generation":12}}""")
        assertTrue(s.prompts.isEmpty())
    }

    @Test fun clarifyAndSecretRequests() {
        var s = ChatState(sessionId = "S").feed("""{"type":"clarify.request","session_id":"S","seq":1,"payload":{"kind":"clarify","prompt_id":"Q1","execution_generation":3,"question":"Which environment?","choices":["staging","production"],"multi_select":false}}""")
        assertEquals("Which environment?", s.prompts.single().question)
        val req = (decodeFrame("""{"jsonrpc":"2.0","id":"srq-9","method":"secret","params":{"session_id":"S","env_var":"API_KEY","prompt":"Paste the key"}}""") as RpcFrame.Request).request
        s = TurnReducer.serverRequest(s, req)
        assertEquals(PromptKind.Secret, s.prompts.last().kind)
        s = s.feed("""{"type":"request.cancel","session_id":"S","seq":2,"payload":{"id":"srq-9","method":"secret","reason":"timeout"}}""")
        assertEquals(1, s.prompts.size)
    }

    @Test fun queuedPromptsAndInterruptedTurnKeepPartial() {
        var s = TurnReducer.optimisticSend(ChatState(sessionId = "S", running = true, generation = 4), "and run tests", "sub2", 0)
        s = s.feed("""{"type":"session.info","session_id":"S","seq":1,"payload":{"running":true,"pending":[{"admission_id":"B","status":"queued","input_id":"sub2","text":"and run tests","controls":["steer"]}]}}""")
        assertEquals(SendState.Queued, (s.items.single() as UserItem).state)
        assertTrue(s.queued.single().canSteer)
        s = s.feed(
            """{"type":"message.start","session_id":"S","seq":2,"execution_generation":4,"payload":{}}""",
            """{"type":"message.delta","session_id":"S","seq":3,"execution_generation":4,"payload":{"text":"Half an answer that was long"}}""",
            """{"type":"message.complete","session_id":"S","seq":4,"execution_generation":4,"payload":{"text":"Half","status":"interrupted"}}""",
        )
        assertEquals("Half an answer that was long", (s.items.last() as BotItem).text)
    }

    @Test fun todosRespectRevision() {
        var s = ChatState(sessionId = "S").feed("""{"type":"todo.updated","session_id":"S","seq":1,"payload":{"todos":[{"id":"1","content":"Read config","status":"completed"},{"id":"2","content":"Patch parser","status":"in_progress"}],"revision":3}}""")
        assertEquals(2, s.todos.size)
        s = s.feed("""{"type":"todo.updated","session_id":"S","seq":2,"payload":{"todos":[],"revision":2}}""")
        assertEquals(2, s.todos.size)
    }

    @Test fun errorTurnAddsNotice() {
        val s = ChatState(sessionId = "S", running = true).feed("""{"type":"message.complete","session_id":"S","seq":1,"payload":{"text":"","status":"error","error_surface":{"code":"usage_window","retryable":false}}}""")
        assertEquals("usage_window", s.lastError?.text)
    }

    @Test fun snapshotRebuildsHistory() {
        val snap = WireJson.decodeFromString(CanonicalSnapshot.serializer(), """
            {"session_id":"S","running":false,"revision":42,"execution_generation":9,"last_sequence":118,"replay_epoch":"E","subscription_id":"sub",
             "messages":[{"role":"user","text":"Fix the login button","row_id":1201,"timestamp":1761400001.2},
                         {"role":"assistant","text":"","row_id":1202,"reasoning":"Look at LoginForm"},
                         {"role":"tool","content":"file text","tool_call_id":"toolu_1","name":"read_file","context":"src/Login.tsx","row_id":1203},
                         {"role":"assistant","text":"Done: fixed it.","row_id":1204},
                         {"role":"system","text":"hidden scaffolding"}],
             "prompts":[{"kind":"approval","prompt_id":"P","execution_generation":9,"choices":["once","deny"],"command":"git push"}],
             "todo_state":{"todos":[{"id":"1","content":"a","status":"pending"}],"revision":2},"pending":[]}""")
        val s = TurnReducer.fromSnapshot(ChatState(), snap, 5)
        assertEquals(listOf("UserItem", "WorkItem", "BotItem"), s.items.map { it::class.simpleName })
        val w = s.items[1] as WorkItem
        assertEquals("read_file", w.steps.single().tool); assertFalse(w.running)
        assertEquals("git push", s.prompts.single().command)
        assertEquals(42L, s.revision); assertEquals(9L, s.generation); assertEquals(118L, s.lastSeq); assertEquals("sub", s.subscriptionId)
        assertEquals(1, s.todos.size)
    }

    /** delegate_task always detaches: the helper outlives the turn, stays one row, and only its own complete ends it. */
    @Test fun backgroundHelperOutlivesTheTurn() {
        var s = ChatState(sessionId = "S").feed(
            """{"type":"message.start","session_id":"S","seq":1,"payload":{}}""",
            """{"type":"subagent.spawn_requested","session_id":"S","seq":2,"payload":{"goal":"Read files","task_index":0,"task_count":1,"delegation_id":"d1"}}""",
            """{"type":"subagent.start","session_id":"S","seq":3,"payload":{"goal":"Read files","task_index":0,"task_count":1,"delegation_id":"d1","subagent_id":"sa1"}}""",
            """{"type":"message.complete","session_id":"S","seq":4,"payload":{"text":"Started a helper.","status":"complete"}}""",
        )
        assertFalse(s.running)
        val step = s.items.filterIsInstance<WorkItem>().flatMap { it.steps }.single()
        assertEquals("sa-sa1", step.id); assertEquals(StepStatus.Running, step.status)
        assertEquals(1, s.activeSubagents)
        s = s.feed("""{"type":"subagent.complete","session_id":"S","seq":5,"payload":{"subagent_id":"sa1","task_index":0,"status":"completed","summary":"Read 3 files"}}""")
        assertEquals(0, s.activeSubagents)
        assertEquals("Read 3 files", s.items.filterIsInstance<WorkItem>().flatMap { it.steps }.single().result)
    }

    /** One stretch of work = one ticking card; a steer bubble keeps the work in the open card. */
    @Test fun oneWorkingCardAndSteerMerges() {
        var s = ChatState(sessionId = "S").feed(
            """{"type":"message.start","session_id":"S","seq":1,"payload":{}}""",
            """{"type":"tool.start","session_id":"S","seq":2,"payload":{"tool_id":"t1","name":"terminal"}}""",
        )
        s = TurnReducer.optimisticSend(s, "also check B", "sub2", 10)
        s = s.feed("""{"type":"tool.start","session_id":"S","seq":3,"payload":{"tool_id":"t2","name":"read_file"}}""")
        assertEquals(2, s.items.filterIsInstance<WorkItem>().single().steps.size)
        s = s.feed(
            """{"type":"message.delta","session_id":"S","seq":4,"payload":{"text":"Partial."}}""",
            """{"type":"message.delta","session_id":"S","seq":5,"payload":{"text":null}}""",
            """{"type":"tool.start","session_id":"S","seq":6,"payload":{"tool_id":"t3","name":"terminal"}}""",
        )
        assertEquals(1, s.items.filterIsInstance<WorkItem>().count { it.running })
    }

    @Test fun compactionSummaryIsADividerAndSpinnerTextIsDropped() {
        val snap = WireJson.decodeFromString(CanonicalSnapshot.serializer(), """
            {"session_id":"S","running":false,"messages":[{"role":"assistant","content":"[CONTEXT COMPACTION — REFERENCE ONLY] Earlier turns were compacted…","row_id":32},
              {"role":"user","text":"hi","row_id":33},
              {"role":"user","content":"[ASYNC DELEGATION BATCH COMPLETE — deleg_1] … [INTERNAL NOTIFICATION — generated by the gateway]","row_id":34,"display_kind":"async_delegation_complete"}],"prompts":[],"pending":[]}""")
        val s = TurnReducer.fromSnapshot(ChatState(), snap, 5)
        assertTrue((s.items.first() as app.buddyverse.core.chat.NoticeItem).summary)
        assertTrue(s.items.none { it is BotItem })
        assertEquals(app.buddyverse.core.chat.Mark.HelperDone, (s.items.last() as app.buddyverse.core.chat.NoticeItem).mark)
        assertEquals(1, s.items.count { it is UserItem })
        assertEquals(null, TurnReducer.cleanPhrase("(´・_・`) contemplating..."))
        assertEquals(null, TurnReducer.cleanPhrase("(°□°) computing..."))
        assertEquals("Compressing context...", TurnReducer.cleanPhrase("Compressing context..."))
    }

    @Test fun sideAgentsAreHelpersWithTheirOwnAnswers() {
        var s = TurnReducer.sideTaskStart(ChatState(sessionId = "S"), "bg_abc123", "summarize the repo", 0)
        assertEquals(1, s.activeSubagents)
        s = s.feed("""{"type":"btw.complete","session_id":"S","seq":1,"payload":{"task_id":"btw_1","question":"q","text":"aside answer"}}""",
            """{"type":"background.complete","session_id":"S","seq":2,"payload":{"task_id":"bg_abc123","text":"repo summary"}}""")
        assertEquals(0, s.activeSubagents)
        assertEquals(listOf("aside answer", "repo summary"), s.sideAnswers.map { it.text })
    }
}
