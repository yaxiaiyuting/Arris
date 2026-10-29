/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.takahashirinta.ncrust.listen.CanonicalTrack
import com.takahashirinta.ncrust.listen.DriftPolicy
import com.takahashirinta.ncrust.listen.RoomClock
import com.takahashirinta.ncrust.listen.RoomEngine
import com.takahashirinta.ncrust.listen.RoomOp
import com.takahashirinta.ncrust.listen.RoomState
import com.takahashirinta.ncrust.recognize.MicCapture
import com.takahashirinta.ncrust.recognize.ShazamFingerprint
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **开发者实验室**（v3.8.0 · 临时诊断面）。
 *
 * 为什么现在长这样：一起听与听歌识曲的可验证部分都还停在"纯逻辑层"——
 * 一起听缺服务端与协议探针，识曲缺网络 Provider。为了让这两个模块在真机上
 * **能被观测**（而不是只在 JVM 单测里绿），先把它们的确定性部分做成自测：
 *
 * - 一起听：跑一遍房主状态机 + 时钟 + 漂移判定，把关键数值打在屏幕上；
 * - 识曲：真录 6 秒麦克风 → 算指纹 → 打印峰/哈希数与 RMS，再录一次算重合率。
 *
 * 入口是「关于」页版本号**长按**（有意不放进设置页）：它不是产品 UI，
 * 不参与 i18n 与文案评审；等真实功能上线时这一页整体删除。
 */
@Composable
fun LabScreen(onClose: () -> Unit) {
    val colors = LocalMetroColors.current
    val context = LocalContext.current
    var roomReport by remember { mutableStateOf("（未运行）") }
    var micReport by remember { mutableStateOf("（未录音）") }
    var lastFingerprint by remember { mutableStateOf<ShazamFingerprint.Fingerprint?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) micReport = "权限被拒绝：麦克风不可用"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MetroText("实验室 · LAB", color = colors.primary, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold))
            MetroText("v3.8.0 临时诊断面：一起听状态机 / 识曲采集与指纹", color = colors.onSurfaceVariant, style = TextStyle(fontSize = 13.sp))

            LabButton("① 一起听自测（状态机 + 时钟 + 漂移）", enabled = !busy) {
                roomReport = runRoomSelfTest()
            }

            MetroText(roomReport, color = colors.onBackground, style = TextStyle(fontSize = 12.sp))

            LabButton("② 录 6 秒并算指纹", enabled = !busy) {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    micReport = "正在申请麦克风权限，授权后再点一次"
                } else {
                    busy = true
                    micReport = "录音中…"
                    // 采集 + 指纹是 CPU/IO 活，放到后台，避免主线程卡住（ANR）。
                    scope.launch(Dispatchers.Default) {
                        val pcm = MicCapture.record(6_000)
                        val text = if (pcm == null) {
                            "采集失败（设备占用或权限）"
                        } else {
                            val fp = ShazamFingerprint.compute(pcm)
                            lastFingerprint = fp
                            val rms = ShazamFingerprint.rms(pcm)
                            "采样=${pcm.size} RMS=%.4f %s".format(rms, ShazamFingerprint.summarize(fp))
                        }
                        withContext(Dispatchers.Main) {
                            micReport = text
                            busy = false
                        }
                    }
                }
            }

            LabButton("③ 再录 6 秒算重合率（验同源可复用）", enabled = !busy) {
                val base = lastFingerprint
                if (base == null) {
                    micReport = "先做 ②，再对比"
                } else {
                    busy = true
                    scope.launch(Dispatchers.Default) {
                        val pcm = MicCapture.record(6_000)
                        val text = if (pcm == null) {
                            "第二次采集失败"
                        } else {
                            val fp = ShazamFingerprint.compute(pcm)
                            val ratio = ShazamFingerprint.overlapRatio(base.hashes, fp.hashes)
                            "第二次 %s；重合率=%.3f".format(ShazamFingerprint.summarize(fp), ratio)
                        }
                        withContext(Dispatchers.Main) {
                            micReport = text
                            busy = false
                        }
                    }
                }
            }

            MetroText(micReport, color = colors.onBackground, style = TextStyle(fontSize = 12.sp))

            MetroText(
                "网络识别未接通：Shazam 协议尚未用真实请求验证，第一版不接。",
                color = colors.onSurfaceVariant,
                style = TextStyle(fontSize = 12.sp),
            )

            LabButton("关闭", enabled = true) { onClose() }
        }
    }
}

@Composable
private fun LabButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalMetroColors.current
    MetroText(
        label,
        color = if (enabled) colors.primary else colors.onSurfaceVariant,
        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/** 一起听自测：把确定性部分跑一遍，输出可肉眼核对的数值。 */
private fun runRoomSelfTest(): String {
    val a = CanonicalTrack(roomTrackId = "a", title = "晴天", artist = "周杰伦", durationMs = 269_000)
    val b = CanonicalTrack(roomTrackId = "b", title = "夜曲", artist = "周杰伦", durationMs = 226_000)
    var state = RoomState(roomId = "lab", hostUserId = "host", stampedAtMs = 1_000)

    fun apply(op: RoomOp): String = when (val r = RoomEngine.apply(state, op, nowMs = op.seq * 1_000L)) {
        is RoomEngine.ApplyResult.Applied -> { state = r.state; "seq=${op.seq} ok" }
        is RoomEngine.ApplyResult.Rejected -> "seq=${op.seq} rejected(${r.reason})"
    }

    val log = StringBuilder()
    log.appendLine("协议版本=${state.protocolVersion}")
    log.appendLine("SetQueue(a,b) → " + apply(RoomOp.SetQueue(seq = 1, byUserId = "host", tracks = listOf(a, b))))
    log.appendLine("Play(0) → " + apply(RoomOp.Play(seq = 2, byUserId = "host", positionMs = 0)))
    log.appendLine("成员点歌被拒 → " + apply(RoomOp.Pause(seq = 3, byUserId = "member", positionMs = 5_000)))
    log.appendLine("重复 seq 被拒 → " + apply(RoomOp.Pause(seq = 3, byUserId = "host", positionMs = 5_000)))
    log.appendLine("Next → " + apply(RoomOp.Next(seq = 4, byUserId = "host")))
    log.appendLine("当前曲=${state.currentTrack?.title} 下标=${state.currentIndex}")

    val estimate = RoomClock.estimateOffset(
        listOf(
            RoomClock.Sample(sentAtMs = 0, receivedAtMs = 120, relayNowAtReplyMs = 10_000),
            RoomClock.Sample(sentAtMs = 200, receivedAtMs = 240, relayNowAtReplyMs = 10_200),
        )
    )
    log.appendLine("时钟 offset=${estimate?.offsetMs}ms rtt=${estimate?.rttMs}ms")
    val target = RoomClock.targetPositionMs(
        transport = state.transport,
        stampedAtMs = state.stampedAtMs,
        localNowMs = state.stampedAtMs + 2_000,
        estimate = estimate,
        durationMs = state.currentTrack?.durationMs ?: 0L,
    )
    log.appendLine("2 秒后的目标位置=${target}ms（公式不再减单程延迟）")
    log.appendLine("漂移 30ms → ${DriftPolicy.decide(30, 0)}")
    log.appendLine("漂移 150ms → ${DriftPolicy.decide(150, 0)}")
    log.appendLine("漂移 900ms → ${DriftPolicy.decide(900, 0)}")
    return log.toString()
}
