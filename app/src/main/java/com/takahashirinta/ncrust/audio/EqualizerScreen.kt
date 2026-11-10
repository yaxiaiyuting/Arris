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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.ui.components.ArrisFullScreenDialog
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.theme.AppShapes
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.controls.MetroSwitch
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import kotlin.math.roundToInt
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/** A separate window keeps the editor above both the tray and the expanded player. */
@Composable
fun EqualizerScreen(onBack: () -> Unit) {
    ArrisFullScreenDialog(onDismiss = onBack, contentAlignment = Alignment.TopCenter) {
        EqualizerEditor(onBack)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EqualizerEditor(onBack: () -> Unit) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    var enabled by remember { mutableStateOf(EqualizerPrefs.isEnabled(context)) }
    var gains by remember { mutableStateOf(EqualizerPrefs.gains(context)) }
    var preset by remember { mutableStateOf(EqualizerPrefs.presetName(context)) }
    val activeBands by EqualizerController.activeBandCount.collectAsState()

    fun pushToAudio() = EqualizerController.setEnabled(enabled, gains)

    fun updateEnabled(value: Boolean) {
        if (value == enabled) return
        enabled = value
        EqualizerPrefs.setEnabled(context, value)
        pushToAudio()
    }

    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetroIconButton(onClick = onBack) {
                MetroIcon(Icons.AutoMirrored.Filled.ArrowBack, strings.back, tint = colors.onBackground)
            }
            MetroText(
                strings.settings.equalizerEntryLabel,
                color = colors.onBackground,
                style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Box(
                modifier = Modifier.heightIn(min = 48.dp)
                    .toggleable(enabled, role = Role.Switch, onValueChange = ::updateEnabled)
                    .semantics { contentDescription = strings.settings.equalizerEntryLabel },
                contentAlignment = Alignment.Center,
            ) {
                MetroSwitch(checked = enabled, onCheckedChange = ::updateEnabled)
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetroText(
                when {
                    activeBands > 0 -> "已接到当前播放 · 设备 $activeBands 段"
                    !enabled -> "开启后应用于当前播放"
                    else -> "等待设备的音频会话"
                },
                color = colors.onSurfaceVariant, style = TextStyle(fontSize = 13.sp)
            )
            EqualizerCurve(gains = gains, active = enabled, accent = colors.primary, grid = colors.surfaceVariant)
            MetroText("预设", color = colors.onSurfaceVariant, style = TextStyle(fontSize = 13.sp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EqualizerPrefs.PRESETS.forEach { (name, _) ->
                    val active = preset == name
                    MetroText(
                        name,
                        color = if (active) colors.onPrimary else colors.onSurfaceVariant,
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        modifier = Modifier
                            .background(if (active) colors.primary else colors.surfaceVariant, AppShapes.full)
                            .selectable(active, role = Role.RadioButton) {
                                preset = name
                                EqualizerPrefs.applyPreset(context, name)
                                gains = EqualizerPrefs.gains(context)
                                pushToAudio()
                            }
                            .padding(horizontal = 14.dp, vertical = 11.dp)
                    )
                }
            }

            MetroText("频段（dB）", color = colors.onSurfaceVariant, style = TextStyle(fontSize = 13.sp))
            gains.forEachIndexed { index, gain ->
                val frequencyHz = EqualizerPrefs.frequenciesHz[index]
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MetroText(
                        if (frequencyHz < 1000) "$frequencyHz" else "${frequencyHz / 1000}k",
                        color = colors.onSurfaceVariant,
                        style = TextStyle(fontSize = 12.sp),
                        modifier = Modifier.width(48.dp)
                    )
                    EqualizerBandBar(
                        gainMb = gain,
                        label = "$frequencyHz Hz",
                        color = if (enabled) colors.primary else colors.onSurfaceVariant,
                        track = colors.surfaceVariant,
                        modifier = Modifier.weight(1f),
                        onGainChanged = { newGain ->
                            if (newGain != gains[index]) {
                                preset = null
                                gains = gains.copyOf().also { it[index] = newGain }
                                EqualizerPrefs.setGain(context, index, newGain)
                                pushToAudio()
                            }
                        },
                    )
                    MetroText(
                        "%+.1f".format(gain / 100f),
                        color = colors.onBackground,
                        style = TextStyle(fontSize = 12.sp),
                        modifier = Modifier.width(58.dp).padding(start = 8.dp)
                    )
                }
            }
            MetroText(
                "调整只作用于本机播放。下载文件保留原样。",
                color = colors.onSurfaceVariant, style = TextStyle(fontSize = 12.sp)
            )
        }
    }
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
    label: String,
    color: Color,
    track: Color,
    modifier: Modifier = Modifier,
    onGainChanged: (Int) -> Unit,
) {
    val updateGain by rememberUpdatedState(onGainChanged)
    var widthPx by remember { mutableStateOf(1f) }
    fun gainAt(x: Float): Int {
        val ratio = (x / widthPx).coerceIn(0f, 1f)
        val raw = EqualizerController.GAIN_MIN_MB +
            ratio * (EqualizerController.GAIN_MAX_MB - EqualizerController.GAIN_MIN_MB)
        // 50mB = 0.5dB 一档：手指精度足够，也不会把预置变成一串奇怪的小数。
        return (raw / 50f).roundToInt() * 50
    }
    Box(
        modifier
            .height(36.dp)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(gainMb / 100f, -12f..12f, 47)
                setProgress { db ->
                    updateGain((db.coerceIn(-12f, 12f) * 2).roundToInt() * 50)
                    true
                }
            }
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { offset -> updateGain(gainAt(offset.x)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    updateGain(gainAt(change.position.x))
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
