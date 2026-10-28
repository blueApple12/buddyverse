package app.buddyverse.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S

/** 64dp screen header: 48dp back (mirrored in RTL) when [onBack] is set, a title, then trailing 48dp actions. */
@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(Icons.arrowBack, S.current.back, onBack, mirror = true) else Spacer(Modifier.width(12.dp))
        Txt(title, if (onBack != null) Theme.type.title2 else Theme.type.title, modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1)
        actions()
    }
}

/** A pushed page: header with back (pops the nav stack) over a lazy list that clears the nav bar. */
@Composable
fun PageFrame(title: String, actions: @Composable RowScope.() -> Unit = {}, content: LazyListScope.() -> Unit) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title, nav::pop, actions = actions)
        LazyColumn(Modifier.fillMaxWidth().weight(1f).navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp), content = content)
    }
}
