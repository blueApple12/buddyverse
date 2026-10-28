package app.buddyverse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import app.buddyverse.core.AppRelease
import app.buddyverse.core.AppUpdate
import app.buddyverse.core.LinkState
import app.buddyverse.core.api.downloadFile
import app.buddyverse.core.api.readText
import app.buddyverse.core.nowMillis
import app.buddyverse.core.sha256Stream
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.filesPage
import app.buddyverse.i18n.update
import app.buddyverse.ui.more.files.rememberFileSaver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What this device can do to install an update of itself; null where it can't (the PC updates through its own setup). */
interface AppInstaller {
    /** The installed `versionCode`. */
    val versionCode: Long
    /** Android "install unknown apps" granted to this app. */
    fun canInstall(): Boolean
    /** Opens that switch's settings page for this app. */
    fun openInstallSettings()
}

@Composable
expect fun rememberAppInstaller(): AppInstaller?

/** Update state for the process: one check per 10 min, the download outlives the banner leaving the screen. */
private object Updates {
    var ready by mutableStateOf<AppRelease?>(null)
    var progress by mutableStateOf<Float?>(null)
    var hidden by mutableStateOf<Int?>(null) // versionCode tucked away with ×
    var lastCheck = 0L
    var resumeInstall = false
}

/**
 * "Update ready · vX" over Bots home (phone only): checks [AppUpdate.VERSION_JSON] on open and on each return to the
 * foreground; a tap downloads the APK with progress, checks its sha256 and opens the system installer. Without the
 * "install unknown apps" grant, a short guide opens its settings first and the install resumes on return.
 */
@Composable
fun UpdateBanner(modifier: Modifier, visible: Boolean) {
    val installer = rememberAppInstaller() ?: return
    val store = LocalStore.current
    val toasts = LocalToasts.current
    val fb = LocalFeedback.current
    val saver = rememberFileSaver()
    val u = S.update
    val ft = S.filesPage
    val c = Theme.colors
    var guide by remember { mutableStateOf(false) }

    fun install(r: AppRelease) {
        if (Updates.progress != null) return
        if (!installer.canInstall()) { guide = true; return }
        val out = saver.create("Buddyverse-${r.versionName}.apk", AppUpdate.APK_MIME)
        if (out == null) { fb.haptic(Haptic.Reject); toasts.show(ToastData(ft.cantSave)); return }
        Updates.progress = 0f
        store.scope.launch {
            try {
                val hash = sha256Stream()
                withContext(Dispatchers.Default) {
                    store.downloadFile(AppUpdate.APK_PATH, null, onChunk = { b, n -> out.write(b, n); hash.update(b, n) }, onProgress = { Updates.progress = it })
                }
                if (AppUpdate.hex(hash.digest()) != r.sha256) {
                    // A partial or swapped file (a newer publish mid-download): drop it and look again next time.
                    out.abort(); Updates.lastCheck = 0
                    fb.haptic(Haptic.Reject); toasts.show(ToastData(u.badFile)); return@launch
                }
                check(out.finish())
                if (!out.open()) toasts.show(ToastData(ft.noApp))
            } catch (x: CancellationException) {
                out.abort(); throw x
            } catch (_: Exception) {
                out.abort()
                fb.haptic(Haptic.Reject); toasts.show(ToastData(u.failed))
            } finally {
                Updates.progress = null
            }
        }
    }

    val foreground by store.foreground.collectAsState()
    LaunchedEffect(foreground) {
        if (!foreground) return@LaunchedEffect
        Updates.ready?.let { r -> if (Updates.resumeInstall && installer.canInstall()) { Updates.resumeInstall = false; install(r) } }
        if (nowMillis() - Updates.lastCheck < AppUpdate.CHECK_EVERY_MS) return@LaunchedEffect
        withTimeoutOrNull(20_000) { store.link.first { it is LinkState.Ready } } ?: return@LaunchedEffect
        val text = try { store.api.readText(AppUpdate.VERSION_JSON, null).text } catch (x: CancellationException) { throw x } catch (_: Exception) { return@LaunchedEffect }
        Updates.lastCheck = nowMillis()
        Updates.ready = AppUpdate.parse(text)?.takeIf { AppUpdate.isNewer(it, installer.versionCode) }
    }

    val r = Updates.ready
    val progress = Updates.progress
    val show = visible && r != null && (Updates.hidden != r.versionCode || progress != null)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(show, modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            enter = slideInVertically(Motion.snappy()) { -it } + fadeIn(Motion.fade()), exit = slideOutVertically(Motion.snappy()) { -it } + fadeOut(Motion.fade())) {
            val shown = r ?: return@AnimatedVisibility
            Row(
                Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(c.surface)
                    .clickable { install(shown) }.padding(start = 16.dp, end = 4.dp).heightIn(min = 52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.refresh, c.accent, 22.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Txt(u.ready(shown.versionName), Theme.type.sub, maxLines = 1)
                    Txt(progress?.let { u.downloading((it * 100).toInt()) } ?: u.tapToInstall, Theme.type.micro, c.secondary, maxLines = 1)
                }
                if (progress == null) IconButton(Icons.close, u.later, { Updates.hidden = shown.versionCode }, tint = c.secondary, iconSize = 20.dp)
            }
        }
        Sheet(guide, { guide = false }, u.allowTitle) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Txt(u.allowBody, Theme.type.callout, c.secondary)
                Spacer(Modifier.height(12.dp))
                u.allowSteps.forEachIndexed { i, step -> Txt("${i + 1}. $step", Theme.type.body, modifier = Modifier.padding(vertical = 4.dp)) }
                Spacer(Modifier.height(16.dp))
                PillButton(u.openSettings, {
                    guide = false; Updates.resumeInstall = true; installer.openInstallSettings()
                }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
            }
        }
    }
}
