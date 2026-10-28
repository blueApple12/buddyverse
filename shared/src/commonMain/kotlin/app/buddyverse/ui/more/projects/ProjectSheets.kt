package app.buddyverse.ui.more.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddyPaths
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.Bot
import app.buddyverse.core.BotStatus
import app.buddyverse.core.api.Project
import app.buddyverse.core.nowMillis
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.projectsPage

/** A project's color: its own, else one picked from its name (same rule as the Bots stage). */
fun projectColor(p: Project): Color = BuddyColors.parse(p.color) ?: BuddyColors.forName(p.title)

fun BotStatus.toMood(): Mood = when (this) {
    BotStatus.Idle -> Mood.Idle
    BotStatus.Working -> Mood.Working
    BotStatus.NeedsYou -> Mood.NeedsYou
    BotStatus.Error -> Mood.Error
    BotStatus.Offline -> Mood.Offline
}

/** The folder-blob badge (design §3.1) with the owner bot sitting at its bottom-end corner, alive. */
@Composable
fun ProjectBlob(color: Color, owner: BuddySpec?, ownerMood: Mood = Mood.Idle, size: Dp = 48.dp, cue: BuddyCue? = null) {
    Box(Modifier.size(size + 10.dp)) {
        Box(
            Modifier.size(size).drawWithCache {
                val body = BuddyPaths.scaled(BuddyShape.Blob, this.size.width)
                val fill = color.copy(alpha = .2f)
                onDrawBehind { drawPath(body, fill) }
            },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.folderFilled, color, size * .52f) }
        if (owner != null) Buddy(owner, ownerMood, size * .46f, Modifier.align(Alignment.BottomEnd), cue = cue, interactive = false)
    }
}

private class Last<T> { var v: T? = null }

/** The latest non-null [value], so a closing sheet keeps showing what it showed. */
@Composable
private fun <T : Any> rememberLast(value: T?): T? {
    val box = remember { Last<T>() }
    if (value != null) box.v = value
    return box.v
}

/** Section caps inside a padded sheet (SectionLabel brings its own 20dp inset). */
@Composable
private fun Label(text: String) = Txt(text.uppercase(), Theme.type.caps, Theme.colors.secondary, Modifier.padding(top = 18.dp, bottom = 8.dp), maxLines = 1)

@Composable
private fun MenuRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = Theme.colors
    PressRow(onClick, minHeight = 56.dp, label = label) {
        Icon(icon, if (danger) c.error else c.text)
        Spacer(Modifier.width(16.dp))
        Txt(label, Theme.type.body, if (danger) c.errorText else c.text, maxLines = 1)
    }
}

/** Long press on a row: Open, Edit, start chats here, Archive / Restore, Delete. Every row closes the sheet first. */
@Composable
fun ProjectMenuSheet(
    p: Project?,
    archived: Boolean,
    current: Boolean,
    onDismiss: () -> Unit,
    onOpen: (Project) -> Unit,
    onEdit: (Project) -> Unit,
    onCurrent: (Project, Boolean) -> Unit,
    onArchive: (Project) -> Unit,
    onDelete: (Project) -> Unit,
) {
    val shown = rememberLast(p)
    val s = S.current
    val t = S.projectsPage
    Sheet(p != null, onDismiss, title = shown?.title) {
        val pr = shown ?: return@Sheet
        pr.primaryPath?.let { Txt(it, Theme.type.code, Theme.colors.secondary, Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp), maxLines = 2) }
        MenuRow(Icons.folder, s.open) { onDismiss(); onOpen(pr) }
        MenuRow(Icons.edit, s.edit) { onDismiss(); onEdit(pr) }
        if (!archived) MenuRow(if (current) Icons.pushPinFilled else Icons.pushPin, if (current) t.stopCurrent else t.makeCurrent) {
            onDismiss(); onCurrent(pr, !current)
        }
        MenuRow(if (archived) Icons.unarchive else Icons.archive, if (archived) t.restore else s.archive) { onDismiss(); onArchive(pr) }
        MenuRow(Icons.delete, s.delete, danger = true) { onDismiss(); onDelete(pr) }
    }
}

/** Destructive confirm (design rule): Cancel and the red Delete with the reject haptic. */
@Composable
fun DeleteProjectSheet(p: Project?, onDismiss: () -> Unit, onConfirm: (Project) -> Unit) {
    val shown = rememberLast(p)
    val s = S.current
    val t = S.projectsPage
    Sheet(p != null, onDismiss, title = shown?.let { t.deleteTitle(it.title) }) {
        Txt(t.deleteBody, Theme.type.callout, Theme.colors.secondary, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(s.cancel, onDismiss, Modifier.weight(1f), kind = ButtonKind.Quiet)
            PillButton(s.deleteForGood, { onDismiss(); shown?.let(onConfirm) }, Modifier.weight(1f), kind = ButtonKind.Danger, haptic = Haptic.Reject)
        }
    }
}

/** What the editor is open for. [n] makes every opening a fresh form. */
sealed interface EditorTarget {
    data class New(val owner: String, val n: Long = nowMillis()) : EditorTarget
    data class Edit(val key: String, val n: Long = nowMillis()) : EditorTarget
}

/** The vivid part of the buddy palette (no black, gray or cream): one 48dp swatch each. */
private val SWATCHES = BuddyColors.palette.subList(2, 10)

/**
 * New project (name, folder or a repo the PC knows, description, color, whose) or edit one (name, description, color,
 * plus its folders: tap one to make it the main folder, × removes it, add another below).
 */
@Composable
fun ProjectEditor(m: ProjectsModel, target: EditorTarget?, bots: List<Bot>, onDismiss: () -> Unit) {
    val shown = rememberLast(target)
    val s = S.current
    val t = S.projectsPage
    val c = Theme.colors
    val editing = (shown as? EditorTarget.Edit)?.let { m.find(it.key) }
    val defaultOwner = bots.firstOrNull { it.isDefault }?.profile ?: bots.firstOrNull()?.profile.orEmpty()
    var d by remember(shown) {
        mutableStateOf(editing?.let(::draftOf) ?: ProjectDraft(owner = (shown as? EditorTarget.New)?.owner ?: defaultOwner))
    }
    var err by remember(shown) { mutableStateOf<String?>(null) }
    var busy by remember(shown) { mutableStateOf(false) }
    var newFolder by remember(shown) { mutableStateOf("") }
    var hop by remember { mutableStateOf<BuddyCue?>(null) }
    val isNew = shown is EditorTarget.New
    if (isNew) LaunchedEffect(d.owner) { if (d.owner.isNotEmpty()) m.loadRepos(d.owner) }
    val owner = bots.firstOrNull { it.profile == (editing?.profile ?: d.owner) }
    val color = BuddyColors.parse(d.color) ?: BuddyColors.forName(d.name.ifBlank { folderName(d.folder) }.ifBlank { "project" })
    fun submit() {
        err = null; busy = true
        val done: (String?) -> Unit = { e -> busy = false; if (e == null) onDismiss() else { err = e } }
        if (editing != null) m.save(editing, d, done) else m.create(d, done)
    }

    Sheet(target != null, onDismiss, title = if (isNew) t.newProject else t.editProject) {
        if (shown is EditorTarget.Edit && editing == null) return@Sheet
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProjectBlob(color, owner?.spec, owner?.status?.toMood() ?: Mood.Idle, 64.dp, cue = hop)
                Spacer(Modifier.width(14.dp))
                Txt(d.name.ifBlank { folderName(d.folder) }.ifBlank { t.newProject }, Theme.type.title2, modifier = Modifier.weight(1f), maxLines = 2)
            }
            Spacer(Modifier.height(16.dp))
            Field(d.name, { d = d.copy(name = it); err = null }, t.name)
            if (isNew) {
                Spacer(Modifier.height(10.dp))
                Field(d.folder, { d = d.copy(folder = it) }, t.folder, mono = true)
                val known = m.all.flatMap { it.folders + listOfNotNull(it.primaryPath) }
                val picks = m.repos.filter { r -> known.none { samePath(it, r.root) } }.take(6)
                if (picks.isNotEmpty()) {
                    Label(t.onYourPc)
                    picks.forEach { r ->
                        val on = samePath(d.folder, r.root)
                        Row(
                            Modifier.fillMaxWidth().height(56.dp).clip(CircleShape).background(if (on) c.surface2 else Color.Transparent)
                                .squish({
                                    d = d.copy(folder = r.root, name = d.name.ifBlank { r.label })
                                    hop = BuddyCue(nowMillis(), OneShot.Hop)
                                }, scale = .97f, haptic = Haptic.Tick, label = r.label)
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (on) Icons.checkCircleFilled else Icons.folder, if (on) c.accent else c.secondary, 22.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Txt(r.label, Theme.type.callout, maxLines = 1)
                                Txt(r.root, Theme.type.code, c.tertiary, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Field(d.description, { d = d.copy(description = it) }, t.description, singleLine = false)

            Label(t.color)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SWATCHES.forEachIndexed { i, sw ->
                    val v = hex(sw)
                    val on = d.color?.let { BuddyColors.parse(it) } == sw
                    Box(
                        Modifier.size(48.dp).squish({
                            d = d.copy(color = if (on) null else v)
                            hop = BuddyCue(nowMillis(), OneShot.Hop)
                        }, haptic = Haptic.Tick, label = BuddyColors.names[i + 2]),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(sw)
                                .then(if (on) Modifier.border(3.dp, c.surface, CircleShape) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Icon(Icons.check, BuddyColors.eyeOn(sw), 20.dp) }
                    }
                }
            }

            if (isNew && bots.size > 1) {
                Label(t.whose)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    bots.forEach { b ->
                        val on = b.profile == d.owner
                        Column(
                            Modifier.width(72.dp).clip(RoundedCornerShape(20.dp))
                                .background(if (on) c.surface2 else Color.Transparent)
                                .squish({ d = d.copy(owner = b.profile); hop = BuddyCue(nowMillis(), OneShot.Hop) }, haptic = Haptic.Tick, label = b.name)
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Buddy(b.spec, if (on) b.status.toMood() else Mood.Idle, 40.dp, interactive = false)
                            Txt(b.name, Theme.type.micro, if (on) c.text else c.secondary, maxLines = 1)
                        }
                    }
                }
            }

            if (editing != null) {
                Label(t.folders)
                editing.folders.forEach { f ->
                    val main = editing.primaryPath?.let { samePath(it, f) } == true
                    Row(
                        Modifier.fillMaxWidth().height(56.dp)
                            .squish({ if (!main) m.setPrimary(editing, f) }, scale = .97f, haptic = Haptic.Tick, label = t.makeMain(folderName(f))),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (main) Icons.folderFilled else Icons.folder, if (main) c.accent else c.secondary, 22.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(f, Theme.type.code, maxLines = 1)
                            if (main) Txt(t.mainFolder, Theme.type.micro, c.accent, maxLines = 1)
                        }
                        IconButton(Icons.close, t.removeFolder, { m.removeFolder(editing, f) }, tint = c.secondary)
                    }
                }
                if (editing.folders.isEmpty()) Txt(t.noFolder, Theme.type.sub, c.tertiary, Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(newFolder, { newFolder = it }, t.addFolder, Modifier.weight(1f), mono = true)
                    Spacer(Modifier.width(4.dp))
                    IconButton(Icons.add, t.addFolder, {
                        m.addFolder(editing, newFolder) { e -> if (e == null) { newFolder = "" } else { err = e } }
                    }, enabled = newFolder.isNotBlank())
                }
            }

            err?.let { Txt(it, Theme.type.sub, c.errorText, Modifier.padding(top = 12.dp)) }
            Spacer(Modifier.height(16.dp))
        }
        PillButton(
            if (busy && isNew) t.creating else if (isNew) s.create else s.save, ::submit,
            Modifier.fillMaxWidth().padding(horizontal = 20.dp), enabled = !busy && d.name.isNotBlank() && (!isNew || d.owner.isNotEmpty()),
            haptic = Haptic.Confirm,
        )
    }
}
