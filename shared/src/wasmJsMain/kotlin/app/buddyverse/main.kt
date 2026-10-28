package app.buddyverse

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import app.buddyverse.core.AppStore
import app.buddyverse.core.LocalStorageStore
import app.buddyverse.design.NoFeedback
import app.buddyverse.design.WebBack
import app.buddyverse.ui.AgenticApp
import kotlinx.coroutines.MainScope

private fun pageOrigin(): String = js("location.origin")
private fun onEscape(back: () -> Unit): Unit = js("{ document.addEventListener('keydown', e => { if (e.key === 'Escape') back(); }); }")
private fun appReady(): Unit = js("{ const s = document.getElementById('splash'); if (s) s.remove(); }")

/** The PWA entry: the page is served by the user's own gateway, so its origin IS the PC link. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val kv = LocalStorageStore()
    kv.put("base", pageOrigin()) // a new tunnel URL is a new origin (new storage): nothing stale to keep
    val store = AppStore(kv, MainScope())
    onEscape { WebBack.back() }
    ComposeViewport {
        AgenticApp(store, NoFeedback)
    }
    appReady()
}
