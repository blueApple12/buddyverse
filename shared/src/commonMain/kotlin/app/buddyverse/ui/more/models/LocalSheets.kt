package app.buddyverse.ui.more.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.buddyverse.core.Bot
import app.buddyverse.core.api.LocalCatalogEntry
import app.buddyverse.core.api.HubFiles
import app.buddyverse.core.api.HubHit
import app.buddyverse.core.api.LocalModelJob
import app.buddyverse.core.api.modelBytes
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Card
import app.buddyverse.design.Chip
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.modelsPage
import app.buddyverse.ui.ltr
import kotlinx.coroutines.launch

/** A long local job: what it does, a springy bar, pause/resume. */
@Composable
fun JobRow(m: ModelsModel, j: LocalModelJob, modifier: Modifier = Modifier) {
    val t = S.modelsPage
    val u = S.current
    val c = Theme.colors
    Row(modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(j.target.ifBlank { t.localEngine }, Theme.type.headline, maxLines = 1)
            val eta = j.etaSeconds?.takeIf { it > 0 }?.let { s -> if (s >= 3600) u.unit(s / 3600, "h") + " " + u.unit((s % 3600) / 60, "m") else if (s >= 60) u.unit(s / 60, "m") else u.unit(s, "s") }
            Txt(listOfNotNull(if (j.status == "paused") t.paused else j.detail.ifBlank { null }, j.percent?.let { ltr("$it%") }, eta?.let(::ltr)).joinToString(" · "),
                Theme.type.sub, c.secondary, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Meter(j.percent?.div(100f), if (j.status == "paused") c.tertiary else c.working)
        }
        Spacer(Modifier.width(8.dp))
        val busy = "job:${j.id}" in m.busy
        when {
            j.canPause -> IconButton(Icons.pause, t.pause, { m.pause(j) }, enabled = !busy)
            j.canResume -> IconButton(Icons.playArrow, t.resume, { m.resume(j) }, enabled = !busy)
            else -> Unit
        }
    }
}

private enum class Tab { Catalog, Search, PcFile }

/** Get a model onto the PC: the catalog priced for this PC (one-tap setup), a Hugging Face search, or a file already on the PC. */
@Composable
fun CatalogSheet(m: ModelsModel, open: Boolean, bot: Bot?, onDismiss: () -> Unit) {
    val t = S.modelsPage
    var tab by remember { mutableStateOf(Tab.Catalog) }
    LaunchedEffect(open) { if (open) m.loadCatalog() }
    Sheet(open, onDismiss, title = t.getModel, heightFraction = .92f) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(t.forThisPc, tab == Tab.Catalog, { tab = Tab.Catalog }, icon = Icons.autoAwesome)
            Chip(t.searchHub, tab == Tab.Search, { tab = Tab.Search }, icon = Icons.search)
            Chip(t.fromPc, tab == Tab.PcFile, { tab = Tab.PcFile }, icon = Icons.folder)
        }
        when (tab) {
            Tab.Catalog -> CatalogList(m, bot, onDismiss)
            Tab.Search -> HubSearch(m)
            Tab.PcFile -> Sideload(m, onDismiss)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CatalogList(m: ModelsModel, bot: Bot?, onDismiss: () -> Unit) {
    val t = S.modelsPage
    val c = Theme.colors
    val list = m.catalog
    val running = m.jobs.filter { it.active }
    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        if (list == null) {
            item(key = "wait") { if (m.catalogError != null) PageMessage(m.catalogError.orEmpty(), S.current.retry, Icons.refresh) { m.loadCatalog(force = true) } else Thinking(bot) }
            return@LazyColumn
        }
        val best = list.firstOrNull { it.recommended }
        if (best != null && !best.downloaded && running.none { it.kind == "quickstart" }) item(key = "quick") {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Txt(t.bestForPc.uppercase(), Theme.type.caps, c.secondary, maxLines = 1)
                Txt(best.name, Theme.type.title2, maxLines = 1)
                Txt(listOf(best.sizeLabel, best.fitSummary).filter { it.isNotBlank() }.joinToString(" · "), Theme.type.sub, c.secondary, maxLines = 3)
                Spacer(Modifier.height(12.dp))
                PillButton(t.setUpForMe, { m.quickstart(); onDismiss() }, Modifier.fillMaxWidth(), icon = Icons.bolt, haptic = Haptic.Confirm,
                    enabled = "quickstart" !in m.busy)
            }
        }
        items(list, key = { "c:" + it.id }) { cm -> CatalogRow(m, cm, running.any { it.modelId == cm.id }) }
    }
}

@Composable
private fun CatalogRow(m: ModelsModel, cm: LocalCatalogEntry, downloading: Boolean) {
    val t = S.modelsPage
    val c = Theme.colors
    PressRow({ if (cm.fits && !cm.downloaded && !cm.needsEngine && !downloading) m.download(cm) }, minHeight = 76.dp, label = cm.name) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(cm.name, Theme.type.headline, modifier = Modifier.weight(1f, fill = false), maxLines = 1)
                if (cm.recommended) { Spacer(Modifier.width(8.dp)); Pill(t.recommended, c.accent) }
            }
            Txt(listOf(cm.sizeLabel, cm.contextLabel.takeIf { it.isNotBlank() }?.let(t.contextOf), t.seesImages.takeIf { cm.vision })
                .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "), Theme.type.sub, c.secondary, maxLines = 1)
            val why = when {
                cm.needsEngine -> t.needsNewerEngine
                !cm.fits -> cm.fitSummary.ifBlank { t.tooBig }
                else -> cm.fitSummary
            }
            if (why.isNotBlank()) Txt(why, Theme.type.caption, if (!cm.fits || cm.needsEngine) c.needsYouText else c.secondary, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        when {
            cm.downloaded -> Check()
            downloading -> Pill(t.downloading, c.working)
            cm.fits && !cm.needsEngine -> PillButton(t.get, { m.download(cm) }, height = 48.dp, kind = ButtonKind.Quiet, enabled = "dl:${cm.id}" !in m.busy)
            else -> Unit
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.HubSearch(m: ModelsModel) {
    val t = S.modelsPage
    val c = Theme.colors
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<HubHit>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var openRepo by remember { mutableStateOf<String?>(null) }
    val files = remember { mutableStateMapOf<String, List<HubFiles>?>() }
    fun go() {
        if (query.isBlank() || searching) return
        searching = true; err = null
        scope.launch {
            m.searchHub(query).onSuccess { hits = it }.onFailure { err = m.errorOf(it) }
            searching = false
        }
    }
    Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Field(query, { query = it }, t.searchHubHint, Modifier.weight(1f), mono = true)
        IconButton(Icons.search, t.searchHub, ::go, enabled = !searching && query.isNotBlank())
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        err?.let { e -> item(key = "err") { PageMessage(e) } }
        if (searching) item(key = "wait") { Meter(null, c.working, Modifier.padding(20.dp)) }
        hits?.let { h ->
            if (h.isEmpty()) item(key = "none") { PageMessage(t.noMatches) }
            h.forEach { hit ->
                item(key = "h:" + hit.repo) {
                    InfoRow(hit.repo, t.downloads(hit.downloads) + if (hit.gated) " · ${t.gated}" else "", {
                        openRepo = if (openRepo == hit.repo) null else hit.repo
                        if (openRepo == hit.repo && hit.repo !in files) scope.launch {
                            files[hit.repo] = null
                            m.repoFiles(hit.repo).onSuccess { files[hit.repo] = it }.onFailure { x -> files.remove(hit.repo); err = m.errorOf(x) }
                        }
                    }, mono = true)
                }
                if (openRepo == hit.repo) {
                    val fs = files[hit.repo]
                    if (fs == null) item(key = "fw:" + hit.repo) { Meter(null, c.working, Modifier.padding(horizontal = 32.dp, vertical = 12.dp)) }
                    else if (fs.isEmpty()) item(key = "fn:" + hit.repo) { Txt(t.noFiles, Theme.type.sub, c.secondary, Modifier.padding(horizontal = 32.dp, vertical = 12.dp)) }
                    else items(fs, key = { "f:${hit.repo}:${it.label}" }) { f ->
                        InfoRow(f.label, listOf(modelBytes(f.totalBytes), t.fit(f.fit)).filter { it.isNotBlank() }.joinToString(" · "), {}, Modifier.padding(start = 16.dp),
                            mono = true, subColor = if (f.fit == "too-big") c.needsYouText else c.secondary, trail = {
                                PillButton(t.get, { m.downloadBrowsed(hit.repo, f) }, height = 48.dp, kind = ButtonKind.Quiet,
                                    enabled = f.fit != "too-big" && "dl:${hit.repo}/${f.label}" !in m.busy)
                            })
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Sideload(m: ModelsModel, onDismiss: () -> Unit) {
    val t = S.modelsPage
    val c = Theme.colors
    var path by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val busy = "sideload" in m.busy
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Field(path, { path = it; err = null }, t.pcPath, mono = true)
        err?.let { Txt(it, Theme.type.sub, c.errorText, maxLines = 4) }
        PillButton(if (busy) t.saving else t.addFile, { m.sideload(path) { r -> if (r == null) { path = ""; onDismiss() } else err = r } },
            Modifier.fillMaxWidth(), icon = Icons.add, enabled = !busy && path.isNotBlank(), haptic = Haptic.Confirm)
    }
}

/** One model file on the PC: make it the default, unload it from the GPU, or delete it (with undo). */
@Composable
fun LocalFileSheet(m: ModelsModel, id: String?, onDismiss: () -> Unit) {
    val t = S.modelsPage
    val s = S.current
    val c = Theme.colors
    val shown = rememberLast(id)
    val st = m.local
    val f = st?.files?.firstOrNull { it.id == shown }
    Sheet(id != null, onDismiss, title = shown) {
        if (f == null) return@Sheet
        val active = st.activeModelId == f.id
        val loaded = f.id in st.loaded
        val busy = "local:${f.id}" in m.busy
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(ltr(f.sizeLabel), Theme.type.sub, c.secondary, maxLines = 1)
                if (active) Pill(t.inUse, c.accent)
                if (loaded) Pill(t.loaded, c.success)
            }
            if (!active) PillButton(t.useIt, { m.useLocal(f.id); onDismiss() }, Modifier.fillMaxWidth(), icon = Icons.checkCircle, enabled = !busy, haptic = Haptic.Confirm)
            if (loaded) PillButton(t.unload, { m.eject(f.id) }, Modifier.fillMaxWidth(), kind = ButtonKind.Outline, icon = Icons.powerSettingsNew, enabled = !busy)
            PillButton(s.delete, { onDismiss(); m.deleteLocal(f.id) }, Modifier.fillMaxWidth(), kind = ButtonKind.Danger, icon = Icons.delete, haptic = Haptic.Reject)
            Spacer(Modifier.height(4.dp))
        }
    }
}
