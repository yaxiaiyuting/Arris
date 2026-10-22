package com.takahashirinta.ncrust.celebration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay

@Composable
internal fun BelatedBirthdayOverlay(days: Int, onClose: () -> Unit, onVisible: () -> Unit) {
    var start by remember(days) { mutableStateOf(false) }
    val visible by rememberUpdatedState(onVisible)
    val close by rememberUpdatedState(onClose)
    if (start) {
        CelebrationOverlay(CelebrationKind.BIRTHDAY, onClose, onVisible)
        return
    }
    LaunchedEffect(days) { delay(4_200); start = true }
    val s = LocalStrings.current.celebration
    // An interrupted intro remains eligible; an explicit dismissal respects the user's choice.
    val dismiss = { visible(); close() }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xff08160f)).safeDrawingPadding().padding(28.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                MetroText(s.lateTitle, color = Color(0xffffe8c7), style = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center))
                Spacer(Modifier.height(24.dp))
                MetroText(s.lateMessage(days), color = Color(0xffd4dfd5), style = TextStyle(fontSize = 18.sp, textAlign = TextAlign.Center))
                Spacer(Modifier.height(32.dp))
                CelebrationButton(s.lateContinue, { start = true })
                CelebrationButton(s.close, dismiss, primary = false)
            }
        }
    }
}
