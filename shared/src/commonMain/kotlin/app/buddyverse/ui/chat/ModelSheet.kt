package app.buddyverse.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.buddyverse.core.ChatController
import app.buddyverse.core.RpcError
import app.buddyverse.core.api.ModelOptions
import app.buddyverse.core.api.ModelPick
import app.buddyverse.core.api.pickerOptions
import app.buddyverse.core.api.pickModel
import app.buddyverse.core.api.reasoningLevel
import app.buddyverse.core.api.setReasoning
import app.buddyverse.core.str
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Chip
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.chat
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.ltr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import app.buddyverse.ui.modelLabel

/** Levels in ascending order; "none" = thinking off (hermes-api.md §2.3 "Reasoning effort"). */
private val EFFORTS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

private data class Target(val model: String, val provider: String?)

/**
 * Model and thinking level for this chat (design §3.2 model chip; hermes-api.md §2.3 "Model picker").
 * A guarded pick (cost, context) asks once more; a busy session applies it after the turn.
 */
@Composable
fun ModelSheet(visible: Boolean, chat: ChatController, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val store = LocalStore.current
    val toasts = LocalToasts.current
    val fb = LocalFeedback.current
    val s = S.current
    val sc = S.chat
    val c = Theme.colors
    val scope = rememberCoroutineScope()
    val st by chat.state.collectAsState()
    val profile = chat.target.profile
    var opts by remember(chat) { mutableStateOf<ModelOptions?>(null) }
    var failed by remember(chat) { mutableStateOf(false) }
    var effort by remember(chat) { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf<Pair<Target, ModelPick.Confirm>?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(visible, chat) {
        if (!visible) return@LaunchedEffect
        confirm = null
        failed = false
        try {
            // OpenRouter and Gemini: only their free chat models (core/api/FreeModels.kt).
            opts = store.api.pickerOptions(profile, chat.sessionId)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { failed = opts == null }
        chat.sessionId?.let { sid -> effort = runCatching { store.api.reasoningLevel(sid, profile) }.getOrNull() ?: effort }
    }

    fun errorText(e: Exception) = (e as? RpcError)?.let { it.data.str("detail") ?: it.reason } ?: s.error

    fun pick(t: Target, token: String? = null) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val sid = chat.ensureAttached(createIfMissing = true) ?: throw IllegalStateException()
                val now = chat.state.value
                when (val r = store.api.pickModel(sid, profile, t.model, t.provider, now.revision, now.generation, token)) {
                    ModelPick.Applied -> { fb.haptic(Haptic.Confirm); onPicked(t.model); onDismiss() }
                    ModelPick.Deferred -> { onPicked(t.model); toasts.show(ToastData(sc.modelDeferred)); onDismiss() }
                    is ModelPick.Confirm -> { confirm = t to r }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                fb.haptic(Haptic.Reject); toasts.show(ToastData(errorText(e)))
            } finally { busy = false }
        }
    }

    fun setEffort(level: String) {
        val before = effort
        effort = level
        scope.launch {
            try {
                val sid = chat.ensureAttached(createIfMissing = true) ?: throw IllegalStateException()
                val now = chat.state.value
                if (store.api.setReasoning(sid, profile, level, now.revision, now.generation)) toasts.show(ToastData(sc.modelDeferred))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                effort = before; toasts.show(ToastData(errorText(e)))
            }
        }
    }

    val current = opts?.model ?: st.model
    Sheet(visible, onDismiss, title = sc.model, heightFraction = .85f) {
        val pending = confirm
        if (pending != null) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface2).padding(16.dp)) {
                Txt(ltr(modelLabel(pending.first.model) ?: pending.first.model), Theme.type.headline, c.text, maxLines = 1)
                if (pending.second.message.isNotBlank()) Txt(pending.second.message, Theme.type.sub, c.secondary, Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(s.cancel, { confirm = null }, kind = ButtonKind.Outline, height = 48.dp)
                    PillButton(sc.switchAnyway, { confirm = null; pick(pending.first, pending.second.token) }, height = 48.dp, haptic = Haptic.Confirm)
                }
            }
        }
        SectionLabel(sc.thinkingLevel)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (e in EFFORTS) Chip(sc.effort(e), (effort ?: "medium") == e, { if (effort != e) setEffort(e) })
        }
        Field(query, { query = it }, sc.searchModels, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), mono = false)
        val o = opts
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            when {
                o == null && failed -> item(key = "err") { Txt(s.unsupported, Theme.type.sub, c.tertiary, Modifier.padding(20.dp)) }
                o == null -> item(key = "loading") { Txt(s.loading, Theme.type.sub, c.tertiary, Modifier.padding(20.dp)) }
                else -> { for (p in o.providers) {
                    val q = query.trim().lowercase()
                    val all = (p.featured + p.models).distinct()
                    val list = when {
                        q.isNotEmpty() -> all.filter { q in it.lowercase() || q in modelLabel(it).orEmpty().lowercase() }
                        p.slug in expanded -> all
                        else -> p.featured.ifEmpty { p.models.take(8) }.distinct()
                    }
                    if (list.isEmpty()) continue
                    item(key = "h-${p.slug}") { SectionLabel(p.name) }
                    items(list, key = { "m-${p.slug}-$it" }) { m ->
                        val isCurrent = m == current && (o.provider == null || o.provider == p.slug)
                        PressRow({ pick(Target(m, p.slug)) }, minHeight = 56.dp, label = m) {
                            Txt(ltr(modelLabel(m) ?: m), Theme.type.callout, if (isCurrent) c.accent else c.text, Modifier.weight(1f), maxLines = 1)
                            if (isCurrent) Icon(Icons.check, c.accent, 22.dp)
                        }
                    }
                    if (q.isEmpty() && p.slug !in expanded && all.size > list.size) item(key = "more-${p.slug}") {
                        PressRow({ expanded = expanded + p.slug }, minHeight = 48.dp, label = sc.showAll(all.size)) {
                            Txt(sc.showAll(all.size), Theme.type.sub, c.accent, maxLines = 1)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.expandMore, c.accent, 20.dp)
                        }
                    }
                } }
            }
        }
    }
}
