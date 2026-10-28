package app.buddyverse.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Chip
import app.buddyverse.design.Field
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.PillButton
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.skillsPage
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.MorePage
import app.buddyverse.ui.PageFrame
import app.buddyverse.ui.more.skills.Confirm
import app.buddyverse.ui.more.skills.ConfirmSheet
import app.buddyverse.ui.more.skills.CuratorCard
import app.buddyverse.ui.more.skills.Delayed
import app.buddyverse.ui.more.skills.EditTarget
import app.buddyverse.ui.more.skills.EmptyNote
import app.buddyverse.ui.more.skills.ErrorRow
import app.buddyverse.ui.more.skills.HubRow
import app.buddyverse.ui.more.skills.HubSheet
import app.buddyverse.ui.more.skills.HubSkill
import app.buddyverse.ui.more.skills.LoadingNote
import app.buddyverse.ui.more.skills.LogLines
import app.buddyverse.ui.more.skills.Skill
import app.buddyverse.ui.more.skills.SkillEditor
import app.buddyverse.ui.more.skills.SkillMenu
import app.buddyverse.ui.more.skills.SkillRow
import app.buddyverse.ui.more.skills.SkillSheet
import app.buddyverse.ui.more.skills.SkillsModel
import app.buddyverse.ui.more.skills.SkillsTab

/**
 * More ▸ Skills (hermes-api.md §2.5 Skills + hub + curator, §2.6 Skills): one global list shared by all bots (read via the default
 * bot; a switch is written to every bot), then three tabs.
 * Installed: search, by category, switch per row, tap = view, long press = menu, + = new skill. Browse (hub): official catalog or search,
 * one-tap install (community ones open the safety check first), update all. Upkeep: the curator's switch, rhythm and Tidy now.
 */
@Composable
fun SkillsPage() {
    val store = LocalStore.current
    val scope = rememberCoroutineScope()
    val model = remember(store) { SkillsModel(store, scope) }
    val t = S.skillsPage
    val s = S.current
    val bots by store.bots.collectAsState()
    val home = bots.firstOrNull { it.isDefault } ?: bots.firstOrNull()
    val homeProfile = home?.profile ?: "default"
    model.fb = LocalFeedback.current
    model.toasts = LocalToasts.current
    model.t = t
    model.s = s
    model.spec = home?.spec ?: BuddySpec(homeProfile, s.brain, BuddyShape.parse(null, homeProfile), BuddyColors.forName(homeProfile))

    LaunchedEffect(model, homeProfile) {
        model.select(homeProfile)
        store.gateway.events.collect { if (it.type == "skills.changed") model.onSkillsChanged() }
    }

    var viewing by remember { mutableStateOf<Skill?>(null) }
    var menuFor by remember { mutableStateOf<Skill?>(null) }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var hubOpen by remember { mutableStateOf<HubSkill?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val askUninstall: (String) -> Unit = { name -> confirm = Confirm(name, t.confirmUninstall, t.uninstall) { model.uninstall(name) } }
    val askRemove: (Skill) -> Unit = { sk ->
        if (sk.provenance == "hub") askUninstall(sk.name)
        else confirm = Confirm(sk.name, t.confirmDelete, s.delete) { model.archive(sk) }
    }

    Box(Modifier.fillMaxSize()) {
        PageFrame(MorePage.Skills.label, actions = { IconButton(Icons.add, t.newSkill, { editing = EditTarget.New }) }) {
            item(key = "shared") { SharedHeader(model) }
            item(key = "tabs") { SkillTabs(model) }
            when (model.tab) {
                SkillsTab.Installed -> skillsInstalledTab(model, onOpen = { viewing = it }, onMenu = { menuFor = it })
                SkillsTab.Hub -> skillsHubTab(model, onOpen = { hubOpen = it })
                SkillsTab.Upkeep -> skillsUpkeepTab(model, onRun = { confirm = Confirm(t.tidyNow, t.confirmTidy, t.tidyNow, model::runCurator) })
            }
        }
        SkillSheet(model, viewing, { viewing = null }, onEdit = { viewing = null; editing = EditTarget.Existing(it) }, onRemove = { viewing = null; askRemove(it) })
        SkillMenu(
            menuFor, { menuFor = null },
            onView = { viewing = it }, onEdit = { editing = EditTarget.Existing(it) }, onToggle = { model.toggle(it, !it.enabled) }, onRemove = askRemove,
        )
        SkillEditor(model, editing) { editing = null }
        HubSheet(model, hubOpen, { hubOpen = null }, onUninstall = { hubOpen = null; askUninstall(it) })
        ConfirmSheet(confirm) { confirm = null }
    }
}

/** The default bot's buddy living the page's mood, and a note that this list is every bot's. */
@Composable
private fun SharedHeader(model: SkillsModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        model.spec?.let { Buddy(it, model.mood, 48.dp, cue = model.cue, interactive = false); Spacer(Modifier.width(12.dp)) }
        Txt(S.skillsPage.allBots, Theme.type.sub, Theme.colors.secondary, maxLines = 1)
    }
}

@Composable
private fun SkillTabs(model: SkillsModel) {
    val t = S.skillsPage
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SkillsTab.entries.forEach { tab ->
            val label = when (tab) { SkillsTab.Installed -> t.installed; SkillsTab.Hub -> t.hub; SkillsTab.Upkeep -> t.upkeep }
            Chip(label, model.tab == tab, { model.show(tab) })
        }
    }
}

private val skillsSearchPad = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)

private fun LazyListScope.skillsInstalledTab(model: SkillsModel, onOpen: (Skill) -> Unit, onMenu: (Skill) -> Unit) {
    item(key = "search") { Field(model.query, { model.query = it }, S.skillsPage.searchSkills, skillsSearchPad) }
    model.loadError?.let { e -> item(key = "error") { ErrorRow(e, model::refreshSkills) } }
    if (!model.loaded) { item(key = "loading") { Delayed { LoadingNote() } }; return }
    val q = model.query.trim()
    val shown = if (q.isEmpty()) model.skills else model.skills.filter {
        it.name.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true) || it.category.contains(q, ignoreCase = true)
    }
    if (shown.isEmpty()) {
        if (model.loadError == null) item(key = "empty") { EmptyNote(if (q.isEmpty()) S.skillsPage.noSkills else S.skillsPage.noMatches) }
        return
    }
    shown.groupBy { it.category }.forEach { (cat, list) ->
        item(key = "cat:$cat") { SectionLabel(cat.ifEmpty { S.skillsPage.other }.replace('-', ' '), Modifier.animateItem()) }
        items(list, key = { "s:${it.category}/${it.provenance}/${it.name}" }) { sk ->
            SkillRow(sk, { onOpen(sk) }, { onMenu(sk) }, { on -> model.toggle(sk, on) }, Modifier.animateItem())
        }
    }
}

private fun LazyListScope.skillsHubTab(model: SkillsModel, onOpen: (HubSkill) -> Unit) {
    item(key = "hub-search") { Field(model.hubQuery, model::setHubQuery, S.skillsPage.searchHub, skillsSearchPad) }
    if (model.hubQuery.isNotBlank() && model.hubSources.size > 1) item(key = "hub-sources") {
        val t = S.skillsPage
        LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("all") + model.hubSources, key = { it }) { id ->
                Chip(if (id == "all") t.allSources else t.source(id), model.hubSource == id, { model.setSource(id) })
            }
        }
    }
    item(key = "hub-update") {
        val t = S.skillsPage
        val busy = model.busy("update")
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            PillButton(if (busy) t.updating else t.updateAll, model::updateAll, height = 48.dp, kind = ButtonKind.Quiet, icon = Icons.refresh, enabled = !busy)
            val lines = model.lines("update")
            if (lines.isNotEmpty()) { Spacer(Modifier.height(8.dp)); LogLines(lines) }
        }
    }
    model.hubError?.let { e -> item(key = "hub-error") { ErrorRow(e) { model.setHubQuery(model.hubQuery) } } }
    item(key = "hub-label") { SectionLabel(if (model.hubQuery.isBlank()) S.skillsPage.official else S.skillsPage.results) }
    if (model.hubItems.isEmpty()) {
        item(key = "hub-empty") {
            if (model.hubLoading) Delayed { LoadingNote() } else if (model.hubError == null && model.hubQuery.isNotBlank()) EmptyNote(S.skillsPage.noMatches)
        }
        return
    }
    items(model.hubItems, key = { "h:" + it.identifier }) { h ->
        HubRow(
            h, model.isInstalled(h), model.busy(h.identifier), model.spec, { onOpen(h) },
            { if (h.trust == "community") onOpen(h) else model.install(h) }, Modifier.animateItem(),
        )
    }
}

private fun LazyListScope.skillsUpkeepTab(model: SkillsModel, onRun: () -> Unit) {
    model.curatorError?.let { e -> item(key = "cur-error") { ErrorRow(e, model::loadCurator) } }
    val cur = model.curator
    if (cur == null) {
        if (model.curatorError == null) item(key = "cur-loading") { Delayed { LoadingNote() } }
        return
    }
    item(key = "curator") { CuratorCard(cur, model.busy("curator"), model.lines("curator"), model::setCuratorPaused, onRun) }
}
