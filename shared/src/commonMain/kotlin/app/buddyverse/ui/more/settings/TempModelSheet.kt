package app.buddyverse.ui.more.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.buddyverse.core.ModelChoice
import app.buddyverse.core.TempChats
import app.buddyverse.core.api.ModelOptions
import app.buddyverse.core.api.pickerOptions
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.PressRow
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.chat
import app.buddyverse.i18n.settingsPage
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.ltr
import app.buddyverse.ui.modelLabel
import kotlinx.coroutines.CancellationException

/** Settings ▸ Temp chat model: any model the chat model sheet offers (free-only OpenRouter); default Sonnet 5.5. */
@Composable
fun TempModelSheet(visible: Boolean, profile: String, onDismiss: () -> Unit) {
    val store = LocalStore.current
    val fb = LocalFeedback.current
    val s = S.current
    val t = S.settingsPage
    val sc = S.chat
    val c = Theme.colors
    val prefs by store.prefs.collectAsState()
    var opts by remember { mutableStateOf<ModelOptions?>(null) }
    var failed by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(visible, profile) {
        if (!visible) return@LaunchedEffect
        failed = false
        try { opts = store.api.pickerOptions(profile, null) } catch (e: CancellationException) { throw e } catch (e: Exception) { failed = opts == null }
    }

    // Automatic is the default: picking it stores nothing.
    fun choose(m: ModelChoice) { fb.haptic(Haptic.Confirm); store.setPrefs(prefs.copy(tempModel = m.takeIf { it != TempChats.AUTO })); onDismiss() }

    Sheet(visible, onDismiss, title = t.tempModel, heightFraction = .85f) {
        Txt(t.tempModelNote, Theme.type.sub, c.secondary, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Field(query, { query = it }, sc.searchModels, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), mono = false)
        val o = opts
        val auto = prefs.tempModel == null || prefs.tempModel == TempChats.AUTO
        val picked = if (auto) TempChats.AUTO else prefs.tempModel!!
        val autoNow = o?.let { TempChats.resolve(null, it) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (query.isBlank()) item(key = "auto") {
                PressRow({ choose(TempChats.AUTO) }, minHeight = 64.dp, label = t.tempAuto) {
                    Column(Modifier.weight(1f)) {
                        Txt(t.tempAuto, Theme.type.callout, if (auto) c.accent else c.text, maxLines = 1)
                        Txt(autoNow?.let { ltr(modelLabel(it.model) ?: it.model) } ?: t.tempAutoNote, Theme.type.sub, c.secondary, maxLines = 2)
                    }
                    if (auto) Icon(Icons.check, c.accent, 22.dp)
                }
            }
            when {
                o == null && failed -> item(key = "err") { Txt(s.unsupported, Theme.type.sub, c.tertiary, Modifier.padding(20.dp)) }
                o == null -> item(key = "loading") { Txt(s.loading, Theme.type.sub, c.tertiary, Modifier.padding(20.dp)) }
                else -> for (p in o.providers) {
                    val q = query.trim().lowercase()
                    val all = (p.featured + p.models).distinct()
                    val list = when {
                        q.isNotEmpty() -> all.filter { q in it.lowercase() || q in modelLabel(it).orEmpty().lowercase() }
                        p.slug in expanded -> all
                        else -> p.featured.ifEmpty { p.models.take(8) }.distinct()
                            .let { l -> if (picked.provider == p.slug && picked.model in all && picked.model !in l) listOf(picked.model) + l else l }
                    }
                    if (list.isEmpty()) continue
                    item(key = "h-${p.slug}") { SectionLabel(p.name) }
                    items(list, key = { "m-${p.slug}-$it" }) { m ->
                        val on = picked.provider == p.slug && picked.model == m
                        PressRow({ choose(ModelChoice(p.slug, m)) }, minHeight = 56.dp, label = m) {
                            Txt(ltr(modelLabel(m) ?: m), Theme.type.callout, if (on) c.accent else c.text, Modifier.weight(1f), maxLines = 1)
                            if (on) Icon(Icons.check, c.accent, 22.dp)
                        }
                    }
                    if (q.isEmpty() && p.slug !in expanded && all.size > list.size) item(key = "more-${p.slug}") {
                        PressRow({ expanded = expanded + p.slug }, minHeight = 48.dp, label = sc.showAll(all.size)) {
                            Txt(sc.showAll(all.size), Theme.type.sub, c.accent, maxLines = 1)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.expandMore, c.accent, 20.dp)
                        }
                    }
                }
            }
        }
    }
}
