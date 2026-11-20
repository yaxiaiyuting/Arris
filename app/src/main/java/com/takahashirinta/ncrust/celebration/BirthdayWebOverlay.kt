/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 *
 * 修改说明：
 *   - v3.9.0：WebView 生日庆祝界面交互层彻底重构。
 *     · 统一采用森系夜色与香槟金调色体系 (CelebrationPalette)
 *     · 操作面板升级为双层磨砂玻璃质感与声波呼吸圈 (AcousticBreathingRing)
 *     · 采用标准药丸胶囊交互组件 (CelebrationPillButton)
 *     · 关闭按钮与原生庆祝层保持一致 (CelebrationCloseButton)
 */

package com.takahashirinta.ncrust.celebration

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import com.takahashirinta.ncrust.ui.theme.AppShapes
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun CelebrationOverlay(
    kind: CelebrationKind,
    onClose: () -> Unit,
    onVisible: () -> Unit = {},
    recommendation: com.takahashirinta.ncrust.network.SongItem? = null,
    onPlayRecommendation: (com.takahashirinta.ncrust.network.SongItem) -> Unit = {},
    onRerollRecommendation: () -> Unit = {},
) {
    if (kind == CelebrationKind.BIRTHDAY) BirthdayWebOverlay(onClose, onVisible)
    else NativeCelebrationOverlay(
        kind, onClose, onVisible,
        recommendation, onPlayRecommendation, onRerollRecommendation
    )
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

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (active && state == "WAITING") {
            mic = granted
            if (!granted) feedback = strings.micDenied
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                active = false; mic = false; close()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { active = false; lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(ready) {
        if (ready) { withFrameNanos { }; if (active) visible() }
    }

    LaunchedEffect(Unit) {
        delay(10_000)
        if (!ready && active) failed = true
    }

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

    Dialog(
        onDismissRequest = { mic = false; close() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(CelebrationPalette.bgDeep)
        ) {
            AndroidView(
                factory = { ctx ->
                    android.widget.FrameLayout(ctx).apply {
                        try {
                            val config = JSONObject()
                                .put("title", strings.titles.last())
                                .put("wishes", JSONArray(strings.wishes))
                                .put("captions", JSONArray(strings.captions))
                                .put("continueLabel", strings.continueListening)
                                .put("replayLabel", strings.replay)
                            val web = BirthdayWebView(ctx, config)
                            view = web
                            addView(web, android.widget.FrameLayout.LayoutParams(-1, -1))
                        } catch (_: Exception) { post { failed = true } }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { host ->
                    (host.getChildAt(0) as? BirthdayWebView)?.release()
                    view = null
                },
                update = { host ->
                    (host.getChildAt(0) as? BirthdayWebView)?.onEvent = { event ->
                        when (event) {
                            "READY" -> ready = true
                            "ERROR" -> { mic = false; failed = true }
                            "CLOSE" -> { mic = false; close() }
                            "PLAYING" -> { state = event; mic = false; feedback = "" }
                            else -> { state = event; if (event != "WAITING") mic = false }
                        }
                    }
                },
            )

            // 顶栏品牌微标与关闭按钮
            Row(
                Modifier
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetroText(
                    "ARRIS / CELEBRATION",
                    color = CelebrationPalette.textTitle.copy(alpha = 0.5f),
                    style = TextStyle(
                        fontSize = 11.sp,
                        letterSpacing = 2.4.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                CelebrationCloseButton(onClick = { mic = false; close() })
            }

            // 底部吹气交互面板
            BirthdayActions(
                state = state,
                mic = mic,
                armed = armed,
                feedback = feedback,
                modifier = Modifier.align(Alignment.BottomCenter),
                onMic = {
                    if (!mic && active) {
                        feedback = ""
                        if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) mic = true
                        else permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onBlow = ::extinguish,
            )
        }
    }
}

@Composable
private fun BirthdayActions(
    state: String,
    mic: Boolean,
    armed: Boolean,
    feedback: String,
    modifier: Modifier,
    onMic: () -> Unit,
    onBlow: () -> Unit,
) {
    if (state != "WAITING") return

    val s = LocalStrings.current.celebration
    val pal = CelebrationPalette
    val landscape =
        LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .clip(AppShapes.extraLarge)
                .background(pal.glassCardBgElevated, AppShapes.extraLarge)
                .border(1.dp, pal.glassCardBorder, AppShapes.extraLarge)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (mic) {
                AcousticBreathingRing(armed = armed)
                Spacer(Modifier.height(10.dp))
            }

            MetroText(
                text = feedback.ifBlank { s.micHint },
                color = if (feedback.isNotEmpty()) pal.textFeedback else pal.textBody,
                style = TextStyle(
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                ),
            )
            Spacer(Modifier.height(14.dp))

            val micLabel = when {
                !mic -> s.micStart
                armed -> s.micListening
                else -> s.micCalibrating
            }

            if (landscape) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CelebrationPillButton(
                        text = micLabel,
                        onClick = onMic,
                        enabled = !mic,
                        candle = true,
                        leadingIcon = if (!mic) Icons.Default.Mic else null,
                        modifier = Modifier.weight(1f)
                    )
                    CelebrationPillButton(
                        text = s.manualBlow,
                        onClick = onBlow,
                        primary = false,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                CelebrationPillButton(
                    text = micLabel,
                    onClick = onMic,
                    enabled = !mic,
                    candle = true,
                    leadingIcon = if (!mic) Icons.Default.Mic else null,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                CelebrationPillButton(
                    text = s.manualBlow,
                    onClick = onBlow,
                    primary = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
