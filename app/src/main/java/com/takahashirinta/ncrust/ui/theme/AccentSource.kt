/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明：
 *   - v1.2.0 · B2-C：主题色来源三选一（预设 / 封面 / 系统）与颜色处理。
 *     封面取色复用 PlaybackService 已有的 Palette 结果，不新增解码；任何来源的颜色
 *     都要过 processAccentColor（饱和度 ≤0.6 + 亮度锚定）与 onAccentColor（WCAG 对比度），
 *     否则高饱和封面会把界面变成霓虹灯、浅色主题色上的白字会看不清。
 */

package com.takahashirinta.ncrust.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.roundToInt

/** 主题色来源。默认 PRESET —— 保持既有观感不变。 */
enum class AccentSource { PRESET, COVER, SYSTEM }

private const val PREFS_NAME = "ncrust_settings"
private const val KEY_ACCENT_SOURCE = "accent_source"

/** 系统强调色（android.R.color.system_accent1_*）需要 API 31+。 */
val systemAccentSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

fun getSavedAccentSource(context: Context): AccentSource {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_ACCENT_SOURCE, null)
    return runCatching { AccentSource.valueOf(raw ?: "") }.getOrDefault(AccentSource.PRESET)
}

fun saveAccentSource(context: Context, source: AccentSource) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_ACCENT_SOURCE, source.name)
        .apply()
}

/**
 * 系统强调色。低版本返回 null（调用方回落预设色）—— 跨设备同步 prefs 可能把
 * SYSTEM 带到 API < 31 的设备上，这里必须挡住，而不是崩在 system_accent1_* 上。
 */
fun systemAccentColor(context: Context): Color? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Color(context.getColor(android.R.color.system_accent1_200))
    } else {
        null
    }

/**
 * RGB → HSV（h 0..360，s/v 0..1）。纯 Kotlin 实现：颜色数学不依赖 android.graphics，
 * 才能在 JVM 单测里真跑（framework 方法在单测里是未实现的 stub）。
 */
internal fun rgbToHsv(r: Int, g: Int, b: Int, out: FloatArray) {
    val rf = r / 255f
    val gf = g / 255f
    val bf = b / 255f
    val max = maxOf(rf, gf, bf)
    val min = minOf(rf, gf, bf)
    val d = max - min
    val h = when {
        d == 0f -> 0f
        max == rf -> 60f * ((((gf - bf) / d) % 6f + 6f) % 6f)
        max == gf -> 60f * (((bf - rf) / d) + 2f)
        else -> 60f * (((rf - gf) / d) + 4f)
    }
    out[0] = h
    out[1] = if (max == 0f) 0f else d / max
    out[2] = max
}

/** HSV → 不透明 ARGB。 */
internal fun hsvToArgb(h: Float, s: Float, v: Float): Int {
    val c = v * s
    val x = c * (1f - kotlin.math.abs(((h / 60f) % 2f) - 1f))
    val m = v - c
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    // 四舍五入而非截断：HSV→RGB→HSV 往返时把误差控制在 1 个色阶内。
    fun channel(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (channel(r1) shl 16) or (channel(g1) shl 8) or channel(b1)
}

/**
 * WCAG 2.1 相对亮度。
 *
 * v3.2.2：由 `private` 放宽到 `internal` —— 波形曲线上要判「当前是深色还是浅色主题」
 * （决定 HCT 三角色的 tone 区间），而判据必须是**同一个**亮度函数，
 * 不能在渲染层再抄一遍 WCAG 公式（两份公式必然分叉）。
 */
internal fun relativeLuminance(argb: Int): Double {
    fun channel(value: Int): Double {
        val s = value / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel((argb shr 16) and 0xFF) +
        0.7152 * channel((argb shr 8) and 0xFF) +
        0.0722 * channel(argb and 0xFF)
}

/** 两色对比度（1..21），onPrimary 的达标依据。 */
fun contrastRatio(foreground: Int, background: Int): Double {
    val a = relativeLuminance(foreground)
    val b = relativeLuminance(background)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}

/** 对比度下限：正文与按钮图标统一按 WCAG AA 4.5:1。 */
const val MIN_ACCENT_CONTRAST = 4.5

/** 饱和度上限：再高就是霓虹灯。 */
private const val MAX_ACCENT_SATURATION = 0.6f

/** 亮度锚定区间（深色 / 浅色）。 */
private val DARK_ACCENT_LUMA = 0.4f to 0.6f
private val LIGHT_ACCENT_LUMA = 0.4f to 0.55f

/** 低于此饱和度视为灰度图 / 提取失败，调用方回落预设色。 */
private const val GRAYSCALE_SATURATION = 0.08f

/**
 * 把任意来源的主题色压进可用范围：饱和度 ≤0.6、亮度锚定到主题模式对应的区间
 * （深色 0.4–0.6 / 浅色 0.4–0.55）。灰度色返回 null，由调用方回落预设色。
 */
fun processAccentColor(argb: Int, isDark: Boolean): Color? {
    val hsv = FloatArray(3)
    rgbToHsv((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, hsv)
    if (hsv[1] < GRAYSCALE_SATURATION) return null
    hsv[1] = hsv[1].coerceAtMost(MAX_ACCENT_SATURATION)
    val (lo, hi) = if (isDark) DARK_ACCENT_LUMA else LIGHT_ACCENT_LUMA
    hsv[2] = hsv[2].coerceIn(lo, hi)
    return Color(hsvToArgb(hsv[0], hsv[1], hsv[2]))
}

/**
 * 与主题色搭配的前景色（onPrimary）。黑白取对比度更高者。
 *
 * 亮度锚定区间保证二者必有一个达标：相对亮度 ≤0.183 时白字 ≥4.5:1，
 * >0.175 时黑字 ≥4.5:1，而 0.175 < 0.183 —— 两个区间无缝覆盖，所以不需要
 * 再回头去调主题色本身。
 */
fun onAccentColor(accent: Color): Color {
    val argb = accent.toArgb()
    val white = contrastRatio(0xFFFFFFFF.toInt(), argb)
    val black = contrastRatio(0xFF000000.toInt(), argb)
    return if (white >= black) Color.White else Color.Black
}
