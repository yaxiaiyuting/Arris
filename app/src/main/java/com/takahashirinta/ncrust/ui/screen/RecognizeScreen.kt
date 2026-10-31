/* Arris · Copyright (c) 2026 yaxiaiyuting, GPLv3. */
package com.takahashirinta.ncrust.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.withResumed
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.recognize.*
import com.takahashirinta.ncrust.ui.components.ArrisButton
import com.takahashirinta.ncrust.ui.components.ArrisPageHeader
import com.takahashirinta.ncrust.ui.theme.AppShapes
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors
import io.github.takahashirinta.kanesumi.core.theme.*
import kotlinx.coroutines.*

/** A bounded, explicit recording flow. Leaving/backgrounding cancels recording and submission. */
@Composable
fun RecognizeScreen(onBack: () -> Unit) {
    val c = LocalMetroColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("让手机靠近音源，留住偶然听见的喜欢。") }
    var result by remember { mutableStateOf<RecogOutcome?>(null) }

    fun cancel() {
        generation++
        job?.cancel(); job = null
        busy = false; countdown = 0
        status = "已停止识别，准备好后可以再试一次。"
    }
    fun start() {
        if (busy || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val snapshot = CookieManager.snapshot()
        val cookie = snapshot.cookie.orEmpty()
        if (cookie.isBlank()) { status = "请先登录网易云音乐，再开始识别。"; return }
        busy = true; result = null; countdown = 10; status = "正在聆听，请保持音源清晰。"
        val attempt = ++generation
        job = scope.launch {
            try {
                val ticker = launch {
                    for (left in 10 downTo 1) { if (generation == attempt) countdown = left; delay(1_000) }
                }
                val audio = try {
                    runInterruptible(Dispatchers.IO) { AacRecorder.record(context, 10_000) }
                } finally { ticker.cancel(); if (generation == attempt) countdown = 0 }
                ensureActive()
                if (audio == null) { status = "录音失败，请确认麦克风可用后重试。"; return@launch }
                if (CookieManager.snapshot() != snapshot) {
                    status = "账号状态已变化，请重新开始识别。"; return@launch
                }
                status = "正在寻找这段旋律…"
                val outcome = withContext(Dispatchers.IO) {
                    NeteaseRecognizeProvider(cookieProvider = { cookie })
                        .recognize(RecogInput.Encoded(audio, "audio/mp4", durationMs = 10_000))
                }
                ensureActive()
                if (CookieManager.snapshot() != snapshot) {
                    status = "账号状态已变化，请重新开始识别。"; return@launch
                }
                result = outcome
                status = when (outcome) {
                    is RecogOutcome.Matched -> "找到这首歌了。"
                    is RecogOutcome.Failed -> when (outcome.failure.name) {
                        "NO_MATCH" -> "暂时没听出来，试试靠近音源，或换一段更清晰的旋律。"
                        "NO_NETWORK" -> "网络暂时不可用，连接恢复后再试。"
                        "PERMISSION_DENIED", "QUOTA_EXCEEDED" -> "请检查网易云登录状态后重试。"
                        else -> "识别暂未成功，请稍后重试。"
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status = "识别暂未成功，请稍后重试。" }
            finally { if (generation == attempt) { busy = false; countdown = 0 } }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) scope.launch { lifecycle.withResumed { start() } } else status = "需要麦克风权限才能识曲，你可以在系统设置中开启。"
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && busy) cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel() }
    }
    BackHandler { cancel(); onBack() }
    RecognitionContent(busy, countdown, status, result, onBack = { cancel(); onBack() }) {
        if (busy) cancel()
        else if (CookieManager.snapshot().cookie.isNullOrBlank()) status = "请先登录网易云音乐，再开始识别。"
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
}

@Composable
private fun RecognitionContent(busy: Boolean, countdown: Int, status: String, result: RecogOutcome?,
                               onBack: () -> Unit, onAction: () -> Unit) {
    val c = LocalMetroColors.current
    Box(Modifier.fillMaxSize().background(c.background), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxHeight().statusBarsPadding()
            .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ArrisPageHeader("听歌识曲", "ARRIS / DISCOVER", "留住偶然听见的喜欢。", onBack = onBack)
            RecognitionCard(busy, countdown, status, result is RecogOutcome.Matched, result != null, onAction)
            val match = (result as? RecogOutcome.Matched)?.match
            if (match != null) {
                Row(Modifier.fillMaxWidth().clip(AppShapes.large).background(c.surface).padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    MetroIcon(Icons.Default.MusicNote, null, tint = c.primary, sizeDp = 32.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        MetroText(match.title, color = c.onBackground, style = LocalMetroTypography.current.titleMedium)
                        if (match.artist.isNotBlank()) MetroText(match.artist, color = c.onSurfaceVariant)
                        if (match.album.isNotBlank()) MetroText(match.album, color = c.onSurfaceVariant, style = LocalMetroTypography.current.caption)
                    }
                }
            }
            MetroText("10 秒聆听 · 网易云账号识别", color = c.primary, style = LocalMetroTypography.current.caption)
            MetroText("仅在你点击开始后使用麦克风，录音将提交给网易云识曲服务。离开页面或退到后台会停止本次识别。",
                color = c.onSurfaceVariant, style = TextStyle(fontSize = 12.sp, lineHeight = 20.sp))
        }
    }
}

@Composable
private fun RecognitionCard(busy: Boolean, countdown: Int, status: String, matched: Boolean,
                            hasResult: Boolean, onAction: () -> Unit) {
    val c = LocalMetroColors.current
    Column(Modifier.fillMaxWidth().clip(AppShapes.extraLarge).background(c.surface)
        .border(1.dp, LocalNcrustColors.current.outlineVariant, AppShapes.extraLarge).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        RecognitionGraphic(countdown, matched)
        Spacer(Modifier.height(16.dp))
        MetroText(if (countdown > 0) "正在聆听 · ${countdown}s" else if (busy) "正在识别" else "让旋律留下名字",
            color = c.onBackground, style = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(10.dp))
        MetroText(status, color = c.onSurfaceVariant, style = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Spacer(Modifier.height(24.dp))
        ArrisButton(if (busy) "停止识别" else if (hasResult) "再听一次" else "开始识别", Modifier.fillMaxWidth(), onClick = onAction)

    }
}

@Composable
private fun RecognitionGraphic(countdown: Int, matched: Boolean) {
    val c = LocalMetroColors.current
    Box(Modifier.size(204.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(c.primary.copy(alpha = 0.06f), radius = size.minDimension / 2)
            drawCircle(c.primary.copy(alpha = 0.12f), radius = size.minDimension * .4f, style = Stroke(1.dp.toPx()))
            drawCircle(c.primary.copy(alpha = 0.15f), radius = size.minDimension * .32f, style = Stroke(1.dp.toPx()))
            if (countdown > 0) drawArc(c.primary, -90f, (10-countdown)/10f*360f, false,
                topLeft = androidx.compose.ui.geometry.Offset(8.dp.toPx(),8.dp.toPx()),
                size = androidx.compose.ui.geometry.Size((size.width-16.dp.toPx()).coerceAtLeast(0f),(size.height-16.dp.toPx()).coerceAtLeast(0f)), style = Stroke(3.dp.toPx()))
        }
        Box(Modifier.size(104.dp).clip(AppShapes.full).background(c.primary), contentAlignment = Alignment.Center) {
            MetroIcon(if (matched) Icons.Default.Check else Icons.Default.Mic,
                contentDescription = null, tint = c.onPrimary, sizeDp = 38.dp)
        }
    }
}
