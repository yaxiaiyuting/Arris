/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.recognize.AacRecorder
import com.takahashirinta.ncrust.recognize.NeteaseRecognizeProvider
import com.takahashirinta.ncrust.recognize.RecogInput
import com.takahashirinta.ncrust.recognize.RecogOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 听歌识曲（v3.8.0 正式入口）。
 *
 * 交互只有一件事：**按下 → 录 10 秒 → 出结果**。
 * 录音格式是官方客户端同款的 MP4/AAC（`MediaRecorder` 直出），
 * 请求走 `/eapi/music/hum/audio/unifymatch`（配方来自抓包，见 provider 注释）。
 *
 * 未登录时先提示登录：这个端点用**你自己的账号**发起，没有登录态会被服务端拒。
 */

@Composable
fun RecognizeScreen(onBack: () -> Unit) {
    val colors = LocalMetroColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RecogOutcome?>(null) }
    var status by remember { mutableStateOf("按下开始识别（录 10 秒，请把手机对着音源）") }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MetroText("听歌识曲", color = colors.onBackground, style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold))
        MetroText(
            "录音会上传到你的网易云账号，不会发给任何第三方服务。",
            color = colors.onSurfaceVariant,
            style = TextStyle(fontSize = 12.sp),
        )

        Spacer(Modifier.height(8.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(if (busy) colors.primary.copy(alpha = 0.3f) else colors.primary, RectangleShape)
                .clickable(enabled = !busy) {
                    val cookie = CookieManager.snapshot().cookie.orEmpty()
                    if (cookie.isBlank()) {
                        status = "未登录网易云：识别需要你的登录态"
                    } else {
                        busy = true
                        result = null
                        status = "录音中…（10 秒）"
                        scope.launch {
                            val audio = withContext(Dispatchers.IO) { AacRecorder.record(context, 10_000) }
                            if (audio == null) {
                                status = "录音失败（权限被拒或麦克风被占用）"
                                busy = false
                            } else {
                                status = "上传识别…"
                                val outcome = withContext(Dispatchers.IO) {
                                    NeteaseRecognizeProvider(cookieProvider = { cookie })
                                        .recognize(RecogInput.Encoded(audio, "audio/mp4", durationMs = 10_000))
                                }
                                result = outcome
                                status = when (outcome) {
                                    is RecogOutcome.Matched -> "识别完成"
                                    is RecogOutcome.Failed -> when (outcome.failure.name) {
                                        "NO_MATCH" -> "没听出来：换个安静环境、把音量调大再试"
                                        "NO_NETWORK" -> "网络不通（${outcome.detail}）"
                                        "PERMISSION_DENIED", "QUOTA_EXCEEDED" -> "登录态失效，请重新登录网易云"
                                        else -> "失败：${outcome.detail}"
                                    }
                                }
                                busy = false
                            }
                        }
                    }
                }
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    text = if (busy) "识别中…" else "开始识别",
                    style = TextStyle(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onPrimary,
                    ),
                )
                if (busy) {
                    Spacer(Modifier.height(6.dp))
                    BasicText("录 10 秒后自动上传", style = TextStyle(fontSize = 12.sp, color = colors.onPrimary))
                }
            }
        }

        MetroText(status, color = colors.onSurfaceVariant, style = TextStyle(fontSize = 12.sp))

        val r = result
        if (r is RecogOutcome.Matched) {
            val m = r.match
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceVariant, RectangleShape)
                    .padding(16.dp),
            ) {
                Column {
                    MetroText(m.title, color = colors.onBackground, style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold))
                    Spacer(Modifier.height(4.dp))
                    if (m.artist.isNotBlank()) {
                        MetroText(m.artist, color = colors.onSurfaceVariant, style = TextStyle(fontSize = 14.sp))
                    }
                    if (m.album.isNotBlank()) {
                        MetroText("《${m.album}》", color = colors.onSurfaceVariant, style = TextStyle(fontSize = 12.sp))
                    }
                    if (m.durationMs > 0) {
                        MetroText(
                            "时长 ${m.durationMs / 1000 / 60}:${(m.durationMs / 1000 % 60).toString().padStart(2, '0')}",
                            color = colors.onSurfaceVariant,
                            style = TextStyle(fontSize = 12.sp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceVariant, RectangleShape)
                .clickable(onClick = onBack)
                .padding(14.dp),
        ) {
            MetroText("返回", color = colors.primary, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold))
        }
    }

}

