/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.audio

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 均衡器页面（4.0.0）。
 *
 * 视觉上只有三样东西：一条**可读的曲线**、五个预设、一排频段推子。
 * 曲线用我们自己的直角/细线语言画（不用 material 的 Slider 外观），
 * 推子本身是 `MetroSlider` 那样的一整条可点区域。
 */
@Composable
fun EqualizerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalMetroColors.current
    var enabled by remember { mutableStateOf(EqualizerPrefs.isEnabled(context)) }
    var gains by remember { mutableStateOf(EqualizerPrefs.gains(context)) }
    var preset by remember { mutableStateOf(EqualizerPrefs.presetName(context)) }
    val attached = EqualizerController.isAttached

    fun pushToAudio() {
        EqualizerController.setEnabled(context, enabled, gains)
    }

    Column(
        Modifier.fillMaxSize().background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            MetroText("均衡器", color = colors.onBackground, style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold))
            MetroText(
                if (enabled) "开" else "关",
                color = if (enabled) colors.primary else colors.onSurfaceVariant,
                style = TextStyle(fontSize = 14.sp)
            )
            Box(
                Modifier.size(width = 56.dp, height = 30.dp)
                    .background(if (enabled) colors.primary else colors.surfaceVariant)
                    .clickable {
                        enabled = !enabled
                        EqualizerPrefs.setEnabled(context, enabled)
                        pushToAudio()
                    }
            )
        }
        MetroText(
            if (attached) "已接到当前播放（10 段）" else "等待播放开始后生效",
            color = colors.onSurfaceVariant, style = TextStyle(fontSize = 12.sp)
        )

        EqualizerCurve(gains = gains, active = enabled, accent = colors.primary, grid = colors.surfaceVariant)

        MetroText("预设", color = colors.onSurfaceVariant, style = TextStyle(fontSize = 12.sp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EqualizerPrefs.PRESETS.forEach { (name, _) ->
                val active = preset == name
                MetroText(
                    name,
                    color = if (active) colors.onPrimary else colors.onSurfaceVariant,
                    style = TextStyle(fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                    modifier = Modifier
                        .background(if (active) colors.primary else colors.surfaceVariant)
                        .clickable {
                            preset = name
                            EqualizerPrefs.applyPreset(context, name)
                            gains = EqualizerPrefs.gains(context)
                            pushToAudio()
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        MetroText("频段（dB）", color = colors.onSurfaceVariant, style = TextStyle(fontSize = 12.sp))
        gains.forEachIndexed { index, gain ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MetroText(
                    bandLabel(index),
                    color = colors.onSurfaceVariant,
                    style = TextStyle(fontSize = 11.sp),
                    modifier = Modifier.width(52.dp)
                )
                EqualizerBandBar(
                    gainMb = gain,
                    color = if (enabled) colors.primary else colors.onSurfaceVariant,
                    track = colors.surfaceVariant,
                    modifier = Modifier.weight(1f),
                    onGainChanged = { newGain ->
                        gains = gains.copyOf().also { it[index] = newGain }
                        EqualizerPrefs.setGain(context, index, newGain)
                        pushToAudio()
                    },
                )
                MetroText(
                    "%+.1f".format(gain / 100f),
                    color = colors.onBackground,
                    style = TextStyle(fontSize = 11.sp),
                    modifier = Modifier.width(46.dp).padding(start = 8.dp)
                )
            }
        }

        MetroText(
            "调整只作用于本机播放链路，不写入音源，也不改变下载的文件。",
            color = colors.onSurfaceVariant, style = TextStyle(fontSize = 11.sp)
        )
        MetroText(
            "返回",
            color = colors.primary,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.clickable { onBack() }.padding(vertical = 8.dp)
        )
    }
}

private fun bandLabel(index: Int): String = when (index) {
    0 -> "31"; 1 -> "62"; 2 -> "125"; 3 -> "250"; 4 -> "500"
    5 -> "1k"; 6 -> "2k"; 7 -> "4k"; 8 -> "8k"; else -> "16k"
}

/** 曲线：把 10 段增益连成折线，±12dB 满量程。 */
@Composable
private fun EqualizerCurve(gains: IntArray, active: Boolean, accent: Color, grid: Color) {
    Canvas(
        Modifier.fillMaxWidth().height(120.dp).background(grid.copy(alpha = .35f)).padding(8.dp)
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        // 0dB 基准线与两条 ±6dB 参考线
        listOf(0.25f, 0.5f, 0.75f).forEach { f ->
            drawLine(Color.White.copy(alpha = .08f), Offset(0f, h * f), Offset(w, h * f), 1f)
        }
        if (gains.isEmpty()) return@Canvas
        val path = Path()
        gains.forEachIndexed { index, gain ->
            val x = w * index / (gains.size - 1).coerceAtLeast(1).toFloat()
            val norm = (gain - EqualizerController.GAIN_MIN_MB).toFloat() /
                (EqualizerController.GAIN_MAX_MB - EqualizerController.GAIN_MIN_MB)
            val y = h - norm * h
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, if (active) accent else accent.copy(alpha = .35f), style = Stroke(width = 2.5f))
        // 中点小方块：我们的设计语言里不用圆点
        gains.forEachIndexed { index, gain ->
            val x = w * index / (gains.size - 1).coerceAtLeast(1).toFloat()
            val norm = (gain - EqualizerController.GAIN_MIN_MB).toFloat() /
                (EqualizerController.GAIN_MAX_MB - EqualizerController.GAIN_MIN_MB)
            val y = h - norm * h
            drawRect(
                color = if (active) accent else accent.copy(alpha = .35f),
                topLeft = Offset(x - 2.5f, y - 2.5f),
                size = androidx.compose.ui.geometry.Size(5f, 5f)
            )
        }
        drawLine(Color.White.copy(alpha = .12f), Offset(0f, mid), Offset(w, mid), 1f)
    }
}


/**
 * 单个频段的可拖推子（4.0.0）。
 *
 * 用 `detectTapGestures(onPress)` + `detectHorizontalDragGestures` 手写，而不是 material 的
 * `Slider`：我们的界面不用 material3 组件，而且这里要的是「细线 + 竖标」的观感。
 * 拖动过程中只回调整数 mB，落盘与下发由调用方负责。
 */
@Composable
private fun EqualizerBandBar(
    gainMb: Int,
    color: Color,
    track: Color,
    modifier: Modifier = Modifier,
    onGainChanged: (Int) -> Unit,
) {
    var widthPx by remember { mutableStateOf(1f) }
    fun gainAt(x: Float): Int {
        val ratio = (x / widthPx).coerceIn(0f, 1f)
        val raw = EqualizerController.GAIN_MIN_MB +
            ratio * (EqualizerController.GAIN_MAX_MB - EqualizerController.GAIN_MIN_MB)
        // 50mB = 0.5dB 一档：手指精度足够，也不会把预置变成一串奇怪的小数。
        return (raw / 50f).toInt() * 50
    }
    Box(
        modifier
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { offset -> onGainChanged(gainAt(offset.x)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    onGainChanged(gainAt(change.position.x))
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val midY = size.height / 2f
            drawRect(track, size = size)
            drawLine(Color.White.copy(alpha = .12f), Offset(0f, midY), Offset(size.width, midY), 1f)
            val x = ((gainMb - EqualizerController.GAIN_MIN_MB).toFloat() /
                (EqualizerController.GAIN_MAX_MB - EqualizerController.GAIN_MIN_MB)) * size.width
            drawLine(color, Offset(x, 0f), Offset(x, size.height), 3f)
        }
    }
}
