/* Arris · Copyright (c) 2026 yaxiaiyuting, GPLv3. */
package com.takahashirinta.ncrust.ui.screen

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.takahashirinta.ncrust.ui.components.ArrisSegmentedTabs
import com.takahashirinta.ncrust.ui.components.arrisSurface
import com.takahashirinta.ncrust.ui.theme.AppShapes
import io.github.takahashirinta.kanesumi.core.theme.*
import kotlinx.coroutines.*

private enum class RecognitionSource(val label: String, val prompt: String, val disclosure: String) {
    MICROPHONE(
        "麦克风", "让手机靠近音源，留住偶然听见的喜欢。",
        "点击开始后使用麦克风，音频片段将提交给网易云识曲。离开页面或退到后台会停止。",
    ),
    PLAYBACK(
        "系统声音", "让音乐继续播放，然后开始识别。",
        "Android 会请求录音与系统共享授权。Arris 只采集允许录制的媒体声音，片段将提交给网易云识曲。离开页面或退到后台会停止。",
    ),
}

private enum class RecognitionPhase { IDLE, AUTHORIZATION, RECORDING, MATCHING }

/** Only a user-started attempt can capture or submit, and it ends when the screen leaves. */
@Composable
fun RecognizeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var source by remember { mutableStateOf(RecognitionSource.MICROPHONE) }
    var phase by remember { mutableStateOf(RecognitionPhase.IDLE) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(RecognitionSource.MICROPHONE.prompt) }
    var result by remember { mutableStateOf<RecogOutcome?>(null) }
    var audioPermissionResult by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var projectionResult by remember { mutableStateOf<CompletableDeferred<ActivityResult>?>(null) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        audioPermissionResult?.complete(granted)
        audioPermissionResult = null
    }
    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { grant ->
        projectionResult?.complete(grant)
        projectionResult = null
    }

    suspend fun requestAudioPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        val reply = CompletableDeferred<Boolean>()
        audioPermissionResult = reply
        try {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        } catch (e: RuntimeException) {
            audioPermissionResult = null
            throw e
        }
        return reply.await()
    }

    suspend fun requestPlaybackConsent(): Intent? {
        val manager = checkNotNull(context.getSystemService(MediaProjectionManager::class.java))
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        val reply = CompletableDeferred<ActivityResult>()
        projectionResult = reply
        try {
            projection.launch(intent)
        } catch (e: RuntimeException) {
            projectionResult = null
            throw e
        }
        val grant = reply.await()
        return grant.data.takeIf { grant.resultCode == Activity.RESULT_OK }
    }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        phase = RecognitionPhase.IDLE
        countdown = 0
        status = "已停止识别，准备好后可以再试一次。"
    }

    fun start() {
        if (phase != RecognitionPhase.IDLE || audioPermissionResult != null || projectionResult != null ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return
        val snapshot = CookieManager.snapshot()
        val cookie = snapshot.cookie.orEmpty()
        if (cookie.isBlank()) {
            status = "请先登录网易云音乐，再开始识别。"
            return
        }
        val captureSource = source
        val attempt = ++generation
        phase = RecognitionPhase.AUTHORIZATION
        result = null
        status = "正在确认录音权限…"
        job = scope.launch {
            try {
                if (!requestAudioPermission()) {
                    status = "需要录音权限才能识曲，你可以在系统设置中开启。"
                    return@launch
                }
                lifecycle.withResumed { }
                val consent = if (captureSource == RecognitionSource.PLAYBACK) {
                    status = "请确认 Android 的系统声音采集授权。"
                    requestPlaybackConsent() ?: run {
                        status = "未授权采集系统声音，可以重新开始。"
                        return@launch
                    }
                } else null
                lifecycle.withResumed { }
                ensureActive()
                if (CookieManager.snapshot() != snapshot) {
                    status = "账号状态已变化，请重新开始识别。"
                    return@launch
                }

                phase = RecognitionPhase.RECORDING
                status = if (captureSource == RecognitionSource.PLAYBACK) "正在采集系统声音，请保持音乐播放。"
                    else "正在聆听，请保持音源清晰。"
                val ticker = launch {
                    for (left in 10 downTo 1) {
                        if (generation == attempt) countdown = left
                        delay(1_000)
                    }
                    if (generation == attempt) countdown = 0
                }
                val clip = try {
                    if (consent == null) {
                        val bytes = runInterruptible(Dispatchers.IO) { AacRecorder.record(context) }
                        bytes?.let { RecogInput.Encoded(it, "audio/mp4", AacRecorder.DEFAULT_DURATION_MS.toLong()) }
                    } else {
                        when (val captured = PlaybackRecognitionService.capture(context, consent)) {
                            is PlaybackCaptureResult.Recorded -> captured.clip
                            is PlaybackCaptureResult.Failed -> {
                                status = when (captured.reason) {
                                    RecogFailure.SILENT_INPUT -> "没有采到播放声。请保持音乐播放，或换一个允许录制的音源。"
                                    RecogFailure.PERMISSION_DENIED -> "系统采集未获授权或已停止，请重新开始。"
                                    RecogFailure.TIMEOUT -> "系统声音采集超时，请重新开始。"
                                    else -> "系统声音采集暂不可用，请重试或选用麦克风。"
                                }
                                return@launch
                            }
                        }
                    }
                } finally {
                    ticker.cancel()
                    if (generation == attempt) countdown = 0
                }
                ensureActive()
                if (clip == null) {
                    status = "录音失败，请确认麦克风可用后重试。"
                    return@launch
                }
                if (CookieManager.snapshot() != snapshot) {
                    status = "账号状态已变化，请重新开始识别。"
                    return@launch
                }
                phase = RecognitionPhase.MATCHING
                status = "正在寻找这段旋律…"
                val outcome = withContext(Dispatchers.IO) {
                    NeteaseRecognizeProvider(cookieProvider = { cookie }).recognize(clip)
                }
                ensureActive()
                if (CookieManager.snapshot() != snapshot) {
                    status = "账号状态已变化，请重新开始识别。"
                    return@launch
                }
                result = outcome
                status = when (outcome) {
                    is RecogOutcome.Matched -> "找到这首歌了。"
                    is RecogOutcome.Failed -> when (outcome.failure) {
                        RecogFailure.NO_MATCH -> "暂时没听出来，试试换一段更清晰的旋律。"
                        RecogFailure.NO_NETWORK -> "网络暂时不可用，连接恢复后再试。"
                        RecogFailure.PERMISSION_DENIED, RecogFailure.QUOTA_EXCEEDED -> "请检查网易云登录状态后重试。"
                        else -> "识别暂未成功，请稍后重试。"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == attempt) status = "识别暂未成功，请稍后重试。"
            } finally {
                if (generation == attempt) {
                    phase = RecognitionPhase.IDLE
                    countdown = 0
                    job = null
                }
            }
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            // The permission activity can hide this Activity. Only actual capture or submission is interrupted.
            if (event == Lifecycle.Event.ON_STOP && (phase == RecognitionPhase.RECORDING || phase == RecognitionPhase.MATCHING)) {
                cancel()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            job?.cancel()
        }
    }
    BackHandler { cancel(); onBack() }
    RecognitionContent(
        source = source,
        phase = phase,
        countdown = countdown,
        status = status,
        result = result,
        onBack = { cancel(); onBack() },
        onSelectSource = {
            source = it
            status = it.prompt
            result = null
        },
        onAction = { if (phase == RecognitionPhase.IDLE) start() else cancel() },
    )
}

@Composable
private fun RecognitionContent(
    source: RecognitionSource,
    phase: RecognitionPhase,
    countdown: Int,
    status: String,
    result: RecogOutcome?,
    onBack: () -> Unit,
    onSelectSource: (RecognitionSource) -> Unit,
    onAction: () -> Unit,
) {
    val c = LocalMetroColors.current
    val busy = phase != RecognitionPhase.IDLE
    Box(Modifier.fillMaxSize().background(c.background), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxHeight().statusBarsPadding()
            .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ArrisPageHeader("听歌识曲", "留住偶然听见的喜欢。", onBack = onBack)
            ArrisSegmentedTabs(
                items = RecognitionSource.entries.map { it.label },
                selected = source.ordinal,
                enabled = if (busy) listOf(false, false) else null,
                onSelect = { onSelectSource(RecognitionSource.entries[it]) },
            )
            RecognitionCard(source, phase, countdown, status, result is RecogOutcome.Matched, result != null, onAction)
            val match = (result as? RecogOutcome.Matched)?.match
            if (match != null) {
                Row(Modifier.fillMaxWidth().arrisSurface().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(c.primary.copy(alpha = .07f), AppShapes.medium),
                        contentAlignment = Alignment.Center) {
                        MetroIcon(Icons.Default.MusicNote, null, tint = c.primary, sizeDp = 22.dp)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        MetroText(match.title, color = c.onBackground, style = LocalMetroTypography.current.titleMedium)
                        if (match.artist.isNotBlank()) MetroText(match.artist, color = c.onSurfaceVariant)
                        if (match.album.isNotBlank()) MetroText(match.album, color = c.onSurfaceVariant, style = LocalMetroTypography.current.caption)
                    }
                }
            }
            MetroText("10 秒聆听 · 网易云账号识别", color = c.primary, style = LocalMetroTypography.current.caption)
            MetroText(source.disclosure,
                color = c.onSurfaceVariant, style = TextStyle(fontSize = 12.sp, lineHeight = 20.sp))
        }
    }
}

@Composable
private fun RecognitionCard(
    source: RecognitionSource,
    phase: RecognitionPhase,
    countdown: Int,
    status: String,
    matched: Boolean,
    hasResult: Boolean,
    onAction: () -> Unit,
) {
    val c = LocalMetroColors.current
    val busy = phase != RecognitionPhase.IDLE
    Column(Modifier.fillMaxWidth().arrisSurface(shape = AppShapes.extraLarge).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        RecognitionGraphic(source, countdown, matched, processing = phase == RecognitionPhase.MATCHING)
        Spacer(Modifier.height(16.dp))
        val headline = when {
            countdown > 0 -> "正在聆听 · ${countdown}s"
            phase == RecognitionPhase.AUTHORIZATION -> "等待授权"
            busy -> "正在识别"
            else -> "让旋律留下名字"
        }
        MetroText(headline,
            color = c.onBackground, style = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center))
        Spacer(Modifier.height(10.dp))
        MetroText(status, color = c.onSurfaceVariant, style = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
        Spacer(Modifier.height(24.dp))
        ArrisButton(if (busy) "停止识别" else if (hasResult) "再听一次" else "开始识别", Modifier.fillMaxWidth(), onClick = onAction)

    }
}

@Composable
private fun RecognitionGraphic(source: RecognitionSource, countdown: Int, matched: Boolean, processing: Boolean) {
    val c = LocalMetroColors.current
    // The side marks are a static sound motif, not a fabricated live microphone meter.
    val marks = remember { floatArrayOf(7f, 15f, 25f, 13f, 31f, 18f) }
    Box(Modifier.size(156.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val ringRadius = size.minDimension / 2f - 9.dp.toPx()
            drawCircle(c.onBackground.copy(alpha = 0.08f), radius = ringRadius, style = Stroke(1.dp.toPx()))
            // Ten quiet marks express the actual ten-second recording budget.
            repeat(10) { index ->
                val angle = Math.toRadians(index * 36.0 - 90.0)
                val vector = Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
                drawLine(c.onBackground.copy(alpha = 0.16f),
                    start = center + vector * (ringRadius - 4.dp.toPx()),
                    end = center + vector * (ringRadius - 7.dp.toPx()),
                    strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
            }
            marks.forEachIndexed { index, height ->
                val x = (40 + index * 5).dp.toPx()
                val half = height.dp.toPx() / 2f
                for (side in listOf(-1f, 1f)) {
                    drawLine(c.primary.copy(alpha = .16f + index * .025f),
                        Offset(center.x + x * side, center.y - half),
                        Offset(center.x + x * side, center.y + half),
                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            if (countdown > 0 || matched || processing) drawArc(c.primary, -90f,
                if (matched || processing) 360f else (10 - countdown).coerceIn(0, 10) / 10f * 360f, false,
                topLeft = center - Offset(ringRadius, ringRadius),
                size = Size(ringRadius * 2f, ringRadius * 2f),
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        Box(Modifier.size(64.dp).arrisSurface(shape = AppShapes.full, emphasized = countdown > 0 || matched),
            contentAlignment = Alignment.Center) {
            val icon = when {
                matched -> Icons.Default.Check
                source == RecognitionSource.PLAYBACK -> Icons.Default.GraphicEq
                else -> Icons.Default.Mic
            }
            MetroIcon(icon,
                contentDescription = null, tint = c.primary, sizeDp = 27.dp)
        }
    }
}
