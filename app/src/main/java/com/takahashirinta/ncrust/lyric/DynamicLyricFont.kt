/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import kotlin.math.ceil

/**
 * v2.0.0 · T4：**动态字号**的纯逻辑（实验性功能，默认关）。
 *
 * ## 做什么
 *
 * 按「每句歌词折行数」给**这一句**一个离散的字号倍率：短句（估算只占 1 行且填充率低）放大，
 * 三行及以上的长句缩小，其余保持基准。目标不是"每句都一样长"，而是让每一句的视觉体量
 * 更接近 —— 一行小字和五行大字混在一起时，整屏节奏是碎的。
 *
 * ## 为什么是"估算"而不是"测量"
 *
 * 真实折行数只有排版之后才知道（[androidx.compose.ui.text.TextLayoutResult]）。
 * 用真值就要**两遍排版**：先按基准字号排一遍拿行数，再按终值排一遍 —— 面板里每行已经有两层
 * `BasicText`，翻倍到四遍在 S6（API 24 / 3GB）上是可感知的开销，而且首次滚到的行会有一次
 * 「基准字号 → 终值」的跳变。因此这里用**显示宽度估算**：O(字符数)、无排版、无首帧跳变、
 * 结果稳定（同一句话永远得到同一个倍率，与"谁是当前行"无关）。
 *
 * 代价是拉丁比例字体只能近似 —— 判据刻意**保守**（宁可少放大、不可放大后再折行），
 * 且估算错一档只是字号差一档，不会造成逐字高亮错位（几何来自渲染层的真实 layout）。
 *
 * ## 与 SweepTrack 的关系（红线复核）
 *
 * **一行都不碰。** `SweepTrack` 的几何只吃「行号 + 横向 x」（`SweepGeometry` 没有纵向 API），
 * 字号变化影响的是**折行结果**（哪些字符落在第几个视觉行），那本来就是渲染层每次 layout
 * 变化时重算的输入；行高/上下沿全部由渲染层直接问 `TextLayoutResult`。所以本功能只改
 * `fontSize`/`lineHeight` 两个输入，渐变、逐字高亮、跨行插值全部沿用既有路径。
 */
object DynamicLyricFont {

    /** 短句放大档。刻意只给 1.15：再大就会破坏"当前行本来就有的放大动画"的观感层次。 */
    const val SCALE_LARGE = 1.15f

    /** 基准（= 用户 A-/A+ 设定的字号，本功能是在它之上浮动，不替换）。 */
    const val SCALE_BASE = 1.0f

    /** 长句缩小档。 */
    const val SCALE_SMALL = 0.85f

    /**
     * 放大的填充率上限（估算宽度 / 单行容量）。
     *
     * 与 [SCALE_LARGE] 的乘积必须 ≤ 1（0.82 × 1.15 = 0.943）：这样"放大后仍然只占一行"
     * 在估算模型内是可证明的 —— 否则会出现 1 行 → 放大 → 变 2 行 → 缩小 → 变 1 行的振荡。
     */
    const val SINGLE_LINE_FILL_LIMIT = 0.82f

    /**
     * 原文绝对字号上限（sp）。A+ 的 1.5 倍档把基准推到 48sp，再乘 1.15 就是 55sp，
     * 横屏大屏模式右栏只有约 210dp 高，会直接撑爆面板 —— 所以倍率要再夹一次。
     */
    const val ABSOLUTE_MAX_ORIGINAL_SP = 48f

    /**
     * 估算文本的显示宽度（单位 em，即"多少个基准字宽"）。
     *
     * 全角/中日韩 = 1em；ASCII 字母数字取 0.6em（偏大，保守）、ASCII 标点 0.35em、
     * 空格 0.3em；其余（西里尔、希腊、泰文等）0.6em。代理对（CJK 扩展 B 区等）按 1em 计一次。
     */
    fun estimateWidthEm(text: String): Float {
        var width = 0f
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c) && i + 1 < text.length &&
                Character.isLowSurrogate(text[i + 1])
            ) {
                // 增补平面（CJK 扩展 B~F、emoji 等）一律按一个全角字算。
                width += 1f
                i += 2
                continue
            }
            width += when {
                c == ' ' || c == '\u3000' -> if (c == '\u3000') 1f else 0.3f
                c == '\t' -> 0.5f
                isFullWidth(c) -> 1f
                c.code in 0x30..0x39 -> 0.6f            // 0-9
                c.code in 0x41..0x5A -> 0.66f           // A-Z（大写更宽）
                c.code in 0x61..0x7A -> 0.55f           // a-z
                c.code in 0x21..0x7E -> 0.35f           // ASCII 标点
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> 0f  // 组合音标
                else -> 0.6f
            }
            i++
        }
        return width
    }

    /** 全角区间：CJK、假名、谚文音节、全角标点、兼容汉字、增补平面汉字。 */
    private fun isFullWidth(c: Char): Boolean = when (c.code) {
        in 0x1100..0x115F -> true     // 谚文字母
        in 0x2E80..0x303E -> true     // CJK 部首 / 假名标点
        in 0x3041..0x33FF -> true     // 假名 / 注音 / CJK 兼容
        in 0x3400..0x4DBF -> true     // CJK 扩展 A
        in 0x4E00..0x9FFF -> true     // CJK 基本区
        in 0xA000..0xA4CF -> true     // 彝文
        in 0xAC00..0xD7A3 -> true     // 谚文音节
        in 0xF900..0xFAFF -> true     // CJK 兼容汉字
        in 0xFE30..0xFE4F -> true     // CJK 兼容形式
        in 0xFF00..0xFF60 -> true     // 全角 ASCII
        in 0xFFE0..0xFFE6 -> true     // 全角符号
        else -> false
    }

    /**
     * 估算折行数。宽度/字号不可用时返回 1（= 不做任何判断，等价于"不缩放"）。
     */
    fun estimatedLines(text: String, availWidthPx: Float, fontSizePx: Float): Int {
        if (text.isBlank() || availWidthPx <= 0f || fontSizePx <= 0f) return 1
        val capacityEm = availWidthPx / fontSizePx
        if (capacityEm <= 0f) return 1
        return ceil(estimateWidthEm(text) / capacityEm).toInt().coerceAtLeast(1)
    }

    /**
     * 这句歌词的字号倍率（离散三档）。
     *
     * 判定顺序：估算 1 行且填充率 ≤ [SINGLE_LINE_FILL_LIMIT] → 放大；
     * 估算 ≥ 3 行 → 缩小；其余（含正好 2 行）→ 基准。
     */
    fun scaleFor(text: String, availWidthPx: Float, fontSizePx: Float): Float {
        if (text.isBlank() || availWidthPx <= 0f || fontSizePx <= 0f) return SCALE_BASE
        val capacityEm = availWidthPx / fontSizePx
        if (capacityEm <= 0f) return SCALE_BASE
        val fill = estimateWidthEm(text) / capacityEm
        val lines = ceil(fill).toInt().coerceAtLeast(1)
        return when {
            lines <= 1 && fill <= SINGLE_LINE_FILL_LIMIT -> SCALE_LARGE
            lines >= 3 -> SCALE_SMALL
            else -> SCALE_BASE
        }
    }

    /**
     * 夹取倍率，使 `基准字号 × 倍率` 不超过 [maxSp]。
     *
     * 用户把 A+ 调到 1.5 倍（原文 48sp）时，放大档会被整体压回 1.0 —— 这是有意的：
     * 用户已经手动放大过，不需要动态字号再叠一层。
     */
    fun clampScale(raw: Float, baseFontSizeSp: Float, maxSp: Float = ABSOLUTE_MAX_ORIGINAL_SP): Float {
        if (baseFontSizeSp <= 0f) return raw
        return minOf(raw, maxSp / baseFontSizeSp)
    }
}
