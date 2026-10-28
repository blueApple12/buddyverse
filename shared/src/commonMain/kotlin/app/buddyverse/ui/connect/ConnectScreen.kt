package app.buddyverse.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.AuthError
import app.buddyverse.core.normalizeBaseUrl
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Field
import app.buddyverse.design.Hairline
import app.buddyverse.design.Haptic
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.PillButton
import app.buddyverse.design.Sound
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.APP_NAME
import app.buddyverse.i18n.S
import app.buddyverse.ui.LocalStore
import kotlinx.coroutines.launch

private val team = listOf(
    BuddySpec("t1", "", BuddyShape.Squircle, BuddyColors.palette[8]),
    BuddySpec("t2", "", BuddyShape.Blob, BuddyColors.palette[6]),
    BuddySpec("t3", "", BuddyShape.Cloud, BuddyColors.palette[3]),
    BuddySpec("t4", "", BuddyShape.Teardrop, BuddyColors.palette[9]),
)

/** First run and "change link": PC link (paste or QR) + sign-in (design §3.10). */
@Composable
fun ConnectScreen() {
    val store = LocalStore.current
    val s = S.current
    val c = Theme.colors
    val fb = LocalFeedback.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf(store.savedLink) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var cue by remember { mutableStateOf(0L) }

    fun connect() {
        if (normalizeBaseUrl(link) == null) { error = s.connectBadLink; fb.haptic(Haptic.Reject); return }
        busy = true; error = null; cue++
        scope.launch {
            val e = store.signIn(link, user, pass)
            busy = false
            if (e == null) { fb.haptic(Haptic.Confirm); fb.sound(Sound.Bloop) }
            else {
                fb.haptic(Haptic.Reject)
                error = when (e) {
                    is AuthError.BadCredentials -> s.connectBadLogin
                    is AuthError.RateLimited -> s.connectRateLimited
                    else -> s.connectUnreachable
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                team.forEachIndexed { i, b ->
                    Buddy(b, if (busy) Mood.Thinking else if (error != null) Mood.Error else Mood.Sleeping, if (i % 2 == 0) 64.dp else 56.dp,
                        cue = if (cue > 0) BuddyCue(cue * 10 + i, OneShot.Startle) else null)
                }
            }
            Hairline(Modifier.widthIn(max = 320.dp).padding(top = 6.dp))
            Spacer(Modifier.height(24.dp))
            Txt(APP_NAME, Theme.type.caps, c.secondary)
            Txt(s.connectTitle, Theme.type.title, modifier = Modifier.padding(top = 4.dp), maxLines = 2)
            Spacer(Modifier.height(24.dp))
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(link, { link = it; error = null }, s.connectLinkHint, Modifier.weight(1f), mono = true, keyboard = KeyboardType.Uri)
                    Spacer(Modifier.width(4.dp))
                    IconButton(Icons.contentCopy, s.connectPaste, { clip.getText()?.text?.let { link = it.trim() } })
                    IconButton(Icons.qrCodeScanner, s.connectScan, { scanning = true })
                }
                Field(user, { user = it; error = null }, s.connectUser)
                Field(pass, { pass = it; error = null }, s.connectPassword, password = true)
                if (error != null) Txt(error!!, Theme.type.sub, c.errorText, Modifier.fillMaxWidth().padding(horizontal = 6.dp))
                PillButton(if (busy) s.connectWorking else s.connectGo, ::connect, Modifier.fillMaxWidth(), height = 64.dp,
                    enabled = !busy && link.isNotBlank() && user.isNotBlank() && pass.isNotEmpty(), haptic = Haptic.Press)
                PillButton(s.connectScan, { scanning = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Outline, icon = Icons.qrCodeScanner)
            }
            Spacer(Modifier.height(32.dp))
            Txt(s.pcOffBody, Theme.type.caption, c.tertiary, Modifier.widthIn(max = 360.dp), maxLines = 3)
            Spacer(Modifier.height(24.dp))
        }
        if (scanning) QrScanner(onResult = { text -> scanning = false; extractLink(text)?.let { link = it; fb.haptic(Haptic.Confirm) } }, onClose = { scanning = false })
    }
}

/** A QR may hold a bare link or any text containing one. */
fun extractLink(text: String): String? =
    Regex("""https?://[^\s"'<>]+""").find(text)?.value?.let(::normalizeBaseUrl) ?: normalizeBaseUrl(text)

/** Full-screen QR viewfinder (Android: CameraX + ZXing). */
@Composable
expect fun QrScanner(onResult: (String) -> Unit, onClose: () -> Unit)
