package app.buddyverse.ui.more

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.MotionLevel
import androidx.compose.runtime.produceState
import app.buddyverse.core.ModelChoice
import app.buddyverse.core.TempChats
import app.buddyverse.core.api.modelOptions
import kotlinx.coroutines.CancellationException
import app.buddyverse.core.ThemePref
import app.buddyverse.core.api.ConfigField
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.PillButton
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Sound
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.Lang
import app.buddyverse.i18n.S
import app.buddyverse.i18n.settingsPage
import app.buddyverse.ui.LocalNav
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.Screen
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.MorePage
import app.buddyverse.ui.PageFrame
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.chat.Spinner
import app.buddyverse.ui.ltr
import app.buddyverse.ui.modelLabel
import app.buddyverse.ui.more.settings.BillingCard
import app.buddyverse.ui.more.settings.BillingModel
import app.buddyverse.ui.more.settings.BotChips
import app.buddyverse.ui.more.settings.CategorySheet
import app.buddyverse.ui.more.settings.ChoiceRow
import app.buddyverse.ui.more.settings.ConfigRow
import app.buddyverse.ui.more.settings.EngineSettings
import app.buddyverse.ui.more.settings.ListenSettings
import app.buddyverse.ui.more.settings.OptionsSheet
import app.buddyverse.ui.more.settings.PasswordSheet
import app.buddyverse.ui.more.settings.PlansSheet
import app.buddyverse.ui.more.settings.RawSheet
import app.buddyverse.ui.more.settings.SaveState
import app.buddyverse.ui.more.settings.TempModelSheet
import app.buddyverse.ui.more.settings.ToggleRow
import app.buddyverse.ui.more.settings.categoryIcon
import app.buddyverse.ui.more.status.ConfirmSheet
import app.buddyverse.ui.more.status.NavRow
import kotlinx.coroutines.launch

private enum class SettingsOpen { None, SignOut, Raw, Plans, Password, TempModel }

/**
 * More ▸ Settings (design §3.7; hermes-api.md §2.3): this app (theme, language, sounds, haptics, motion), the paired PC and
 * sign-out, every engine setting of each bot from the PC's own settings schema (by category or by search, plus the raw
 * settings file and usage-count sharing), and plan & credits.
 */
@Composable
fun SettingsPage() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val toasts = LocalToasts.current
    val fb = LocalFeedback.current
    val uri = LocalUriHandler.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val s = S.current
    val t = S.settingsPage
    val c = Theme.colors
    val scope = rememberCoroutineScope()
    val prefs by store.prefs.collectAsState()
    val bots by store.bots.collectAsState()
    val engine = remember(store) { EngineSettings(store) }
    val billing = remember(store) { BillingModel(store) }
    var open by remember { mutableStateOf(SettingsOpen.None) }
    var category by remember { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf<ConfigField?>(null) }
    var query by remember { mutableStateOf("") }
    var savedOnce by remember { mutableStateOf(false) }

    fun toast(text: String) = toasts.show(ToastData(text))
    // Failures always say why; the first good save says it applies to new chats.
    engine.onSaved = { _, st ->
        when (st) {
            is SaveState.Failed -> { fb.haptic(Haptic.Reject); toast(t.notSaved(st.why)) }
            SaveState.Saved -> if (!savedOnce) { savedOnce = true; toast(t.saved) }
            SaveState.Saving -> Unit
        }
    }
    billing.toast = ::toast
    LaunchedEffect(engine.profile) { engine.load() }
    LaunchedEffect(billing) { billing.load() }
    val brain = bots.firstOrNull { it.isDefault }?.profile ?: "default"

    fun openLink(url: String) { runCatching { uri.openUri(url) }.onFailure { toast(s.error) } }

    Box(Modifier.fillMaxSize()) {
        PageFrame(MorePage.Settings.label) {
            // ---- the AI model: add or remove keys, sign in to a provider, pick the default model (More ▸ Models)
            item(key = "model-h") { SectionLabel(t.aiModel) }
            item(key = "connect-model") {
                NavRow(Icons.autoAwesome, t.connectModel, t.connectModelSub, { nav.connectModel = true; nav.push(Screen.More(MorePage.Models)) })
            }

            // ---- this app
            item(key = "app-h") { SectionLabel(t.thisApp) }
            item(key = "theme") {
                ChoiceRow(Icons.darkMode, s.theme, listOf(ThemePref.System to s.system, ThemePref.Light to s.light, ThemePref.Dark to s.dark), prefs.theme) {
                    store.setPrefs(prefs.copy(theme = it))
                }
            }
            item(key = "lang") {
                ChoiceRow(Icons.language, s.language, listOf(Lang.System to s.system, Lang.English to s.english, Lang.Hebrew to s.hebrew), prefs.lang) {
                    store.setPrefs(prefs.copy(lang = it))
                }
            }
            item(key = "sounds") {
                ToggleRow(Icons.volumeUp, s.sounds, prefs.sounds) { on -> store.setPrefs(prefs.copy(sounds = on)); if (on) fb.sound(Sound.Pop) }
            }
            item(key = "haptics") {
                ToggleRow(Icons.vibration, s.haptics, prefs.haptics) { on -> store.setPrefs(prefs.copy(haptics = on)); if (on) fb.haptic(Haptic.Confirm) }
            }
            item(key = "notifications") {
                // Turning it on asks the phone for permission (MainActivity watches this switch).
                ToggleRow(Icons.notifications, s.notifications, prefs.notifications) { on -> store.setPrefs(prefs.copy(notifications = on)) }
            }
            if (prefs.notifications) item(key = "listen") { ListenSettings() }
            item(key = "motion") {
                ChoiceRow(Icons.autoAwesome, s.motion, listOf(MotionLevel.Full to s.motionFull, MotionLevel.Calm to s.motionCalm, MotionLevel.Off to s.motionOff), prefs.motion) {
                    store.setPrefs(prefs.copy(motion = it))
                }
            }

            item(key = "temp-model") {
                // New temp (quick) chats start on this model (default Automatic); the chat's model chip still switches it.
                val pick = prefs.tempModel?.takeIf { it != TempChats.AUTO }
                // Automatic: what it resolves to right now (the Claude subscription's Sonnet, else the engine's default model).
                val autoNow by produceState<ModelChoice?>(null, pick, brain) {
                    if (pick == null) value = try { TempChats.resolve(null, store.api.modelOptions(brain, null)) }
                        catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                }
                val detail = when {
                    pick != null -> ltr(modelLabel(pick.model) ?: pick.model)
                    autoNow != null -> t.tempAutoWith(ltr(modelLabel(autoNow!!.model) ?: autoNow!!.model))
                    else -> t.tempAuto
                }
                NavRow(Icons.bolt, t.tempModel, detail, { open = SettingsOpen.TempModel })
            }

            // ---- the paired PC
            item(key = "pc-h") { SectionLabel(t.yourPc) }
            item(key = "link") {
                NavRow(Icons.link, s.pairedPc, ltr(store.savedLink), {
                    clipboard.setText(AnnotatedString(store.savedLink)); toast(t.linkCopied)
                })
            }
            item(key = "password") { NavRow(Icons.lock, t.changePassword, null, { open = SettingsOpen.Password }) }
            item(key = "signout") { NavRow(Icons.logout, s.signOut, null, { open = SettingsOpen.SignOut }, tint = c.errorText) }

            // ---- engine settings, per bot, from the PC's schema
            item(key = "engine-h") { SectionLabel(t.engine) }
            if (bots.size > 1) item(key = "bots") { BotChips(bots, engine.profile) { engine.pick(it) } }
            item(key = "search") { Field(query, { query = it }, t.searchSettings, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
            val schema = engine.schema
            when {
                schema == null && engine.error != null -> item(key = "engine-error") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Txt(engine.error.orEmpty(), Theme.type.sub, c.errorText, Modifier.padding(horizontal = 4.dp), maxLines = 3)
                        PillButton(s.retry, { scope.launch { engine.load() } }, Modifier.padding(top = 8.dp), kind = ButtonKind.Quiet, height = 48.dp, icon = Icons.refresh)
                    }
                }
                schema == null -> item(key = "engine-loading") {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { Spinner(24.dp, c.tertiary) }
                }
                query.isNotBlank() -> {
                    val hits = engine.search(query)
                    if (hits.isEmpty()) item(key = "no-hit") { Txt(t.noMatch, Theme.type.sub, c.secondary, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                    items(hits, key = { "f:" + it.key }) { f -> ConfigRow(f, engine) { options = it } }
                }
                else -> items(schema.categories, key = { "cat:$it" }) { cat ->
                    NavRow(categoryIcon(cat), t.category(cat), t.fieldsCount(schema.fields.count { it.category == cat }), { category = cat })
                }
            }
            item(key = "raw") { NavRow(Icons.description, t.rawFile, null, { open = SettingsOpen.Raw }) }
            engine.metrics?.let { mc -> item(key = "metrics") { ToggleRow(Icons.insights, t.shareCounts, mc.enabled && mc.send) { engine.setMetrics(it) } } }

            // ---- plan & credits
            item(key = "bill-h") { SectionLabel(t.planCredits) }
            item(key = "bill") { BillingCard(billing, onPlans = { open = SettingsOpen.Plans }, onOpen = ::openLink) }
        }

        CategorySheet(category, engine, { options = it }) { category = null }
        OptionsSheet(options, engine) { options = null }
        RawSheet(open == SettingsOpen.Raw, engine.profile, { open = SettingsOpen.None }) { scope.launch { engine.load() } }
        PlansSheet(open == SettingsOpen.Plans, billing, ::openLink) { open = SettingsOpen.None }
        PasswordSheet(open == SettingsOpen.Password, ::toast) { open = SettingsOpen.None }
        TempModelSheet(open == SettingsOpen.TempModel, brain) { open = SettingsOpen.None }
        ConfirmSheet(open == SettingsOpen.SignOut, s.signOut, listOf(t.signOutAsk), s.signOut, danger = true,
            onConfirm = { store.signOut() }, onDismiss = { if (open == SettingsOpen.SignOut) open = SettingsOpen.None })
    }
}
