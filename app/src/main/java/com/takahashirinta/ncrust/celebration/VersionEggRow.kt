package com.takahashirinta.ncrust.celebration

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText

@Composable
fun VersionEggRow(label:String,version:String) {
    val s=LocalStrings.current.celebration
    val gate=remember {VersionTapGate()}
    var count by remember {mutableIntStateOf(0)}
    var open by remember {mutableStateOf(false)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    fun reset() {gate.reset();count=0}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event -> if(event==Lifecycle.Event.ON_STOP)reset()}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);gate.reset()}
    }
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp)
        .semantics {customActions=listOf(CustomAccessibilityAction(s.openEgg) {open=true;true})}
        .clickable(enabled=!open) {if(gate.tap(SystemClock.elapsedRealtime()))open=true;count=gate.count},
        horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        MetroText(label,color=LocalMetroColors.current.onSurfaceVariant,style=TextStyle(fontSize=13.sp),modifier=Modifier.weight(1f))
        Column(Modifier.weight(1.5f),horizontalAlignment=Alignment.End) {
            MetroText(version,color=LocalMetroColors.current.onBackground,style=TextStyle(fontSize=13.sp,textAlign=TextAlign.End))
            if(count in 4..6)MetroText(s.remaining(7-count),color=LocalMetroColors.current.primary,style=TextStyle(fontSize=12.sp,textAlign=TextAlign.End))
        }
    }
    if(open)CelebrationOverlay(CelebrationKind.LISTENER,onClose={open=false;reset()})
}
