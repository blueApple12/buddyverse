package app.buddyverse.ui.more

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.LocalMotion
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.MotionLevel
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.nowMillis
import app.buddyverse.design.Card
import app.buddyverse.design.Chip
import app.buddyverse.design.Haptic
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.gallery
import app.buddyverse.ui.MorePage
import app.buddyverse.ui.PageFrame
import app.buddyverse.ui.more.gallery.AliveCheck
import app.buddyverse.ui.more.gallery.AliveReadout
import app.buddyverse.ui.more.gallery.ColorRow
import app.buddyverse.ui.more.gallery.GallerySize
import app.buddyverse.ui.more.gallery.GroupRow
import app.buddyverse.ui.more.gallery.MoodRow
import app.buddyverse.ui.more.gallery.ReactionChips
import app.buddyverse.ui.more.gallery.galleryCells
import app.buddyverse.ui.more.gallery.galleryMoods
import app.buddyverse.ui.more.gallery.runAliveCheck
import app.buddyverse.ui.more.gallery.touchHaptic
import kotlinx.coroutines.delay

/**
 * More ▸ Buddy gallery (debug; design §4.9.10 and the §13 acceptance check): every shape × mood running at once, every
 * one-shot on tap, every palette color, group stacks, and a live check that no buddy stands still for more than 4 s.
 * No network. The page's own Motion switch overrides the app setting here only.
 */
@Composable
fun GalleryPage() {
    val appMotion = LocalMotion.current
    var motion by remember { mutableStateOf(appMotion) }
    CompositionLocalProvider(LocalMotion provides motion) { GalleryBody(motion) { motion = it } }
}

@Composable
private fun GalleryBody(motion: MotionLevel, onMotion: (MotionLevel) -> Unit) {
    val s = S.current
    val g = S.gallery
    val c = Theme.colors
    val fb = LocalFeedback.current
    val cells = remember { galleryCells() }
    val byMood = remember(cells) { galleryMoods.map { m -> m to cells.filter { it.mood == m } } }

    var size by remember { mutableStateOf(GallerySize.S) }
    var stage by remember { mutableStateOf(cells.first()) }
    var stageColor by remember { mutableIntStateOf(cells.first().color) }
    var stageCue by remember { mutableStateOf<BuddyCue?>(null) }
    var wave by remember { mutableStateOf<BuddyCue?>(null) }
    var everyone by remember { mutableStateOf(false) }
    var serial by remember { mutableLongStateOf(0L) }
    var talkUntil by remember { mutableLongStateOf(0L) }
    var talkAt by remember { mutableLongStateOf(0L) }

    val check = remember { AliveCheck() }
    var checkRun by remember { mutableIntStateOf(0) }
    LaunchedEffect(motion, size, checkRun) { check.longest = 0f; runAliveCheck(cells, motion, size.dp.value, check) }
    LaunchedEffect(stage.mood, talkUntil) {
        while (stage.mood == Mood.Talking || nowMillis() < talkUntil) { talkAt = nowMillis(); delay(90) }
    }

    fun fire(shot: OneShot) {
        val cue = BuddyCue(++serial, shot)
        stageCue = cue
        if (everyone) wave = cue
    }

    val stageName = "${g.shape(stage.shape)} · ${g.mood(stage.mood)}"
    val stageSpec = BuddySpec("gallery:stage", stageName, stage.shape, BuddyColors.palette[stageColor])

    PageFrame(MorePage.Gallery.label, actions = {
        IconButton(Icons.refresh, g.resetCheck, { checkRun++; fb.haptic(Haptic.Confirm) })
    }) {
        item(key = "stage") {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(176.dp).touchHaptic(fb), contentAlignment = Alignment.Center) {
                    Buddy(stageSpec, stage.mood, 120.dp, cue = stageCue, talkAt = talkAt, talkChars = 10)
                }
                Txt(stageName, Theme.type.headline.copy(textAlign = TextAlign.Center), c.text, Modifier.fillMaxWidth(), maxLines = 1)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
                    for (m in galleryMoods) Chip(g.mood(m), m == stage.mood, { stage = stage.copy(mood = m) }, Modifier.padding(end = 6.dp))
                }
                AliveReadout(check, Modifier.fillMaxWidth().padding(top = 10.dp))
            }
        }
        item(key = "controls") { Column {
            SectionLabel(g.size)
            Row(Modifier.padding(horizontal = 12.dp)) {
                Chip(g.sizeSmall, size == GallerySize.S, { size = GallerySize.S }, Modifier.padding(end = 6.dp))
                Chip(g.sizeMedium, size == GallerySize.M, { size = GallerySize.M }, Modifier.padding(end = 6.dp))
                Chip(g.sizeLarge, size == GallerySize.L, { size = GallerySize.L })
            }
            SectionLabel(s.motion)
            Row(Modifier.padding(horizontal = 12.dp)) {
                Chip(s.motionFull, motion == MotionLevel.Full, { onMotion(MotionLevel.Full) }, Modifier.padding(end = 6.dp))
                Chip(s.motionCalm, motion == MotionLevel.Calm, { onMotion(MotionLevel.Calm) }, Modifier.padding(end = 6.dp))
                Chip(s.motionOff, motion == MotionLevel.Off, { onMotion(MotionLevel.Off) })
            }
        } }
        item(key = "reactions") { Column {
            SectionLabel(g.reactions)
            ReactionChips(everyone, { everyone = it }, onTalk = { talkUntil = nowMillis() + 2_500 }, onShot = ::fire)
        } }
        item(key = "moods-label") { SectionLabel(g.moods) }
        var first = 0
        for ((m, row) in byMood) {
            val start = first
            item(key = "mood:${m.name}") {
                MoodRow(m, row, start, size.dp, wave) { cell -> stage = cell; stageColor = cell.color }
            }
            first += row.size
        }
        item(key = "colors") { Column {
            SectionLabel(g.colors)
            ColorRow(stage.shape, stageColor) { stageColor = it }
        } }
        item(key = "groups") { Column {
            SectionLabel(g.groups)
            GroupRow(cells)
        } }
    }
}
