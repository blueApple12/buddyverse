package app.buddyverse.core.chat

enum class SendState { Sending, Sent, Queued, Steered, Failed }
enum class StepStatus { Running, Done, Failed }
enum class Tone { Info, Warn, Error }
enum class PromptKind { Approval, Clarify, Secret, Sudo, Vault }

sealed interface ChatItem {
    val key: String
    val at: Long
}

data class UserItem(
    override val key: String,
    val text: String,
    val state: SendState,
    override val at: Long,
    val submissionId: String? = null,
    val rowId: Long? = null,
    val attachments: List<String> = emptyList(),
) : ChatItem

data class BotItem(
    override val key: String,
    val text: String,
    val streaming: Boolean,
    override val at: Long,
    val rowId: Long? = null,
) : ChatItem

data class Step(
    val id: String,
    val tool: String,
    val context: String?,
    val status: StepStatus,
    val args: String? = null,
    val result: String? = null,
    val progress: String? = null,
    val durationS: Double? = null,
    /** A helper (delegate_task child, /background or /btw agent): it outlives the turn and ends only on its own complete. */
    val subagent: Boolean = false,
    /** `delegation_id#task_index`: matches a spawn that came before the child had an id. */
    val alias: String? = null,
    /** The helper's own session (`child_session_id`): its chat opens read-only. */
    val childSession: String? = null,
) {
    /** /background and /btw agents have no live tail or stop; delegate_task children do. */
    val sideTask: Boolean get() = id.startsWith("bg-")
}

data class WorkItem(
    override val key: String,
    val steps: List<Step>,
    val reasoning: String,
    override val at: Long,
    val endedAt: Long? = null,
) : ChatItem {
    val running: Boolean get() = endedAt == null
}

/** Engine-made rows shown as a quiet divider in our words, never as a bubble with the engine's text. */
enum class Mark { Summary, HelperDone, JobDone }

/** [oops] = say it in plain words ([text] keeps the raw detail for long press); [mark] = a divider instead of text. */
data class NoticeItem(
    override val key: String, val text: String, val tone: Tone, override val at: Long,
    val oops: app.buddyverse.core.Oops? = null, val mark: Mark? = null,
) : ChatItem {
    val summary: Boolean get() = mark == Mark.Summary
}

/** A /btw or /background answer; each stays until dismissed. [background] = a /background agent, else /btw. */
data class SideAnswer(val taskId: String, val question: String?, val text: String, val background: Boolean)

data class Prompt(
    val kind: PromptKind,
    /** prompt_id (approval/clarify events) or the server-request id (secret/sudo/vault/batch clarify). */
    val id: String,
    val generation: Long,
    val command: String? = null,
    val description: String? = null,
    val question: String? = null,
    val choices: List<String> = emptyList(),
    val multiSelect: Boolean = false,
    val viaRequest: Boolean = false,
    val method: String? = null,
)

data class Todo(val id: String, val content: String, val status: String) {
    val done: Boolean get() = status == "completed" || status == "cancelled"
}

data class Queued(val admissionId: String, val text: String, val canSteer: Boolean, val submissionId: String?)

/** Everything one chat screen renders. Built only by [TurnReducer] so it can be unit-tested without a socket. */
data class ChatState(
    val sessionId: String? = null,
    val profile: String = "default",
    val items: List<ChatItem> = emptyList(),
    val running: Boolean = false,
    val turnStartedAt: Long? = null,
    val turnStartIndex: Int = 0,
    val phrase: String? = null,
    val prompts: List<Prompt> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val todoRevision: Long = -1,
    val queued: List<Queued> = emptyList(),
    val revision: Long = 0,
    val generation: Long = 0,
    val lastSeq: Long = 0,
    val replayEpoch: String? = null,
    val subscriptionId: String? = null,
    val title: String? = null,
    val model: String? = null,
    /** Chars in the newest reply chunk and when it came: the buddy "talks" with it (design §4.4). */
    val lastDeltaChars: Int = 0,
    val lastDeltaAt: Long = 0,
    val lastEventAt: Long = 0,
    val nextKey: Long = 0,
    val loaded: Boolean = false,
    val error: String? = null,
    val sideAnswers: List<SideAnswer> = emptyList(),
) {
    /** Helpers still working, counted from their steps, so a finished or missed one can never linger. */
    val activeSubagents: Int get() = items.sumOf { i -> if (i is WorkItem) i.steps.count { it.subagent && it.status == StepStatus.Running } else 0 }
    val streamingText: String? get() = (items.lastOrNull() as? BotItem)?.takeIf { it.streaming }?.text
    val lastError: NoticeItem? get() = (items.lastOrNull() as? NoticeItem)?.takeIf { it.tone == Tone.Error }
}
