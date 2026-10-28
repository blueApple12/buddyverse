package app.buddyverse.ui.more.models

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.buddyverse.core.api.MODEL_REASONING_LEVELS
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Card
import app.buddyverse.design.Chip
import app.buddyverse.design.Field
import app.buddyverse.design.Hairline
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.PillButton
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.modelsPage
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.ltr

/** Chip rows inside a card: a caps label, then chips that scroll sideways. */
@Composable
private fun ChipLine(label: String, content: @Composable () -> Unit) {
    Txt(label.uppercase(), Theme.type.caps, Theme.colors.secondary, Modifier.padding(start = 20.dp, top = 8.dp), maxLines = 1)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

private val PARALLEL = listOf(1, 2, 3, 5, 10, 20)

/**
 * Subagents (the helpers a bot starts with delegate_task, hermes-api.md §2.5 Delegation): their model, their thinking, how many
 * run at once, how many levels deep, and where they run. Saved in the bot's config; new subagents use it.
 */
@Composable
fun SubagentCard(m: ModelsModel, onPickModel: () -> Unit) {
    val t = S.modelsPage
    val c = Theme.colors
    val toasts = LocalToasts.current
    val st = m.settings ?: return
    val sub = st.subagent
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), padding = 0.dp) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).squish(onPickModel, scale = .97f, label = t.subModel).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModelIcon(Icons.group)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(t.subModel.uppercase(), Theme.type.caps, c.secondary, maxLines = 1)
                if (sub == null) Txt(t.sameAsBot, Theme.type.headline, maxLines = 1)
                else Txt(ltr(sub.model), Theme.type.headline.copy(fontFamily = Theme.type.mono), maxLines = 1)
                if (sub != null && sub.provider.isNotBlank()) Txt(m.providerName(sub.provider), Theme.type.sub, c.secondary, maxLines = 1)
            }
            LiveDot(c.accent, "sub:model" in m.busy)
            Icon(Icons.chevronRight, c.tertiary, mirror = true)
        }
        Hairline()
        ChipLine(t.subThinking) {
            Chip(t.same, st.subReasoning.isBlank(), { m.setSubReasoning("") })
            MODEL_REASONING_LEVELS.forEach { lv -> Chip(t.level(lv), st.subReasoning == lv, { m.setSubReasoning(lv) }) }
        }
        ChipLine(t.atOnce) {
            (PARALLEL + st.subParallel).distinct().sorted().forEach { n -> Chip(ltr("$n"), st.subParallel == n, { m.setSubParallel(n) }) }
        }
        ChipLine(t.depth) {
            (listOf(1, 2, 3) + st.subDepth).distinct().sorted().forEach { n -> Chip(t.depthName(n), st.subDepth == n, { m.setSubDepth(n) }) }
        }
        val r = m.routing
        if (r != null && (r.routes.size > 1 || r.blocked != null)) {
            ChipLine(t.route) {
                r.routes.forEach { route ->
                    Chip(route.label, r.configured == route.name, {
                        if (route.available) m.setSubRoute(route.name) else toasts.show(ToastData(route.reason ?: route.description))
                    })
                }
            }
            r.blocked?.let { Txt(t.routeBlocked(it), Theme.type.sub, c.errorText, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), maxLines = 4) }
            if (r.mappedModels + r.mappedBots > 0) {
                Txt(t.routesSetOnPc(r.mappedModels, r.mappedBots), Theme.type.caption, c.secondary, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), maxLines = 2)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * One team (a Mixture of Agents preset): its advisors (tap = on/off, × = remove, with undo), the model that combines their
 * answers, on/off, default, answer as it, delete. [onPick] opens the model picker on top of this sheet.
 */
@Composable
fun TeamSheet(m: ModelsModel, name: String?, onPick: (PickTarget) -> Unit, onDismiss: () -> Unit) {
    val t = S.modelsPage
    val c = Theme.colors
    val shown = rememberLast(name)
    val all = m.teams
    val team = all?.teams?.firstOrNull { it.name == shown }
    Sheet(name != null, onDismiss, title = shown, heightFraction = .92f) {
        if (team == null) { Thinking(null); return@Sheet }
        val busy = "teams" in m.busy
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt(t.teamOn, Theme.type.headline, maxLines = 1)
                    if (all.defaultTeam == team.name) Txt(t.defaultTeam, Theme.type.sub, c.accent, maxLines = 1)
                }
                Toggle(team.enabled, { on -> m.setTeamOn(team.name, on) }, t.teamOn, enabled = !busy)
            }
            Txt(t.advisors.uppercase(), Theme.type.caps, c.secondary, Modifier.padding(start = 20.dp, top = 12.dp), maxLines = 1)
            team.advisors.forEachIndexed { i, a ->
                InfoRow(a.model.ifBlank { "?" }, listOf(m.providerName(a.provider), t.off.takeIf { !a.enabled }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                    { m.setAdvisorOn(team.name, i, !a.enabled) }, mono = true, lead = { LiveDot(if (a.enabled) c.success else c.tertiary) },
                    trail = { IconButton(Icons.close, t.remove, { m.removeAdvisor(team.name, i) }) })
            }
            AddRow(t.addAdvisor, { onPick(PickTarget.TeamAdvisor(team.name)) })
            Txt(t.combiner.uppercase(), Theme.type.caps, c.secondary, Modifier.padding(start = 20.dp, top = 12.dp), maxLines = 1)
            InfoRow(team.combiner.model.ifBlank { "?" }, m.providerName(team.combiner.provider), { onPick(PickTarget.TeamCombiner(team.name)) }, mono = true,
                lead = { ModelIcon(Icons.hub) }, trail = { Icon(Icons.chevronRight, c.tertiary, mirror = true) })
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(t.useTeam, { m.useTeam(team.name); onDismiss() }, Modifier.fillMaxWidth(), icon = Icons.checkCircle, haptic = Haptic.Confirm,
                    enabled = team.enabled && "main" !in m.busy)
                if (all.defaultTeam != team.name) PillButton(t.makeDefaultTeam, { m.makeDefaultTeam(team.name) }, Modifier.fillMaxWidth(),
                    kind = ButtonKind.Outline, icon = Icons.pushPin, enabled = !busy)
                if (all.teams.size > 1) PillButton(t.deleteTeam, { onDismiss(); m.deleteTeam(team.name) }, Modifier.fillMaxWidth(),
                    kind = ButtonKind.Danger, icon = Icons.delete, haptic = Haptic.Reject)
            }
        }
    }
}

/** Name a new team; it starts with the bot's own model as advisor and combiner. */
@Composable
fun NewTeamSheet(m: ModelsModel, open: Boolean, onDismiss: () -> Unit) {
    val t = S.modelsPage
    val s = S.current
    val fb = LocalFeedback.current
    Sheet(open, onDismiss, title = t.addTeam) {
        var name by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        Field(name, { name = it; err = null }, t.teamName, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        err?.let { Txt(it, Theme.type.sub, Theme.colors.errorText, Modifier.padding(horizontal = 24.dp), maxLines = 3) }
        PillButton(s.create, {
            val r = m.addTeam(name)
            if (r == null) onDismiss() else { err = r; fb.haptic(Haptic.Reject) }
        }, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), enabled = name.isNotBlank(), icon = Icons.group)
    }
}
