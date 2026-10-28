package app.buddyverse

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.buddyBitmap
import app.buddyverse.core.AppStore
import app.buddyverse.core.ChatController
import app.buddyverse.core.ChatTarget
import app.buddyverse.core.UiSignal
import app.buddyverse.core.chat.PromptKind
import app.buddyverse.core.systemLanguage
import app.buddyverse.i18n.EnglishStrings
import app.buddyverse.i18n.HebrewStrings
import app.buddyverse.i18n.Lang
import app.buddyverse.i18n.Strings
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phone notifications while the app is in the background (design §3.11): needs you (with Deny / Allow for approvals),
 * done (turns of 30 s or more) and failed. One channel per bot, so each bot can be muted on its own; the large icon is the
 * bot's buddy with the matching face; a tap opens that chat. Cleared when the app comes back.
 */
class Notifier(private val ctx: Context, private val store: AppStore) {
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    init {
        store.scope.launch { store.signals.collect(::on) }
        // Seen: clear them all, except the listening service's own ongoing one.
        store.scope.launch { store.foreground.filter { it }.collect { nm.activeNotifications.filter { it.id != ListenService.ID }.forEach { n -> nm.cancel(n.tag, n.id) } } }
    }

    private val strings: Strings get() = if (hebrew(store)) HebrewStrings else EnglishStrings

    private fun allowed(): Boolean = store.prefs.value.notifications && !store.foreground.value &&
        (Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** Needs-you notifications per session (or bot): a turn that ended has nothing left to answer, so they go. */
    private val asks = mutableMapOf<String, MutableSet<Int>>()

    private fun on(s: UiSignal) {
        when (s) {
            is UiSignal.TurnDone -> asks.remove(s.sessionId ?: s.profile)?.forEach(nm::cancel)
            is UiSignal.Failed -> asks.remove(s.sessionId ?: s.profile)?.forEach(nm::cancel)
            else -> Unit
        }
        if (!allowed()) return
        val t = strings
        when (s) {
            is UiSignal.NeedsYou -> needsYou(s.profile, s.sessionId, t)
            is UiSignal.TurnDone -> if (s.tookMs >= 30_000) post(s.profile, s.sessionId, idOf(s.profile, "done"), t.notifyDone(name(s.profile)), s.text?.take(200),
                Notification.CATEGORY_STATUS, Mood.Idle)
            is UiSignal.Failed -> post(s.profile, s.sessionId, idOf(s.profile, "failed"), t.notifyFailed(name(s.profile)), null, Notification.CATEGORY_ERROR, Mood.Error)
            else -> Unit
        }
    }

    private fun needsYou(profile: String, sessionId: String?, t: Strings) {
        // Nothing open any more (answered before this ran, here or on the PC): nothing to show.
        val ctl = chatOf(store, profile, sessionId) ?: return
        val p = ctl.state.value.prompts.firstOrNull() ?: return
        val line = p.description ?: p.question ?: p.command ?: t.notifyOpen
        val actions = if (p.kind == PromptKind.Approval) {
            val id = idOf(profile, p.id)
            listOf(t.deny to "deny", t.allow to "once").map { (label, value) ->
                val i = withChat(Intent(ctx, NotifyReceiver::class.java), profile, ctl.sessionId ?: sessionId).putExtra(K_CHAT, ctl.target.key)
                    .putExtra(K_PROMPT, p.id).putExtra(K_VALUE, value).putExtra(K_NOTIF, id)
                val pi = PendingIntent.getBroadcast(ctx, (id * 31 + value.hashCode()), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat_buddy), label, pi).build()
            }
        } else emptyList()
        val sid = ctl.sessionId ?: sessionId
        val id = idOf(profile, p.id)
        asks.getOrPut(sid ?: profile) { mutableSetOf() } += id
        post(profile, sid, id, t.notifyNeedsYou(name(profile)), line.take(300), Notification.CATEGORY_REMINDER, Mood.NeedsYou, actions)
    }

    private fun name(profile: String) = store.bots.value.firstOrNull { it.profile == profile }?.name ?: profile

    private fun post(profile: String, sessionId: String?, id: Int, title: String, text: String?, category: String, mood: Mood,
                     actions: List<Notification.Action> = emptyList()) {
        val b = Notification.Builder(ctx, channel(profile))
            .setSmallIcon(R.drawable.ic_stat_buddy)
            .setLargeIcon(avatar(profile, mood))
            .setContentTitle(title)
            .setCategory(category)
            .setAutoCancel(true)
            .setContentIntent(open(profile, sessionId, id))
        if (!text.isNullOrBlank()) b.setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
        actions.forEach(b::addAction)
        runCatching { nm.notify(id, b.build()) }
    }

    /** Opens the chat the notification is about (MainActivity reads the extras); one request code per notification. */
    private fun open(profile: String, sessionId: String?, id: Int): PendingIntent =
        PendingIntent.getActivity(ctx, id, withChat(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), profile, sessionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Which chat, in words that outlive this process: read back by [targetOf]. */
    private fun withChat(i: Intent, profile: String, sessionId: String?): Intent {
        val ctl = sessionId?.let { sid -> store.openChats().firstOrNull { it.sessionId == sid } }
        val botChat = ctl?.target?.botChat ?: (sessionId == null || store.bots.value.firstOrNull { it.profile == profile }?.botChatId == sessionId)
        return i.putExtra(K_OPEN_PROFILE, profile).putExtra(K_OPEN_SESSION, sessionId).putExtra(K_OPEN_BOTCHAT, botChat)
    }

    private fun channel(profile: String): String {
        val id = "bot-$profile"
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(NotificationChannel(id, name(profile), NotificationManager.IMPORTANCE_HIGH))
        }
        return id
    }

    /** The bot's buddy, drawn by the app's own buddy code with the face of the moment (design §3.11). */
    private fun avatar(profile: String, mood: Mood): Icon {
        val spec = store.bots.value.firstOrNull { it.profile == profile }?.spec
            ?: BuddySpec(profile, profile, BuddyShape.parse(null, profile), BuddyColors.forName(profile))
        return Icon.createWithBitmap(buddyBitmap(spec, mood, 192).asAndroidBitmap())
    }

    companion object {
        const val K_CHAT = "chat"; const val K_PROMPT = "prompt"; const val K_VALUE = "value"; const val K_NOTIF = "notif"
        const val K_OPEN_PROFILE = "open.profile"; const val K_OPEN_SESSION = "open.session"; const val K_OPEN_BOTCHAT = "open.botchat"
        fun idOf(profile: String, key: String) = (profile + "|" + key).hashCode() and 0x7fffffff
        fun hebrew(store: AppStore) = when (store.prefs.value.lang) { Lang.System -> systemLanguage() in setOf("he", "iw"); Lang.Hebrew -> true; Lang.English -> false }
        fun chatOf(store: AppStore, profile: String, sessionId: String?): ChatController? = store.openChats().let { all ->
            all.firstOrNull { sessionId != null && it.sessionId == sessionId && it.state.value.prompts.isNotEmpty() }
                ?: all.firstOrNull { it.target.profile == profile && it.state.value.prompts.isNotEmpty() }
        }

        /** The chat a tapped notification asks for: the open one for that session if any, else the bot's Bot Chat or that session. */
        fun targetOf(store: AppStore, i: Intent): ChatTarget? {
            val profile = i.getStringExtra(K_OPEN_PROFILE) ?: return null
            val sid = i.getStringExtra(K_OPEN_SESSION)
            return store.openChats().firstOrNull { sid != null && it.sessionId == sid }?.target
                ?: if (sid == null || i.getBooleanExtra(K_OPEN_BOTCHAT, false)) ChatTarget(profile) else ChatTarget(profile, sid)
        }
    }
}

/**
 * Deny / Allow from the notification: answers through the same chat controller the app uses. If the phone killed the
 * process since, the chat is re-attached first and the same prompt (same id) is answered once it is back.
 */
class NotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = App.store(context.applicationContext)
        val promptId = intent.getStringExtra(Notifier.K_PROMPT) ?: return
        val value = intent.getStringExtra(Notifier.K_VALUE) ?: return
        val nm = context.getSystemService(NotificationManager::class.java)
        val notif = intent.getIntExtra(Notifier.K_NOTIF, 0)
        nm.cancel(notif)
        val ctl = store.openChats().firstOrNull { it.target.key == intent.getStringExtra(Notifier.K_CHAT) }
            ?: Notifier.targetOf(store, intent)?.let(store::chat) ?: return
        val done = goAsync()
        store.scope.launch {
            val p = withTimeoutOrNull(9_000) { ctl.state.first { s -> s.prompts.any { it.id == promptId } }.prompts.first { it.id == promptId } }
            if (p != null) { ctl.answer(p, value); nm.cancel(notif) } // re-attaching may have posted it again
            done.finish()
        }
    }
}
