package app.buddyverse.ui.more.skills

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.obj
import app.buddyverse.core.str
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Field
import app.buddyverse.design.Hairline
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.skillsPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

// Every sheet here closes three ways (back, scrim, its 48dp ×) and is placed last in the page's full-size Box (design rule 4).

/** Keeps the last non-null value so a sheet still has content while it slides away. */
@Composable
private fun <T : Any> rememberLast(v: T?): T? {
    val last = remember { arrayOfNulls<Any>(1) }
    if (v != null) last[0] = v
    @Suppress("UNCHECKED_CAST")
    return last[0] as T?
}

/** Delete for the user's own skills (archive), Uninstall for hub ones; bundled and external skills can only be switched off. */
@Composable
fun removeLabel(sk: Skill): String? = when (sk.provenance) {
    "agent" -> S.current.delete
    "hub" -> S.skillsPage.uninstall
    else -> null
}

/** One skill: what it is, its switch, and its SKILL.md (frontmatter hidden). */
@Composable
fun SkillSheet(model: SkillsModel, skill: Skill?, onDismiss: () -> Unit, onEdit: (Skill) -> Unit, onRemove: (Skill) -> Unit) {
    val shown = rememberLast(skill)
    Sheet(skill != null, onDismiss, title = shown?.name, heightFraction = .86f) {
        val sk = shown ?: return@Sheet
        val t = S.skillsPage
        val s = S.current
        val c = Theme.colors
        val live = model.skills.firstOrNull { it.name == sk.name } ?: sk
        var md by remember(sk.name, model.profile) { mutableStateOf<String?>(null) }
        var err by remember(sk.name, model.profile) { mutableStateOf<String?>(null) }
        LaunchedEffect(sk.name, model.profile) {
            try { md = model.content(sk.name) } catch (e: CancellationException) { throw e } catch (e: Exception) { err = unbrand(e.message ?: s.error) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            if (sk.description.isNotBlank()) Txt(sk.description, Theme.type.callout, c.secondary)
            val meta = listOfNotNull(
                sk.category.ifBlank { null }, provenanceLabel(sk.provenance, t) ?: t.builtIn.takeIf { sk.provenance == "bundled" },
                sk.usage.takeIf { it > 0 }?.let(t.used),
            )
            if (meta.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Txt(meta.joinToString(" · "), Theme.type.caption, c.tertiary) }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(t.active, Theme.type.headline, modifier = Modifier.weight(1f), maxLines = 1)
                Toggle(live.enabled, { model.toggle(live, it) }, sk.name)
            }
            Hairline()
            Spacer(Modifier.height(12.dp))
            val text = md
            val e = err
            when {
                // Rendered markdown, in the app's words (no engine branding).
                text != null -> app.buddyverse.ui.chat.MdText(remember(text) { unbrand(skillBody(text)) }, Theme.type.sub, c.text, c.surface2)
                e != null -> Txt(e, Theme.type.sub, c.errorText)
                else -> Delayed { Txt(s.loading, Theme.type.sub, c.secondary) }
            }
            Spacer(Modifier.height(12.dp))
        }
        val remove = removeLabel(live)
        if (live.editable || remove != null) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (live.editable) PillButton(s.edit, { onEdit(live) }, Modifier.weight(1f), kind = ButtonKind.Quiet, icon = Icons.edit)
                if (remove != null) PillButton(remove, { onRemove(live) }, Modifier.weight(1f), kind = ButtonKind.Outline, icon = Icons.delete)
            }
        }
    }
}

/** Long-press menu of a skill row (design §3.7: the row shows one control, the rest live here). */
@Composable
fun SkillMenu(skill: Skill?, onDismiss: () -> Unit, onView: (Skill) -> Unit, onEdit: (Skill) -> Unit, onToggle: (Skill) -> Unit, onRemove: (Skill) -> Unit) {
    val shown = rememberLast(skill)
    Sheet(skill != null, onDismiss, title = shown?.name) {
        val sk = shown ?: return@Sheet
        val t = S.skillsPage
        MenuRow(Icons.visibility, t.view) { onDismiss(); onView(sk) }
        if (sk.editable) MenuRow(Icons.edit, S.current.edit) { onDismiss(); onEdit(sk) }
        MenuRow(if (sk.enabled) Icons.pause else Icons.playArrow, if (sk.enabled) t.turnOff else t.turnOn) { onDismiss(); onToggle(sk) }
        removeLabel(sk)?.let { MenuRow(Icons.delete, it, danger = true) { onDismiss(); onRemove(sk) } }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = Theme.colors
    PressRow(onClick, minHeight = 56.dp, label = label) {
        Icon(icon, if (danger) c.error else c.text)
        Spacer(Modifier.width(16.dp))
        Txt(label, Theme.type.body, if (danger) c.errorText else c.text, maxLines = 1)
    }
}

sealed interface EditTarget {
    data object New : EditTarget
    data class Existing(val skill: Skill) : EditTarget
}

/** Create a skill (name, one-line description, category, instructions) or rewrite an existing SKILL.md in full. */
@Composable
fun SkillEditor(model: SkillsModel, target: EditTarget?, onDismiss: () -> Unit) {
    val shown = rememberLast(target)
    val title = when (shown) { is EditTarget.Existing -> shown.skill.name; else -> S.skillsPage.newSkill }
    Sheet(target != null, onDismiss, title = title, heightFraction = .92f) {
        when (shown) {
            EditTarget.New -> NewSkillForm(model, onDismiss)
            is EditTarget.Existing -> EditSkillForm(model, shown.skill, onDismiss)
            null -> Unit
        }
    }
}

@Composable
private fun ColumnScope.NewSkillForm(model: SkillsModel, onDone: () -> Unit) {
    val t = S.skillsPage
    val s = S.current
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Field(name, { name = cleanSkillName(it) }, t.name, mono = true)
        Field(desc, { desc = it }, t.description)
        Field(cat, { cat = cleanSkillName(it) }, t.category, mono = true)
        Field(body, { body = it }, t.instructions, singleLine = false, minHeight = 220.dp)
        err?.let { Txt(it, Theme.type.caption, Theme.colors.errorText) }
    }
    PillButton(
        if (busy) t.saving else s.create,
        {
            busy = true; err = null
            scope.launch { val e = model.create(name, desc, cat, body); busy = false; if (e == null) onDone() else err = e }
        },
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
        icon = Icons.check, enabled = !busy && name.isNotBlank() && desc.isNotBlank() && body.isNotBlank(), haptic = Haptic.Confirm,
    )
}

@Composable
private fun ColumnScope.EditSkillForm(model: SkillsModel, skill: Skill, onDone: () -> Unit) {
    val t = S.skillsPage
    val s = S.current
    var text by remember(skill.name) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(skill.name) {
        try { text = model.content(skill.name) } catch (e: CancellationException) { throw e } catch (e: Exception) { err = unbrand(e.message ?: s.error) }
    }
    val cur = text
    if (cur == null) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(20.dp)) {
            val e = err
            if (e != null) Txt(e, Theme.type.sub, Theme.colors.errorText) else Delayed { Txt(s.loading, Theme.type.sub, Theme.colors.secondary) }
        }
        return
    }
    Field(cur, { text = it }, t.instructions, Modifier.weight(1f).padding(horizontal = 20.dp), singleLine = false, mono = true)
    err?.let { Txt(it, Theme.type.caption, Theme.colors.errorText, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) }
    PillButton(
        if (busy) t.saving else s.save,
        {
            busy = true; err = null
            scope.launch { val e = model.save(skill.name, cur); busy = false; if (e == null) onDone() else err = e }
        },
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
        icon = Icons.check, enabled = !busy && cur.isNotBlank(), haptic = Haptic.Confirm,
    )
}

/** A hub skill before installing: where it comes from, the safety check, and its SKILL.md. */
@Composable
fun HubSheet(model: SkillsModel, item: HubSkill?, onDismiss: () -> Unit, onUninstall: (String) -> Unit) {
    val shown = rememberLast(item)
    Sheet(item != null, onDismiss, title = shown?.name, heightFraction = .86f) {
        val h = shown ?: return@Sheet
        val t = S.skillsPage
        val s = S.current
        val c = Theme.colors
        var preview by remember(h.identifier) { mutableStateOf<JsonObject?>(null) }
        var previewDone by remember(h.identifier) { mutableStateOf(false) }
        var scan by remember(h.identifier) { mutableStateOf<JsonObject?>(null) }
        var scanDone by remember(h.identifier) { mutableStateOf(false) }
        LaunchedEffect(h.identifier) {
            launch { preview = model.preview(h); previewDone = true }
            scan = model.scan(h); scanDone = true
        }
        val p = preview
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            if (h.description.isNotBlank()) Txt(h.description, Theme.type.callout, c.secondary)
            val meta = listOfNotNull(
                h.source.takeIf { it.isNotBlank() }?.let(t::source), trustLabel(h.trust, t),
                h.repo.takeIf { it.isNotBlank() && h.trust != "builtin" }, p.list("files").size.takeIf { it > 0 }?.let(t.files),
            )
            if (meta.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Txt(meta.joinToString(" · "), Theme.type.caption, c.tertiary) }
            if (h.tags.isNotEmpty()) Txt(h.tags.joinToString("  ") { "#$it" }, Theme.type.caption, c.tertiary, maxLines = 2)
            Spacer(Modifier.height(12.dp))
            ScanBox(scan, scanDone)
            Spacer(Modifier.height(12.dp))
            val md = p.str("skill_md")
            if (md != null) Txt(skillBody(md), Theme.type.sub) else if (!previewDone) Delayed { Txt(s.loading, Theme.type.sub, c.secondary) }
            Spacer(Modifier.height(12.dp))
        }
        val mod = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp)
        when {
            model.busy(h.identifier) -> PillButton(t.installing, {}, mod, enabled = false)
            model.isInstalled(h) -> PillButton(t.uninstall, { onUninstall(h.name) }, mod, kind = ButtonKind.Outline, icon = Icons.delete)
            else -> PillButton(t.install, { model.install(h); onDismiss() }, mod, icon = Icons.add, enabled = scan.str("policy") != "block", haptic = Haptic.Confirm)
        }
    }
}

@Composable
private fun ScanBox(scan: JsonObject?, done: Boolean) {
    val t = S.skillsPage
    val c = Theme.colors
    if (scan == null) { if (!done) Delayed { Txt(t.scanning, Theme.type.caption, c.secondary) }; return }
    val (icon, color, label) = when (scan.str("policy")) {
        "allow" -> Triple(Icons.checkCircleFilled, c.success, t.scanAllow)
        "ask" -> Triple(Icons.warningFilled, c.needsYouText, t.scanAsk)
        "block" -> Triple(Icons.errorFilled, c.errorText, t.scanBlock)
        else -> return
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface2).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, color, 22.dp)
            Spacer(Modifier.width(8.dp))
            Txt(label, Theme.type.headline, color, maxLines = 1)
        }
        scan.str("summary")?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(4.dp)); Txt(unbrand(it), Theme.type.caption, c.secondary) }
        scan.list("findings").mapNotNull { it.obj }.take(5).forEach { f ->
            val where = listOfNotNull(f.str("file"), f.long("line")?.toString()).joinToString(":")
            Spacer(Modifier.height(4.dp))
            Txt(
                listOfNotNull(f.str("severity"), f.str("description")?.let(::unbrand)).joinToString(" · ") + if (where.isNotEmpty()) " ($where)" else "",
                Theme.type.caption, c.secondary, maxLines = 3,
            )
        }
    }
}

/** A destructive step asks first: title (with its ×), one plain line, Cancel and the red action (REJECT haptic, design §5.2). */
data class Confirm(val title: String, val body: String, val action: String, val run: () -> Unit)

@Composable
fun ConfirmSheet(confirm: Confirm?, onDismiss: () -> Unit) {
    val shown = rememberLast(confirm)
    Sheet(confirm != null, onDismiss, title = shown?.title) {
        val cf = shown ?: return@Sheet
        Txt(cf.body, Theme.type.callout, Theme.colors.secondary, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(S.current.cancel, onDismiss, Modifier.weight(1f), kind = ButtonKind.Quiet)
            PillButton(cf.action, { onDismiss(); cf.run() }, Modifier.weight(1f), kind = ButtonKind.Danger, haptic = Haptic.Reject)
        }
    }
}
