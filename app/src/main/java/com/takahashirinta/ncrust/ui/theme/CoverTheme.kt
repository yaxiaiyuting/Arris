/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明：
 *   - v2.5.0 · A（色调）：把封面取色的结果接进主题 —— 播放页背景与强调色跟随封面。
 */

package com.takahashirinta.ncrust.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.takahashirinta.ncrust.ui.theme.color.CoverPalette
import com.takahashirinta.ncrust.ui.theme.color.CoverPaletteExtractor

/**
 * v2.5.0 · A（色调）：**同一张封面的深色与浅色两套配色**。
 *
 * ## 为什么一次算出两套（而不是按当前模式算一套）
 *
 * 主题模式是可以随时切的（设置页 / 跟随系统）。若只按"算的那一刻"的模式算一套，
 * 用户切到浅色之后，封面配色会**停留在深色变体上**直到换歌 —— 表现是
 * 「切了浅色主题，背景还是黑的」，而且要等下一首歌才恢复。
 *
 * 两套一起算的代价很小：量化（最贵的一步）**只跑一次**，两套共用同一个种子色，
 * 差别只是在 [CoverPalette.fromSeed] 里各解一遍 HCT 色调映射。
 * 换来的是「切模式立即正确」+ 主线程零计算。
 *
 * ## 为什么必须整体算好再交给 UI
 *
 * 铁律 3：非核心计算必须在后台线程。而 `NcrustTheme`（类名未改）是在**组合期**求值的 ——
 * 若把「种子色 → 配色」这一步放在 `remember(seed, isDark)` 里做，
 * 那就是在主线程的组合期解 HCT（十余次 CAM16 求解）。放在后台一次算完，
 * UI 侧只剩一次按 [forMode] 取字段。
 */
@Immutable
data class CoverThemeColors(
    /** 深色变体。 */
    val dark: CoverPalette,
    /** 浅色变体。 */
    val light: CoverPalette,
) {
    /** 按当前主题模式取对应的一套。 */
    fun forMode(isDark: Boolean): CoverPalette = if (isDark) dark else light
}

/**
 * v2.5.0 · A（色调）：封面 → [CoverThemeColors] 的**唯一入口**。
 *
 * 与 `CoverPaletteExtractor.extract` 的关系：后者是「一次一个模式」的底层入口，
 * 本对象是 App 侧真正用的那个 —— 它把「降采样 → 量化 → 打分 → 取种子 → 两套配色」
 * 串成一次调用，供 `PlaybackService` 在 `Dispatchers.Default` 上调用。
 *
 * ## 降级（铁律 3 的另一半）
 *
 * **任何一步失败都返回 null，永不抛异常。** 调用链上每一层都做了兜底：
 * [CoverPaletteExtractor.downsample] 对非法参数返回空数组、
 * [CoverPaletteExtractor.extract] 用 `runCatching` 包住全部内部异常、
 * 这里再包一层 `runCatching`。返回 null 时调用方回落到预设主题 ——
 * 用户**察觉不到**取色失败，这正是"取色是增值功能、不是播放链路一环"的含义。
 *
 * ## 采样边长 50
 *
 * 任务书 §3.2 要求 50×50。这里让它成为**默认值而不是硬编码**：
 * `dstMax` 是可传参数，方便将来按设备性能调整（低端机可以降到 32）。
 * 注意它**只影响新增的这条 HCT 通路** —— 既有的 `androidx.palette` 通路
 * （通知栏着色）仍然用它的 112×112，两条通路不共享采样，
 * 也不以"给出同一个颜色"为目标（见 `probe-theme.md` §P5）。
 */
object CoverThemeExtractor {

    /** 任务书 §3.2 的采样边长。 */
    const val SAMPLE_MAX_PX: Int = 50

    /**
     * 从一张位图的像素里算出两套配色；**必须在后台线程调用**。
     *
     * @param pixels 源位图的 ARGB 像素（`Bitmap.getPixels` 的结果）
     * @param srcW 源宽
     * @param srcH 源高
     * @param dstMax 降采样后的最长边，默认 [SAMPLE_MAX_PX]
     * @return 两套配色；灰度封面 / 空输入 / 任何异常 ⇒ **null**（调用方回落预设主题）
     */
    fun extractBoth(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        dstMax: Int = SAMPLE_MAX_PX,
    ): CoverThemeColors? = runCatching {
        val small = CoverPaletteExtractor.downsample(pixels, srcW, srcH, dstMax)
        if (small.isEmpty()) return@runCatching null
        // 先按深色解一次拿到配色；`extract` 内部的量化与打分是**唯一且最贵**的一步，
        // 两套变体共用它的种子色（`CoverPalette.seedArgb`），所以量化只跑一次。
        val dark = CoverPaletteExtractor.extract(small, isDark = true) ?: return@runCatching null
        val light = CoverPalette.fromSeed(dark.seedArgb, isDark = false)
        CoverThemeColors(dark = dark, light = light)
    }.getOrNull()

    /**
     * 把 [CoverPalette] 折算成一份 [NcrustColors]。
     *
     * ## 映射口径（为什么是这些字段）
     *
     * 主题里有两类颜色，来源不同：
     *
     * | 字段 | 来源 | 理由 |
     * |---|---|---|
     * | `primary` / `onPrimary` | 直接取配色的对应角色 | 「强调色跟随封面」就是这条 |
     * | `background` / `surface` / `surfaceContainer*` | 取配色的**中性**角色 | 「播放页背景跟随封面」；中性色饱和度低，大面积铺开不会刺眼 |
     * | `onBackground` / `onSurface` / `onSurfaceVariant` | 取配色的对应角色 | 它们由 tone 差保证对比度（tone 差 ≥ 50 ⇒ ≥ 4.5:1） |
     * | `outline` / `outlineVariant` | 直接取配色的对应角色 | 描边要跟着背景走，否则封面边框会和背景脱节 |
     *
     * ## 刻意**不**覆盖的字段（安全阀）
     *
     * `surfaceContainerHigh` / `surfaceContainerHighest` 这两个"浮层"档位**保留主题原值**：
     * 它们是菜单/下拉/输入框的底色，上面会叠**任意**文字与图标。
     * 封面配色给的中性色在极端封面（比如深色照片）上可能与前景色对比不足，
     * 而这两个档位的可读性影响面比"背景好不好看"大得多。
     * 也就是说：**背景跟随封面，浮层的层级阶梯仍然由经过 WCAG 校验的默认色板负责。**
     */
    fun toNcrustColors(palette: CoverPalette, base: NcrustColors): NcrustColors = base.copy(
        primary = Color(palette.primary),
        onPrimary = Color(palette.onPrimary),
        background = Color(palette.neutral),
        surface = Color(palette.neutralVariant),
        surfaceVariant = Color(palette.neutralVariant),
        onBackground = Color(palette.onSurface),
        onSurface = Color(palette.onSurface),
        onSurfaceVariant = Color(palette.onSurfaceVariant),
        outline = Color(palette.outline),
        outlineVariant = Color(palette.outlineVariant),
    )
}
