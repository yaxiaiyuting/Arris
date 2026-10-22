package com.takahashirinta.ncrust.celebration

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun CelebrationOverlay(kind: CelebrationKind, onClose: () -> Unit, onVisible: () -> Unit = {}) {
    if (kind == CelebrationKind.BIRTHDAY) BirthdayWebOverlay(onClose, onVisible)
    else NativeCelebrationOverlay(kind, onClose, onVisible)
}

@Composable
private fun BirthdayWebOverlay(onClose: () -> Unit, onVisible: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val strings = LocalStrings.current.celebration
    val options = rememberCelebrationOptions(context)
    val motion = rememberMotionAllowed()
    var failed by remember { mutableStateOf(false) }
    if (failed || !options.animated || !motion) {
        NativeCelebrationOverlay(CelebrationKind.BIRTHDAY, onClose, onVisible)
        return
    }
    var view by remember { mutableStateOf<BirthdayWebView?>(null) }
    var state by remember { mutableStateOf("LOADING") }
    var ready by remember { mutableStateOf(false) }
    var mic by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(true) }
    val close by rememberUpdatedState(onClose)
    val visible by rememberUpdatedState(onVisible)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (active && state == "WAITING") {
            mic = granted
            if (!granted) feedback = strings.micDenied
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { active = false; mic = false; close() }
        }
        lifecycle.addObserver(observer)
        onDispose { active = false; lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(ready) {
        if (ready) { withFrameNanos { }; if (active) visible() }
    }
    LaunchedEffect(Unit) { delay(10_000); if (!ready && active) failed = true }
    fun extinguish() {
        if (state == "WAITING") { mic = false; state = "PLAYING"; view?.command("blow") }
    }
    LaunchedEffect(mic, state) {
        if (!mic || state != "WAITING") return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        armed = false
        val result = BlowRecorder.listen(context) { armed = true }
        mic = false
        if (active && state == "WAITING") when (result) {
            BlowResult.DETECTED -> extinguish()
            BlowResult.TIMEOUT -> feedback = strings.micTimeout
            BlowResult.UNAVAILABLE -> feedback = strings.micUnavailable
        }
        }
    }
    Dialog(onDismissRequest = { mic = false; close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xff08160f))) {
            AndroidView(
                factory = { ctx -> android.widget.FrameLayout(ctx).apply {
                    try {
                        val web = BirthdayWebView(ctx, JSONObject().put("title", strings.titles.last()).put("wishes", JSONArray(strings.wishes)).put("captions", JSONArray(strings.captions)))
                        view = web
                        addView(web, android.widget.FrameLayout.LayoutParams(-1, -1))
                    } catch (_: Exception) { post { failed = true } }
                } },
                modifier = Modifier.fillMaxSize(),
                onRelease = { host -> (host.getChildAt(0) as? BirthdayWebView)?.release(); view = null },
                update = { host -> (host.getChildAt(0) as? BirthdayWebView)?.onEvent = { event ->
                    when (event) {
                        "READY" -> ready = true
                        "ERROR" -> { mic = false; failed = true }
                        else -> { state = event; if (event != "WAITING") mic = false }
                    }
                } },
            )
            Box(Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp)) {
                CelebrationButton(strings.close, { mic = false; close() }, primary = false)
            }
            BirthdayActions(state, mic, armed, feedback,
                modifier = Modifier.align(Alignment.BottomCenter),
                onMic = {
                    if (!mic && active) {
                        feedback = ""
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) mic = true
                        else permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }, onBlow = ::extinguish,
                onReplay = { mic = false; feedback = ""; state = "PLAYING"; view?.command("replay") },
                onClose = { mic = false; close() },
            )
        }
    }
}

@Composable
private fun BirthdayActions(state: String, mic: Boolean, armed: Boolean, feedback: String,
    modifier: Modifier, onMic: () -> Unit, onBlow: () -> Unit, onReplay: () -> Unit, onClose: () -> Unit) {
    if (state != "WAITING" && state != "DONE") return
    val s = LocalStrings.current.celebration
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    Column(modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (state == "WAITING") {
            MetroText(feedback.ifBlank { s.micHint }, color = Color(0xffe8cfa9), style = TextStyle(fontSize = 12.sp, textAlign = TextAlign.Center))
            Spacer(Modifier.height(8.dp))
        }
        if (landscape || state == "DONE") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { CelebrationButton(if (state == "DONE") s.continueListening else if (mic) if (armed) s.micListening else s.micCalibrating else s.micStart, if (state == "DONE") onClose else onMic, enabled = !mic) }
                Box(Modifier.weight(1f)) { CelebrationButton(if (state == "DONE") s.replay else s.manualBlow, if (state == "DONE") onReplay else onBlow, primary = false) }
            }
        } else {
            CelebrationButton(if (state == "DONE") s.continueListening else if (mic) if (armed) s.micListening else s.micCalibrating else s.micStart, if (state == "DONE") onClose else onMic, enabled = !mic)
            CelebrationButton(if (state == "DONE") s.replay else s.manualBlow, if (state == "DONE") onReplay else onBlow, primary = false)
        }
    }
}
