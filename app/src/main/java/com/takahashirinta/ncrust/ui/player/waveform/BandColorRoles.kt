/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

import com.takahashirinta.ncrust.ui.theme.color.Hct
import com.takahashirinta.ncrust.ui.theme.color.TonalPalette

/**
 * v3.2.2：**主导频段的三个颜色（HCT 三角色）**（纯逻辑，无 Compose / Android 依赖 ⇒ JVM 直测）。
 *
 * ## 为什么不是 `CoverPalette` 的 a1/a2/a3
 *
 * 仓库里已有一套 Material 的角色色（`ui/theme/color/CoverPalette`，`Scheme.java` 的经典映射）。
 * 探针 §2.4 把它当 baseline 量了一遍：**它的最小 CAM16-UCS ΔE 只有 5.1，36 个样本 0 个达到
 * 「理想（≥10）」**—— a2/a3 的彩度只有 16 / 24、色相又挨着，那是为「和谐」设计的，
 * 不是为「一眼分辨三个频段」设计的。频带切换在 2~3dp 的细带上，ΔE 5 读起来就是
 * 「颜色好像动了一下」，而不是「换频段了」。
 *
 * ## 本方案（探针在 16 个变体里选出）
 *
 * | 项 | 取值 | 依据 |
 * |---|---|---|
 * | 低 / 中 / 高 → 角色 | **中频 = 主题色本身**、低频 = secondary、高频 = tertiary | 探针 §2.3 实测频带占比 **低 0.33 / 中 0.66 / 高 0.00** —— 中频占 2/3 的时间。把主题色给中频，波形**大多数时候仍是主题色**，只有鼓点（低频）与镲片/齿音（高频）把它推开；反过来把主题色给低频，画面就会长期偏离主题 |
 * | secondary 色相 | 主题色 hue **+240°** | 三色两两相隔 120°（三色组），探针里与 −60° 并列第一但色相分离度最大 ⇒ 对没测到的封面色更稳 |
 * | tertiary 色相 | 主题色 hue **+120°** | 同上 |
 * | 两者彩度 | **48**（探针扫了 24 / 48） | 48 那一档的最小 ΔE 10.1，24 那一档 8.4 |
 * | tone | 与主题色相同，夹到 `[55,88]`（深）/ `[25,62]`（浅） | **只换色相与彩度、不换亮度** —— 「这一段更亮」这个既有读法（峰值、呼吸）不被伪造 |
 *
 * 实测结果（36 个真实种子 × 明暗两模式）：最小 ΔE **10.1**、31/36 同时满足对比度 ≥ 3.0。
 * 那 5 个不满足的是**主题色本身的对比度**（例：素白主题 + 浅色底 = 1.12，今天的单色波形
 * 同样看不见），不是本方案引入的 —— 详见探针文档的「遗留风险」一节。
 *
 * ## 调用纪律（**不要在帧路径里调用**）
 *
 * [paletteFor] 内部要解两次 HCT（CAM16 求解），量级是微秒 —— 它必须在**组合期**按
 * `主题色 + 明暗` 缓存（`remember(barColor, isDark)`），帧路径只做 `lerp` 与三元素加权。
 */
object BandColorRoles {

    /** 低频 → secondary。 */
    const val LOW = 0

    /** 中频 → primary（主题色本身）。 */
    const val MID = 1

    /** 高频 → tertiary。 */
    const val HIGH = 2

    /** secondary 相对主题色的色相偏移（度）。+240 ≡ −120，与 tertiary 的 +120 构成三色组。 */
    const val SECONDARY_HUE_SHIFT = 240.0

    /** tertiary 相对主题色的色相偏移（度）。 */
    const val TERTIARY_HUE_SHIFT = 120.0

    /** secondary / tertiary 的彩度（HCT chroma）。探针扫过 24 / 48，48 那一档最小 ΔE 高 1.7。 */
    const val BAND_CHROMA = 48.0

    /** 深色主题下 tone 的可读区间（下沿保证不糊进 OLED 黑底，上沿保证还有颜色）。 */
    const val DARK_TONE_MIN = 55.0
    const val DARK_TONE_MAX = 88.0

    /** 浅色主题下 tone 的可读区间。 */
    const val LIGHT_TONE_MIN = 25.0
    const val LIGHT_TONE_MAX = 62.0

    /**
     * 由主题色算出三个频段色，**按频段下标写入** [out]（`[0]` = 低、`[1]` = 中、`[2]` = 高）。
     *
     * @param accentArgb 主题色（`NcrustColors.primary` 的 ARGB）。
     * @param isDark 当前是深色主题（决定 tone 夹取区间）。
     * @param out 长度 ≥ 3 的复用数组（调用方 `remember` 一个，避免每次重组分配）。
     * @return 是否写入成功（[out] 太短时返回 false，调用方退回单色）。
     */
    fun paletteFor(accentArgb: Int, isDark: Boolean, out: IntArray): Boolean {
        if (out.size < 3) return false
        return try {
            val hct = Hct.fromInt(accentArgb)
            val hue = if (hct.hue.isFinite()) hct.hue else 0.0
            val tone = hct.tone.coerceIn(
                if (isDark) DARK_TONE_MIN else LIGHT_TONE_MIN,
                if (isDark) DARK_TONE_MAX else LIGHT_TONE_MAX,
            ).toInt()
            out[LOW] = TonalPalette.fromHueAndChroma(hue + SECONDARY_HUE_SHIFT, BAND_CHROMA).tone(tone)
            out[MID] = accentArgb
            out[HIGH] = TonalPalette.fromHueAndChroma(hue + TERTIARY_HUE_SHIFT, BAND_CHROMA).tone(tone)
            true
        } catch (t: Throwable) {
            // HCT 是一段纯算术，抛异常的唯一现实来源是畸形输入（例如平台上的极端 hue）。
            // 失败时的降级是**三色合一**（全部用主题色）——等同于 v3.2.1 的单色波形，
            // 而不是让整块画布消失（铁律 4：异常必须隔离且有确定的退路）。
            out[LOW] = accentArgb
            out[MID] = accentArgb
            out[HIGH] = accentArgb
            false
        }
    }

    /**
     * v3.2.2：**这一帧到底画哪个颜色**（纯函数，渲染侧的唯一决策点，JVM 直测）。
     *
     * @param reliable `WaveformStore.snapshotBand` 的返回值。**false 时必须退回 [fallback]**
     *   —— 那是"测不到频段"的如实表达：特征链路不可用（降级路径）或还没得出结论。
     *   不退回就会出现 v3.0.0 那个既有缺口：降级时 `mix = 0.0`，于是"测不到"被画成"全是低频"。
     * @param fallback 单色回退值（调用方传主题色 ARGB，即 v3.2.1 的波形颜色）。
     */
    fun resolve(
        palette: IntArray,
        weights: FloatArray,
        reliable: Boolean,
        fallback: Int,
    ): Int = if (!reliable || palette.size < 3 || weights.size < 3) {
        fallback
    } else {
        mix(palette, weights)
    }

    /**
     * 三个频段色按 [weights]（和 ≈ 1）加权混合成一个 ARGB。
     *
     * 这是「分段 + 短过渡」的落点：过渡期间权重是 [previous] 与 [dominant] 的两份，
     * 混合结果就是两者之间的中间色。**逐通道整数插值**，不经过 Compose 的 `lerp`
     * （那一层在渲染侧做，这里只给纯函数供单测）。
     */
    fun mix(palette: IntArray, weights: FloatArray): Int {
        if (palette.size < 3 || weights.size < 3) return if (palette.isNotEmpty()) palette[0] else 0
        var a = 0f
        var r = 0f
        var g = 0f
        var b = 0f
        for (i in 0..2) {
            val w = if (weights[i].isFinite()) weights[i].coerceAtLeast(0f) else 0f
            if (w <= 0f) continue
            val c = palette[i]
            a += w * ((c ushr 24) and 0xFF)
            r += w * ((c shr 16) and 0xFF)
            g += w * ((c shr 8) and 0xFF)
            b += w * (c and 0xFF)
        }
        fun ch(v: Float): Int = v.coerceIn(0f, 255f).toInt()
        return (ch(a) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
