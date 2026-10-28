package app.buddyverse.ui.more.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.buddyverse.core.api.PickedModel
import app.buddyverse.core.api.modelTokens
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Chip
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.modelsPage

/** Keeps the last non-null value so a closing sheet still has content while it slides away. */
@Composable
fun <T : Any> rememberLast(value: T?): T? {
    val box = remember { arrayOfNulls<Any>(1) }
    if (value != null) box[0] = value
    @Suppress("UNCHECKED_CAST") return box[0] as T?
}

/** Pick a model for [target] from every signed-in provider ([only] narrows to one). Shown while [target] != null. */
@Composable
fun PickerSheet(m: ModelsModel, target: PickTarget?, only: String?, onDismiss: () -> Unit) {
    val t = S.modelsPage
    val c = Theme.colors
    val shown = rememberLast(target)
    var query by remember(shown) { mutableStateOf("") }
    val title = when (shown) {
        is PickTarget.Helper -> t.helperLabel(shown.task, m.helpers.firstOrNull { it.task == shown.task }?.label)
        PickTarget.Fallback -> t.addFallback
        PickTarget.Subagent -> t.subModel
        is PickTarget.TeamAdvisor -> t.addAdvisor
        is PickTarget.TeamCombiner -> t.combiner
        else -> t.defaultModel
    }
    // A team can't hold another team (the PC refuses a recursive Mixture of Agents).
    val inTeam = shown is PickTarget.TeamAdvisor || shown is PickTarget.TeamCombiner
    Sheet(target != null, onDismiss, title = title, heightFraction = .92f) {
        Field(query, { query = it }, t.searchModels, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), mono = true)
        val q = query.trim().lowercase()
        val groups = m.pickable.filter { (only == null || it.slug == only) && !(inTeam && it.slug == "moa") }
            .map { p -> p to p.models.filter { q.isEmpty() || it.lowercase().contains(q) || p.name.lowercase().contains(q) } }
            .filter { it.second.isNotEmpty() }
        val helper = (shown as? PickTarget.Helper)?.let { h -> m.helpers.firstOrNull { it.task == h.task } }
        val info = m.info
        fun isCurrent(slug: String, model: String): Boolean = when (shown) {
            PickTarget.Main -> info?.provider == slug && info.model == model
            is PickTarget.Helper -> helper?.provider == slug && helper.model == model
            PickTarget.Fallback -> m.settings?.fallbacks?.contains(PickedModel(slug, model)) == true
            PickTarget.Subagent -> m.settings?.subagent == PickedModel(slug, model)
            is PickTarget.TeamCombiner -> m.teams?.teams?.firstOrNull { it.name == shown.team }?.combiner?.let { it.provider == slug && it.model == model } == true
            is PickTarget.TeamAdvisor -> m.teams?.teams?.firstOrNull { it.name == shown.team }?.advisors?.any { it.provider == slug && it.model == model } == true
            null -> false
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (shown is PickTarget.Helper && q.isEmpty()) item(key = "auto") {
                InfoRow(t.auto, t.followsMain, { m.pick(shown, null); onDismiss() },
                    lead = { ModelIcon(Icons.autoAwesome) }, trail = { if (helper?.auto == true) Check() })
            }
            if (shown == PickTarget.Subagent && q.isEmpty()) item(key = "same") {
                InfoRow(t.sameAsBot, info?.model, { m.pick(PickTarget.Subagent, null); onDismiss() },
                    lead = { ModelIcon(Icons.autoAwesome) }, trail = { if (m.settings?.subagent == null) Check() })
            }
            if (groups.isEmpty()) item(key = "none") { PageMessage(if (m.pickable.isEmpty()) t.noProviders else t.noMatches) }
            groups.forEach { (p, models) ->
                item(key = "h:${p.slug}") { SectionLabel(p.name) }
                items(models, key = { "m:${p.slug}:$it" }) { model ->
                    val current = isCurrent(p.slug, model)
                    InfoRow(model, p.prices[model], {
                        if (shown != null && !(current && (shown == PickTarget.Fallback || shown is PickTarget.TeamAdvisor))) m.pick(shown, PickedModel(p.slug, model))
                        onDismiss()
                    }, mono = true, trail = if (current) CheckSlot else null)
                }
            }
        }
    }
}

/** A trailing check as a slot value (lets rows pass `if (on) CheckSlot else null`). */
val CheckSlot: @Composable () -> Unit = { Check() }

@Composable
fun Check() = Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(Icons.checkCircleFilled, Theme.colors.success, 24.dp) }

@Composable
fun ModelIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color = Theme.colors.accent) =
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(icon, tint, 24.dp) }

/** The PC asks before a costly or risky switch: say why, then "Use it" or not. */
@Composable
fun ConfirmSheet(m: ModelsModel) {
    val t = S.modelsPage
    val s = S.current
    val p = rememberLast(m.pending)
    Sheet(m.pending != null, m::dropPending, title = t.confirmTitle) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (p != null) {
                p.ref?.let { Txt(it.model, Theme.type.code, Theme.colors.secondary, maxLines = 1) }
                Txt(p.message, Theme.type.body)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(s.cancel, m::dropPending, Modifier.weight(1f), kind = ButtonKind.Quiet)
                PillButton(t.useAnyway, m::confirmPending, Modifier.weight(1f), haptic = Haptic.Confirm)
            }
        }
    }
}

private val CONTEXT_PRESETS = listOf(0L, 32_768L, 65_536L, 131_072L, 262_144L, 1_000_000L)

/** How much the model may read at once: Auto, a preset, or a number. */
@Composable
fun ContextSheet(m: ModelsModel, open: Boolean, onDismiss: () -> Unit) {
    val t = S.modelsPage
    val s = S.current
    val cur = m.settings?.contextLength ?: 0L
    var text by remember(open) { mutableStateOf(if (cur > 0) "$cur" else "") }
    Sheet(open, onDismiss, title = t.context) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CONTEXT_PRESETS.forEach { n ->
                    Chip(if (n == 0L) t.auto else modelTokens(n), cur == n, { m.setContext(n); onDismiss() })
                }
            }
            Field(text, { v -> text = v.filter(Char::isDigit).take(9) }, t.tokens, mono = true, keyboard = KeyboardType.Number)
            val n = text.toLongOrNull()
            PillButton(s.save, { if (n != null) { m.setContext(n); onDismiss() } }, Modifier.fillMaxWidth(), enabled = n != null && n != cur, icon = Icons.check)
            Spacer(Modifier.height(4.dp))
        }
    }
}
