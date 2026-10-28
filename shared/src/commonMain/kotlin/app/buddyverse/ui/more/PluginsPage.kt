package app.buddyverse.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Mood
import app.buddyverse.core.LinkState
import app.buddyverse.core.api.CatalogPlugin
import app.buddyverse.design.Chip
import app.buddyverse.design.Field
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.pluginsPage
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.MorePage
import app.buddyverse.ui.PageFrame
import app.buddyverse.ui.ltr
import app.buddyverse.ui.more.plugins.AskSheet
import app.buddyverse.ui.more.plugins.CatalogItem
import app.buddyverse.ui.more.plugins.CatalogSheet
import app.buddyverse.ui.more.plugins.CrewPicker
import app.buddyverse.ui.more.plugins.FailRow
import app.buddyverse.ui.more.plugins.Later
import app.buddyverse.ui.more.plugins.LinkSheet
import app.buddyverse.ui.more.plugins.Note
import app.buddyverse.ui.more.plugins.PluginItem
import app.buddyverse.ui.more.plugins.PluginMenu
import app.buddyverse.ui.more.plugins.PluginSheet
import app.buddyverse.ui.more.plugins.PluginsModel
import app.buddyverse.ui.more.plugins.PluginsTab
import app.buddyverse.ui.more.plugins.ResultSheet
import app.buddyverse.ui.more.plugins.SuggestedItem
import app.buddyverse.ui.more.plugins.brandFree
import app.buddyverse.ui.more.plugins.pluginTitle

private val TIER_ORDER = listOf("official", "verified", "community")

/**
 * More ▸ Plugins (hermes-api.md §2.5 "Plugins and plugin catalog", §2.6 "Plugins"): per bot, what is installed (switch, update,
 * settings, keys, remove) and the catalog (search, categories, suggestions for this PC, install, install from a link).
 */
@Composable
fun PluginsPage() {
    val store = LocalStore.current
    val t = S.pluginsPage
    val s = S.current
    val model = remember(store) { PluginsModel(store) }
    model.fb = LocalFeedback.current
    model.toasts = LocalToasts.current
    model.t = t
    model.undoLabel = s.undo

    val bots by store.bots.collectAsState()
    val link by store.link.collectAsState()
    val ready = link is LinkState.Ready
    val crew = remember(bots) { bots.filterNot { it.hidden }.ifEmpty { bots }.map { it.profile to it.spec } }
    var picked by remember { mutableStateOf<String?>(null) }
    val profile = picked?.takeIf { p -> bots.any { it.profile == p } } ?: (bots.firstOrNull { it.isDefault } ?: bots.firstOrNull())?.profile ?: "default"
    val bot = bots.firstOrNull { it.profile == profile }

    var tab by remember { mutableStateOf(PluginsTab.Installed) }
    var openId by remember { mutableStateOf<String?>(null) }
    var menuId by remember { mutableStateOf<String?>(null) }
    var entry by remember { mutableStateOf<CatalogPlugin?>(null) }
    var linkOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(profile, ready) { if (ready) model.load(profile) }
    LaunchedEffect(tab, profile, ready) { if (ready && tab == PluginsTab.Browse) model.loadCatalog() }

    val catalog = model.catalog
    val mood = when {
        model.hurt -> Mood.Error
        model.busy.isNotEmpty() -> Mood.Working
        !ready -> Mood.Offline
        !model.loaded || (tab == PluginsTab.Browse && catalog == null && model.catalogError == null) -> Mood.Thinking
        else -> Mood.Idle
    }
    val categories = remember(catalog) { catalog.orEmpty().groupingBy { it.category }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
    val shown = remember(catalog, query, category) {
        val q = query.trim().lowercase()
        catalog.orEmpty().filter { (category == null || it.category == category) && (q.isEmpty() || q in it.search) }
            .sortedWith(compareBy<CatalogPlugin> { TIER_ORDER.indexOf(it.tier).let { i -> if (i < 0) TIER_ORDER.size else i } }.thenByDescending { it.stars ?: 0L })
    }
    val listed = model.listed
    val worker = bot?.spec

    Box(Modifier.fillMaxSize()) {
        PageFrame(MorePage.Plugins.label, actions = {
            if (tab == PluginsTab.Browse) IconButton(Icons.link, t.linkTitle, { linkOpen = true })
            else IconButton(Icons.add, t.browsePlugins, { tab = PluginsTab.Browse })
        }) {
            item(key = "crew") {
                CrewPicker(crew, profile, mood, model.cue) { p -> picked = p; openId = null; menuId = null; entry = null }
            }
            item(key = "count") {
                val line = if (model.loaded && !model.unsupported) t.countLine(bot?.name ?: profile, listed.count { it.on }, listed.size) else ""
                Txt(line, Theme.type.caption, Theme.colors.secondary, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), maxLines = 1)
            }
            item(key = "tabs") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(if (model.loaded) "${t.installed} ${ltr(listed.size.toString())}" else t.installed, tab == PluginsTab.Installed, { tab = PluginsTab.Installed })
                    Chip(t.browse, tab == PluginsTab.Browse, { tab = PluginsTab.Browse })
                }
            }
            when (tab) {
                PluginsTab.Installed -> {
                    val err = model.error
                    when {
                        model.unsupported -> item(key = "unsupported") { Note(s.unsupported) }
                        err != null && listed.isEmpty() -> item(key = "error") { FailRow(err, model::retry) }
                        !model.loaded -> item(key = "loading") { Later { Note(s.loading) } }
                        listed.isEmpty() -> item(key = "empty") {
                            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Note(t.noPlugins)
                                PillButton(t.browsePlugins, { tab = PluginsTab.Browse }, icon = Icons.extension)
                            }
                        }
                        else -> items(listed, key = { "p:" + it.id }) { r ->
                            PluginItem(
                                r, r.id in model.updating, onOpen = { openId = r.id }, onMenu = { menuId = r.id }, onToggle = { on -> model.toggle(r, on) },
                                modifier = Modifier.animateItem(fadeInSpec = Motion.fade(), placementSpec = Motion.smooth(), fadeOutSpec = Motion.fade()),
                            )
                        }
                    }
                }
                PluginsTab.Browse -> {
                    item(key = "search") { Field(query, { query = it }, t.searchHint, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                    if (categories.isNotEmpty()) item(key = "categories") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item(key = "all") { Chip(t.all, category == null, { category = null }) }
                            items(categories, key = { it }) { k -> Chip(t.category(k), category == k, { category = if (category == k) null else k }) }
                        }
                    }
                    if (query.isBlank() && category == null && model.suggested.isNotEmpty()) {
                        item(key = "suggested-label") { SectionLabel(t.suggested) }
                        items(model.suggested, key = { "s:" + it.name }) { sg ->
                            SuggestedItem(sg, model.installed(sg.name), sg.name in model.busy, worker,
                                onOpen = {
                                    entry = catalog?.firstOrNull { it.name == sg.name } ?: CatalogPlugin(
                                        sg.name, sg.description, sg.tier, "general", "", "", null, emptyList(), emptyList(), emptyList(), null, null,
                                    )
                                },
                                onInstall = { model.install(sg.name, sg.name, brandFree(sg.title)) })
                        }
                        item(key = "all-label") { SectionLabel(t.all) }
                    }
                    val catErr = model.catalogError
                    when {
                        catalog == null && catErr != null -> item(key = "catalog-error") { FailRow(t.catalogFailed, model::loadCatalog) }
                        catalog == null -> item(key = "catalog-loading") { Later { Note(s.loading) } }
                        shown.isEmpty() -> item(key = "no-matches") { Note(t.noMatches) }
                        else -> items(shown, key = { "c:" + it.name }) { c ->
                            CatalogItem(c, model.installed(c.name), c.name in model.busy, worker, onOpen = { entry = c },
                                onInstall = { model.install(c.name, c.name, pluginTitle(c.name)) })
                        }
                    }
                }
            }
        }

        PluginSheet(model, openId?.let(model::row), worker, mood, onDismiss = { openId = null }, onRemove = { r -> openId = null; model.remove(r) })
        PluginMenu(menuId?.let(model::row), onDismiss = { menuId = null }, onView = { openId = it.id }, onToggle = { model.toggle(it, !it.on) },
            onUpdate = { model.update(it) }, onRemove = { model.remove(it) })
        CatalogSheet(model, entry, worker, onDismiss = { entry = null })
        LinkSheet(linkOpen, model, worker, onDismiss = { linkOpen = false })
        AskSheet(model)
        ResultSheet(model)
    }
}
