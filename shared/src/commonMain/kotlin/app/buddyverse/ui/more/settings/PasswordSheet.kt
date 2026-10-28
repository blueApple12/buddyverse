package app.buddyverse.ui.more.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.buddyverse.core.PasswordChange
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Field
import app.buddyverse.design.PillButton
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.settingsPage
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.chat.Spinner
import kotlinx.coroutines.launch

/**
 * Settings ▸ Change password (the Webapp login). On the PC: current, new, again, a strength hint, a look-alike warning and
 * the restart warning; [PasswordChange.onPc] does the work. On the phone it only says to do it from the PC app.
 * The fields live only in this sheet and are cleared when it closes.
 */
@Composable
fun PasswordSheet(visible: Boolean, toast: (String) -> Unit, onDismiss: () -> Unit) {
    val t = S.settingsPage
    val s = S.current
    val c = Theme.colors
    // The app's scope, not the sheet's: leaving the page mid-restart must not cancel a half-done change.
    val scope = LocalStore.current.scope
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun close() { if (working) return; current = ""; new = ""; again = ""; error = null; onDismiss() }

    Sheet(visible, ::close, title = t.changePassword) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val change = PasswordChange.onPc
            if (change == null) {
                Txt(t.pwOnPc, Theme.type.callout, c.secondary)
                PillButton(s.close, ::close, Modifier.fillMaxWidth().padding(top = 10.dp), kind = ButtonKind.Outline)
                return@Column
            }
            Field(current, { current = it; error = null }, t.pwCurrent, password = true)
            Field(new, { new = it; error = null }, t.pwNew, password = true)
            Field(again, { again = it; error = null }, t.pwRepeat, password = true)
            if (new.isNotEmpty()) {
                val strength = PasswordChange.strength(new)
                Txt(t.pwStrength(strength), Theme.type.caption, if (strength <= 1) c.errorText else c.secondary)
                PasswordChange.lookAlikes(new).takeIf { it.isNotEmpty() }?.let { Txt(t.pwLookAlikes(it), Theme.type.caption, c.secondary) }
            }
            val problem = PasswordChange.problem(current, new, again)
            val why = when {
                new.isEmpty() || again.isEmpty() -> null
                problem == PasswordChange.Problem.Spaces -> t.pwSpaces
                problem == PasswordChange.Problem.Mismatch -> t.pwMismatch
                problem == PasswordChange.Problem.SameAsOld -> t.pwSame
                else -> null
            }
            (error ?: why)?.let { Txt(it, Theme.type.sub, c.errorText) }
            Txt(t.pwApplyNote, Theme.type.caption, c.secondary)
            if (working) Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner(20.dp, c.tertiary); Txt(t.pwWorking, Theme.type.sub, c.secondary)
            } else Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(s.cancel, ::close, Modifier.weight(1f), kind = ButtonKind.Outline)
                PillButton(t.pwApply, {
                    working = true
                    scope.launch {
                        val r = try { change(current, new) } finally { working = false }
                        when (r) {
                            PasswordChange.Result.Done -> { toast(t.pwDone); close() }
                            PasswordChange.Result.WrongCurrent -> error = t.pwWrongCurrent
                            is PasswordChange.Result.Failed -> error = t.pwFailed(r.why)
                        }
                    }
                }, Modifier.weight(1f), kind = ButtonKind.Danger, enabled = current.isNotEmpty() && problem == null)
            }
        }
    }
}
