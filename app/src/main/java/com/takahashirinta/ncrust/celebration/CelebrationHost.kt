package com.takahashirinta.ncrust.celebration

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.util.Calendar

/** Only mounted on the unobstructed home route, after splash and first-run prompts. */
@Composable
fun CelebrationHost(allowed:Boolean, playerProgress:()->Float = {0f}) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val options=rememberCelebrationOptions(context)
    var resumeTick by remember {mutableIntStateOf(0)}
    var occasion by remember {mutableStateOf<BirthdayOccasion?>(null)}
    val collapsed by remember(playerProgress) {derivedStateOf {playerProgress()<.01f}}
    val ready=allowed && collapsed
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event -> when(event) {
            Lifecycle.Event.ON_RESUME -> resumeTick++
            Lifecycle.Event.ON_STOP -> occasion=null
            else -> Unit
        }}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer)}
    }
    // Track a birthday-day open even if another route or overlay delays the celebration.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { CelebrationPrefs.noteAppOpened(context); delay(60_000) }
        }
    }
    LaunchedEffect(ready,resumeTick,options) {
        if(!ready) {occasion=null;return@LaunchedEffect}
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        // Re-check the local date while the app stays open across midnight too.
        while(true) {
            delay(500)
            val now=Calendar.getInstance()
            val y=now.get(Calendar.YEAR)
            if(occasion==null) occasion=BirthdayWindow.find(options.month,options.day,options.enabled,y,now.get(Calendar.MONTH)+1,now.get(Calendar.DAY_OF_MONTH),options.shown,options.openedOnBirthday)
            delay(59_500)
        }
        }
    }
    if(ready && occasion!=null) {
        val displayed=occasion!!
        val close = { occasion = null }
        val visible = { CelebrationPrefs.markShown(context,displayed.birthdayYear) }
        if (displayed.daysLate > 0) BelatedBirthdayOverlay(displayed.daysLate, close, visible)
        else CelebrationOverlay(CelebrationKind.BIRTHDAY, onClose=close, onVisible=visible)
    }
}
