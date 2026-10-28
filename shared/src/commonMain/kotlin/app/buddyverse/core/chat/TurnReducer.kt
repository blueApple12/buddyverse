package app.buddyverse.core.chat

import app.buddyverse.core.ServerRequest
import app.buddyverse.core.WireEvent
import app.buddyverse.core.arr
import app.buddyverse.core.bool
import app.buddyverse.core.child
import app.buddyverse.core.contract.CanonicalPendingAdmission
import app.buddyverse.core.contract.CanonicalPendingPrompt
import app.buddyverse.core.contract.CanonicalSnapshot
import app.buddyverse.core.contract.TranscriptMessage
import app.buddyverse.core.decodeAs
import app.buddyverse.core.double
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.obj
import app.buddyverse.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure turn reconstruction (hermes-api.md §2.2 "Reconstructing a message from deltas"):
 * snapshot → items, optimistic sends, then every session event folded in order.
 */
object TurnReducer {
    private const val MAX_RESULT = 4_000

    fun fromSnapshot(prev: ChatState, s: CanonicalSnapshot, now: Long): ChatState {
        var st = prev.copy(items = emptyList(), nextKey = prev.nextKey)
        for (m in s.messages) st = st.addHistory(m)
        if (s.running != true) st = st.copy(items = st.closeWork(now))
        // Keep optimistic bubbles the snapshot doesn't know yet (sent while resuming).
        val known = st.items.filterIsInstance<UserItem>().map { it.text }.toSet()
        val keep = prev.items.filterIsInstance<UserItem>().filter { it.state == SendState.Sending && it.text !in known }
        st = st.copy(
            items = st.items + keep,
            sessionId = s.sessionId ?: prev.sessionId,
            running = s.running == true,
            turnStartedAt = if (s.running == true) prev.turnStartedAt ?: now else null,
            turnStartIndex = st.items.size,
            prompts = s.prompts.mapNotNull { it.toPrompt() } + prev.prompts.filter { it.viaRequest },
            todos = s.todoState?.todos?.mapNotNull { it.toTodo() } ?: prev.todos,
            todoRevision = s.todoState?.revision ?: prev.todoRevision,
            queued = s.pending.toQueued(),
            revision = maxOf(prev.revision, s.revision ?: 0),
            generation = maxOf(prev.generation, s.executionGeneration ?: 0),
            lastSeq = s.lastSequence ?: prev.lastSeq,
            replayEpoch = s.replayEpoch ?: prev.replayEpoch,
            subscriptionId = s.subscriptionId ?: prev.subscriptionId,
            title = s.info?.title?.takeIf { it.isNotBlank() } ?: prev.title,
            model = s.info?.model?.takeIf { it.isNotBlank() } ?: prev.model,
            loaded = true,
            error = null,
            lastEventAt = now,
        )
        return st
    }

    fun optimisticSend(st: ChatState, text: String, submissionId: String, now: Long, state: SendState = SendState.Sending, attachments: List<String> = emptyList()): ChatState =
        st.copy(items = st.closeStreaming() + UserItem("u-$submissionId", text, state, now, submissionId, attachments = attachments), lastEventAt = now)

    fun markSend(st: ChatState, submissionId: String, state: SendState, rowId: Long? = null): ChatState =
        st.copy(items = st.items.map { if (it is UserItem && it.submissionId == submissionId) it.copy(state = state, rowId = rowId ?: it.rowId) else it })

    fun removeItem(st: ChatState, key: String): ChatState = st.copy(items = st.items.filterNot { it.key == key })

    fun notice(st: ChatState, text: String, tone: Tone, now: Long, oops: app.buddyverse.core.Oops? = null): ChatState =
        st.copy(items = st.items + NoticeItem("n-${st.nextKey}", text, tone, now, oops), nextKey = st.nextKey + 1)

    /** After a live replay: the snapshot's open prompts the events may not have carried (approval asked while we were away). */
    fun mergePrompts(st: ChatState, s: CanonicalSnapshot): ChatState {
        val fresh = s.prompts.mapNotNull { it.toPrompt() }.filter { p -> st.prompts.none { it.id == p.id } }
        return if (fresh.isEmpty()) st else st.copy(prompts = st.prompts + fresh, items = st.closeStreaming())
    }

    /** A /btw or /background agent started (its task id from `prompt.btw` / `prompt.background`): a helper row until it answers. */
    fun sideTaskStart(st: ChatState, taskId: String, text: String, now: Long): ChatState {
        val step = Step("bg-$taskId", if (taskId.startsWith("btw")) "btw" else "background", text, StepStatus.Running, subagent = true)
        if (st.running) return st.withWork(now) { it.copy(steps = it.steps + step) }
        // Outside a turn it gets its own card, already "ended": only the helper inside stays live.
        return st.copy(items = st.items + WorkItem("w-${st.nextKey}", listOf(step), "", now, endedAt = now), nextKey = st.nextKey + 1)
    }

    /** Engine marker for its own context-compaction summary: never a bubble, a small divider instead. */
    private const val COMPACTION = "[CONTEXT COMPACTION"

    /** Which engine-made row this is (by `display_kind`, else by the engine's own text markers), or null for a real message. */
    fun markOf(role: String?, displayKind: String?, text: String): Mark? {
        val t = text.trimStart()
        return when {
            t.startsWith(COMPACTION) -> Mark.Summary
            role != "user" -> null
            displayKind == "async_delegation_complete" || t.startsWith("[ASYNC DELEGATION") -> Mark.HelperDone
            displayKind == "process_complete" || displayKind == "auto_continue" || t.startsWith("[INTERNAL NOTIFICATION") || t.startsWith("[BACKGROUND PROCESS") -> Mark.JobDone
            else -> null
        }
    }

    /** The engine's CLI spinner ("(´・_・`) contemplating...") is not our voice: drop it, the UI says "Thinking". */
    internal fun cleanPhrase(t: String?): String? {
        val text = t?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val m = Regex("""^(.*\S)\s+[a-z]+ing(\.{2,3}|…)$""").find(text) ?: return text
        return if (m.groupValues[1].none { it in 'a'..'z' || it in 'A'..'Z' }) null else text
    }

    fun apply(st0: ChatState, e: WireEvent, now: Long): ChatState {
        // Fences: drop stamped frames from an older execution generation, and replays we already applied.
        val terminalSubagent = e.type == "subagent.complete"
        if (e.generation != null && e.generation < st0.generation && !terminalSubagent && e.type != "session.info") return st0
        if (e.seq != null && e.seq <= st0.lastSeq && e.replayEpoch == st0.replayEpoch && st0.replayEpoch != null) return st0
        var st = st0.copy(
            lastSeq = e.seq ?: st0.lastSeq,
            replayEpoch = e.replayEpoch ?: st0.replayEpoch,
            generation = maxOf(st0.generation, e.generation ?: 0),
            lastEventAt = now,
        )
        val p = e.payload
        st = when (e.type) {
            "message.start" -> st.copy(
                running = true, turnStartedAt = now, turnStartIndex = st.items.size, phrase = null,
                items = st.items.map { if (it is UserItem && it.state == SendState.Sending) it.copy(state = SendState.Sent) else it },
            ).let { s ->
                // An automation turn (helper / process finished): our divider, not the engine's card text.
                val shown = p.str("display_text")?.takeIf { it.isNotBlank() }
                val mark = p.str("display_kind")?.let { markOf("user", it, shown ?: p.str("text").orEmpty()) }
                when {
                    mark != null -> s.copy(items = s.items + NoticeItem("n-${s.nextKey}", shown.orEmpty(), Tone.Info, now, mark = mark), nextKey = s.nextKey + 1)
                    shown != null -> notice(s, shown, Tone.Info, now)
                    else -> s
                }
            }
            "session.info" -> st.sessionInfo(p, now)
            "session.updated" -> st.copy(revision = maxOf(st.revision, p.long("revision") ?: 0), title = p.str("title") ?: st.title)
            "message.delta" -> st.delta(p["text"]?.let { (it as? JsonPrimitive)?.takeIf { j -> j.isString }?.content }, now)
            "message.interim" -> {
                val text = p.str("text").orEmpty()
                if (p.bool("already_streamed") == true || text.isBlank()) st.copy(items = st.closeStreaming())
                else st.copy(items = st.closeStreaming() + BotItem("b-${st.nextKey}", text, false, now), nextKey = st.nextKey + 1)
            }
            "reasoning.delta" -> st.reasoning(p.str("text").orEmpty(), append = true, now)
            "reasoning.available" -> st.reasoning(p.str("text").orEmpty(), append = false, now)
            "thinking.delta", "status.update" -> p.str("text")?.takeIf { it.isNotBlank() }?.let { st.copy(phrase = cleanPhrase(it)) } ?: st
            "tool.generating" -> st.copy(phrase = p.str("name") ?: st.phrase)
            "tool.start" -> st.toolStart(p, now)
            "tool.progress" -> st.updateStep(p.str("tool_id")) { it.copy(progress = p.str("message") ?: p.double("percent")?.let { pc -> "${pc.toInt()}%" }) }
            "tool.complete" -> st.toolComplete(p)
            "todo.updated" -> st.todos(p.list("todos"), p.long("revision") ?: 0)
            "subagent.start", "subagent.spawn_requested" -> st.subagentStart(p, now)
            "subagent.thinking", "subagent.tool", "subagent.progress" -> p.str("child_session_id")?.let { c -> st.subagentStep(p) { it.copy(childSession = c) } } ?: st
            "subagent.complete" -> st.subagentStep(p) {
                it.copy(childSession = p.str("child_session_id") ?: it.childSession, status = if (p.str("status") in setOf("failed", "error", "timeout")) StepStatus.Failed else StepStatus.Done, result = p.str("summary")?.take(MAX_RESULT), durationS = p.double("duration_seconds"))
            }
            "approval.request", "clarify.request" -> st.addPrompt(runCatching { p.decodeAs<CanonicalPendingPrompt>() }.getOrNull()?.toPrompt())
            "approval.settled", "clarify.settled" -> st.copy(prompts = st.prompts.filterNot { it.id == p.str("prompt_id") })
            "request.cancel" -> st.copy(prompts = st.prompts.filterNot { it.id == p.str("id") })
            "message.complete" -> st.complete(p, now)
            "error" -> notice(st.copy(items = st.closeStreaming()), p.str("message") ?: "error", Tone.Error, now)
            "btw.complete", "background.complete" -> st.sideTaskDone(p, e.type == "background.complete")
            "notification.show" -> if (p.str("level") == "error") notice(st, p.str("text").orEmpty(), Tone.Error, now) else st
            else -> st
        }
        return st
    }

    /** Server requests the user must answer in this chat (secret, sudo, vault, batch clarify). */
    fun serverRequest(st: ChatState, r: ServerRequest): ChatState {
        val prompt = when (r.method) {
            "secret" -> Prompt(PromptKind.Secret, r.id, st.generation, description = r.params.str("prompt"), command = r.params.str("env_var"), viaRequest = true, method = r.method)
            "sudo" -> Prompt(PromptKind.Sudo, r.id, st.generation, command = r.params.str("command"), viaRequest = true, method = r.method)
            "vault.unlock_prompt", "vault.code", "vault.save_login" -> Prompt(PromptKind.Vault, r.id, st.generation,
                description = r.params.str("display_name") ?: r.params.str("site") ?: r.params.str("hint"), viaRequest = true, method = r.method)
            "clarify" -> r.params.list("questions").firstOrNull()?.obj?.let { q ->
                Prompt(PromptKind.Clarify, r.id, st.generation, question = q.str("question"), choices = q.list("choices").mapNotNull { (it as? JsonPrimitive)?.content },
                    multiSelect = q.bool("multi_select") == true, viaRequest = true, method = r.method, command = q.str("qid"))
            }
            else -> null
        }
        return st.addPrompt(prompt)
    }

    // ---------------------------------------------------------------- internals

    private fun ChatState.addPrompt(p: Prompt?): ChatState =
        if (p == null || prompts.any { it.id == p.id }) this else copy(prompts = prompts + p, items = closeStreaming())

    private fun ChatState.closeStreaming(): List<ChatItem> = items.map { if (it is BotItem && it.streaming) it.copy(streaming = false) else it }

    private fun ChatState.sessionInfo(p: JsonObject, now: Long): ChatState {
        var s = copy(revision = maxOf(revision, p.long("revision") ?: 0))
        p.str("title")?.takeIf { it.isNotBlank() }?.let { s = s.copy(title = it) }
        p.str("model")?.takeIf { it.isNotBlank() }?.let { s = s.copy(model = it) }
        if (p.containsKey("pending")) {
            val pending = runCatching { p.list("pending").map { it.decodeAs<CanonicalPendingAdmission>() } }.getOrDefault(emptyList())
            val queuedIds = pending.filter { it.status == "queued" }.mapNotNull { it.inputId }.toSet()
            s = s.copy(queued = pending.toQueued(), items = s.items.map {
                if (it is UserItem && it.submissionId in queuedIds && it.state == SendState.Sending) it.copy(state = SendState.Queued) else it
            })
        }
        val running = p.bool("running")
        if (running == false && s.running) s = s.endTurn(now)
        else if (running == true && !s.running) s = s.copy(running = true, turnStartedAt = s.turnStartedAt ?: now, turnStartIndex = s.items.size)
        return s
    }

    private fun ChatState.endTurn(now: Long): ChatState = copy(
        running = false, turnStartedAt = null, phrase = null,
        items = closeStreaming().map { if (it is WorkItem && it.endedAt == null) it.closeSteps(now) else it },
    )

    /** The turn's tools are done; helpers keep running in the background until their own complete (hermes-api.md §2.2 Notes). */
    private fun WorkItem.closeSteps(now: Long) = copy(endedAt = now, steps = steps.map { if (it.status == StepStatus.Running && !it.subagent) it.copy(status = StepStatus.Done) else it })

    private fun ChatState.sideTaskDone(p: JsonObject, background: Boolean): ChatState {
        val taskId = p.str("task_id") ?: "t-$nextKey"
        val text = p.str("text").orEmpty()
        val s = updateStep("bg-$taskId") { it.copy(status = StepStatus.Done, result = text.take(MAX_RESULT)) }
        if (text.isBlank() || s.sideAnswers.any { it.taskId == taskId }) return s
        return s.copy(sideAnswers = (s.sideAnswers + SideAnswer(taskId, p.str("question"), text, background)).takeLast(3))
    }

    private fun ChatState.delta(text: String?, now: Long): ChatState {
        if (text == null) return copy(items = closeStreaming())
        if (text.isEmpty()) return this
        val last = items.lastOrNull()
        val newItems = if (last is BotItem && last.streaming) items.dropLast(1) + last.copy(text = last.text + text)
        else items + BotItem("b-$nextKey", text, true, now)
        return copy(items = newItems, nextKey = if (last is BotItem && last.streaming) nextKey else nextKey + 1,
            lastDeltaChars = text.length, lastDeltaAt = now, running = true, turnStartedAt = turnStartedAt ?: now)
    }

    /**
     * The open work card, or a new one at the end. A steer bubble (or notice) after the open card keeps the work in that card;
     * a new stretch of work after reply text starts a new card and stops the older one ticking, so only one card says "Working".
     */
    private fun ChatState.withWork(now: Long, edit: (WorkItem) -> WorkItem): ChatState {
        val open = items.indexOfLast { it is WorkItem && it.endedAt == null }
        if (open >= 0 && (open + 1 until items.size).all { items[it] is UserItem || items[it] is NoticeItem }) {
            return copy(items = items.mapIndexed { i, it -> if (i == open) edit(it as WorkItem) else it })
        }
        val sealed = items.map { if (it is BotItem && it.streaming) it.copy(streaming = false) else if (it is WorkItem && it.endedAt == null) it.copy(endedAt = now) else it }
        return copy(items = sealed + edit(WorkItem("w-$nextKey", emptyList(), "", now)), nextKey = nextKey + 1)
    }

    private fun ChatState.reasoning(text: String, append: Boolean, now: Long): ChatState {
        if (text.isEmpty()) return this
        val last = items.lastOrNull()
        // Reasoning after reply text starts a new card only when the text is sealed; never interleave mid-bubble.
        if (last is BotItem && last.streaming) return this
        return withWork(now) { it.copy(reasoning = if (append) it.reasoning + text else text) }
    }

    private fun ChatState.toolStart(p: JsonObject, now: Long): ChatState {
        val id = p.str("tool_id") ?: p.str("tool_call_id") ?: "t-$nextKey"
        val tool = p.str("name") ?: p.str("tool_name") ?: "tool"
        // A tool.start for a row sealed without a result re-opens it.
        val existing = items.any { it is WorkItem && it.steps.any { s -> s.id == id } }
        if (existing) return updateStep(id) { it.copy(status = StepStatus.Running) }
        val step = Step(id, tool, p.str("context") ?: p.str("preview"), StepStatus.Running, args = p.child("args")?.toString()?.take(MAX_RESULT))
        return withWork(now) { it.copy(steps = it.steps + step) }.copy(phrase = tool)
    }

    private fun ChatState.toolComplete(p: JsonObject): ChatState {
        val id = p.str("tool_id") ?: p.str("tool_call_id")
        var s = updateStep(id) {
            it.copy(status = if (p.bool("is_error") == true) StepStatus.Failed else StepStatus.Done,
                result = resultText(p["result"])?.take(MAX_RESULT), durationS = p.double("duration_s"))
        }
        if (p.containsKey("todos") && p["todos"] is JsonArray) s = s.todos(p.list("todos"), p.long("revision") ?: (todoRevision + 1))
        return s
    }

    private fun resultText(e: JsonElement?): String? = when (e) {
        null -> null
        is JsonPrimitive -> e.content
        else -> e.toString()
    }

    private fun ChatState.updateStep(id: String?, edit: (Step) -> Step): ChatState {
        if (id == null) return this
        return copy(items = items.map { item ->
            if (item is WorkItem && item.steps.any { it.id == id }) item.copy(steps = item.steps.map { if (it.id == id) edit(it) else it }) else item
        })
    }

    /** `delegation_id#task_index`: spawn_requested can come before the child has its subagent_id. */
    private fun aliasOf(p: JsonObject) = "${p.str("delegation_id").orEmpty()}#${p.long("task_index") ?: 0}"

    /** This turn's not-yet-identified spawn row for the same delegation slot (ids starting "sa-~"). */
    private fun ChatState.pendingSpawn(alias: String): String? = items.drop(turnStartIndex.coerceAtMost(items.size))
        .firstNotNullOfOrNull { i -> (i as? WorkItem)?.steps?.firstOrNull { it.alias == alias && it.id.startsWith("sa-~") }?.id }

    /** Upsert by subagent_id, else by (delegation_id, task_index) within this turn: one row per child, never two. */
    private fun ChatState.subagentStart(p: JsonObject, now: Long): ChatState {
        val sid = p.str("subagent_id")?.let { "sa-$it" }
        val alias = aliasOf(p)
        if (sid != null && items.any { it is WorkItem && it.steps.any { s -> s.id == sid } }) return this
        val pending = pendingSpawn(alias)
        if (pending != null) return if (sid == null) this else updateStep(pending) { it.copy(id = sid, childSession = p.str("child_session_id") ?: it.childSession) }
        val step = Step(sid ?: "sa-~$alias-$nextKey", "delegate_task", p.str("goal"), StepStatus.Running, subagent = true, alias = alias, childSession = p.str("child_session_id"))
        return withWork(now) { it.copy(steps = it.steps + step) }.let { if (sid == null) it.copy(nextKey = it.nextKey + 1) else it }
    }

    private fun ChatState.subagentStep(p: JsonObject, edit: (Step) -> Step): ChatState {
        val sid = p.str("subagent_id")?.let { "sa-$it" }
        if (sid != null && items.any { it is WorkItem && it.steps.any { s -> s.id == sid } }) return updateStep(sid, edit)
        return updateStep(pendingSpawn(aliasOf(p)), edit)
    }

    private fun ChatState.todos(raw: List<JsonElement>, revision: Long): ChatState {
        if (revision < todoRevision) return this
        return copy(todos = raw.mapNotNull { it.obj?.toTodo() }, todoRevision = revision)
    }

    private fun JsonElement.toTodo(): Todo? = obj?.let { o -> Todo(o.str("id") ?: return null, o.str("content").orEmpty(), o.str("status") ?: "pending") }
    private fun JsonObject.toTodo(): Todo? = Todo(str("id") ?: return null, str("content").orEmpty(), str("status") ?: "pending")

    private fun ChatState.complete(p: JsonObject, now: Long): ChatState {
        val text = p.str("text") ?: p.str("content") ?: ""
        val status = p.str("status") ?: "complete"
        val turnItems = items.drop(turnStartIndex.coerceAtMost(items.size))
        val lastWork = turnItems.indexOfLast { it is WorkItem }
        val lastBotRel = turnItems.indexOfLast { it is BotItem }
        val lastBot = if (lastBotRel > lastWork && lastBotRel >= 0) turnStartIndex + lastBotRel else -1
        var newItems = closeStreaming()
        var key = nextKey
        when {
            status == "error" -> Unit
            text.isBlank() || text.trimStart().startsWith(COMPACTION) -> Unit
            lastBot >= 0 -> {
                val bubble = newItems[lastBot] as BotItem
                val norm = { s: String -> s.replace(Regex("\\s+"), " ").trim() }
                val reused = p.bool("response_reused") == true && norm(bubble.text).contains(norm(text))
                val keepPartial = status == "interrupted" && bubble.text.length >= text.length
                if (!reused && !keepPartial) newItems = newItems.toMutableList().also { it[lastBot] = bubble.copy(text = text) }
            }
            else -> { newItems = newItems + BotItem("b-$key", text, false, now); key++ }
        }
        // Bind durable row ids so a transcript re-read never paints twice.
        val persisted = p.child("persisted_turn")
        val sub = persisted.str("submission_id")
        val userRow = persisted.long("user_row_id")
        val botRow = persisted.long("final_assistant_row_id")
        // The live receipt usually omits submission_id: pair user_row_ids with the newest unbound sent bubbles, in order.
        val userRows = persisted.list("user_row_ids").mapNotNull { it.toString().toLongOrNull() }.ifEmpty { listOfNotNull(userRow) }
            .filter { r -> newItems.none { (it as? UserItem)?.rowId == r } }
        val unbound = newItems.indices.filter { i -> (newItems[i] as? UserItem)?.let { it.rowId == null && it.state != SendState.Queued } == true }
        val bind = if (sub == null) unbound.takeLast(userRows.size).zip(userRows.takeLast(unbound.size)).toMap() else emptyMap()
        newItems = newItems.mapIndexed { i, it ->
            when {
                it is UserItem && sub != null && it.submissionId == sub -> it.copy(rowId = userRow ?: it.rowId, state = SendState.Sent)
                it is UserItem && i in bind -> it.copy(rowId = bind[i])
                it is BotItem && botRow != null && i == newItems.indexOfLast { b -> b is BotItem } -> it.copy(rowId = botRow)
                it is WorkItem && it.endedAt == null -> it.closeSteps(now)
                else -> it
            }
        }
        var s = copy(items = newItems, nextKey = key, running = false, turnStartedAt = null, phrase = null, prompts = prompts.filter { it.viaRequest },
            todos = if (todos.all { it.done }) todos else emptyList())
        if (status == "error") {
            val surface = p.child("error_surface")
            val why = p.str("failure_reason") ?: surface.str("code") ?: p.str("warning") ?: "turn failed"
            s = notice(s, why, Tone.Error, now)
        } else p.str("warning")?.takeIf { it.isNotBlank() }?.let { s = notice(s, it, Tone.Warn, now) }
        return s
    }

    private fun ChatState.addHistory(m: TranscriptMessage): ChatState {
        if (m.displayKind == "hidden" || m.role == "system") return this
        val at = ((m.timestamp ?: 0.0) * 1000).toLong()
        val text = m.text?.takeIf { it.isNotBlank() } ?: contentText(m.content)
        // Engine-made rows (the compaction summary, which lacks display_kind:"hidden" in the snapshot; the "helper finished" /
        // "process finished" user rows of automation turns) are one small divider in our words, never a bubble.
        val mark = markOf(m.role, m.displayKind, text)
        if (mark != null) {
            if ((items.lastOrNull() as? NoticeItem)?.mark == mark) return this
            return copy(items = closeWork(at) + NoticeItem("n-mk-${m.rowId ?: nextKey}", "", Tone.Info, at, mark = mark), nextKey = nextKey + 1)
        }
        return when (m.role) {
            "user" -> copy(items = closeWork(at) + UserItem("u-row-${m.rowId ?: nextKey}", text, SendState.Sent, at, rowId = m.rowId), nextKey = nextKey + 1)
            "assistant" -> {
                var s = this
                if (!m.reasoning.isNullOrBlank()) s = s.withWork(at) { it.copy(reasoning = it.reasoning + m.reasoning) }
                if (text.isNotBlank()) s = s.copy(items = s.closeWork(at) + BotItem("b-row-${m.rowId ?: s.nextKey}", text, false, at, m.rowId), nextKey = s.nextKey + 1)
                s
            }
            "tool" -> {
                val step = Step(m.toolCallId ?: "h-${nextKey}", m.name ?: "tool", m.context, StepStatus.Done, args = m.args?.toString()?.take(MAX_RESULT), result = text.take(MAX_RESULT))
                withWork(at) { it.copy(steps = it.steps + step) }
            }
            else -> this
        }
    }

    private fun ChatState.closeWork(at: Long): List<ChatItem> = items.map { if (it is WorkItem && it.endedAt == null) it.copy(endedAt = at) else it }

    private fun contentText(c: JsonElement?): String = when (c) {
        null -> ""
        is JsonPrimitive -> c.content
        is JsonArray -> c.mapNotNull { part -> part.obj?.takeIf { it.str("type") in setOf("text", "input_text", "output_text") }?.str("text") }.joinToString("\n")
        else -> ""
    }

    private fun CanonicalPendingPrompt.toPrompt(): Prompt? {
        val id = promptId ?: return null
        return Prompt(if (kind == "clarify") PromptKind.Clarify else PromptKind.Approval, id, executionGeneration ?: 0,
            command = command, description = description, question = question, choices = choices, multiSelect = multiSelect == true)
    }

    private fun List<CanonicalPendingAdmission>.toQueued(): List<Queued> = filter { it.status == "queued" }.mapNotNull { a ->
        Queued(a.admissionId ?: return@mapNotNull null, a.text.orEmpty(), "steer" in a.controls, a.inputId)
    }
}
