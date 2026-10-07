/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.share

/**
 * 「选哪几句歌词」—— 复制 / 分享 / 出图共用的纯逻辑。
 *
 * 抽出来的理由：这是本功能里唯一**有判断**的部分（其余是排版与 IO）。放在纯函数里，
 * 「歌首 / 歌尾 / 行数不足 / 空歌词」四种边界就能在 JVM 上逐个钉死，而不是靠在模拟器上
 * 拖到某一秒去肉眼看。
 *
 * ## 与歌词面板的「当前行」必须是同一份判定
 *
 * [indexAt] 的语义与 `NcrustLyricsPanel` 里那个 `internal fun currentLineIndex` **逐条相同**：
 * 位置早于第一行时返回 **-1**（面板此时不高亮任何一行）。本包不直接复用它，是因为
 * 那个函数在 `ui/player` 包里、且并行改动中的歌词面板正在变；这里用 12 行重写一遍二分，
 * 换来的是「分享选段」不受面板重构影响。两处语义一旦分叉，
 * 症状是「屏幕上高亮的是第 5 句、分享出去的是第 4 句」—— 单测 `LyricShareSelectionTest`
 * 专门钉了「与面板同源」的四个位置。
 */
object LyricShareSelection {

    /** 默认上下文半径：当前行前后各 3 行 ⇒ 最多 7 行。 */
    const val DEFAULT_RADIUS = 3

    /**
     * 出图时的行数上限。
     *
     * 海报的高度是固定的 1920px，字号再小也得让人看得清。20 行是「整首歌词」在 1080×1920 上
     * 还能保持 ~40px 字号的临界值（见 [LyricPosterLayout.TEXT_SIZES] 的最后一档）。
     * 超过就退化成「以当前行为中心取 20 行」—— 宁可少印几句，也不印一张糊成一片的图。
     */
    const val MAX_POSTER_LINES = 20

    /**
     * 位置(ms) → 当前行索引。语义与面板的 `currentLineIndex` 一致：
     * - 空表 ⇒ `-1`；
     * - 早于第一行 ⇒ `-1`（前奏，面板不高亮任何一行）；
     * - 晚于最后一行 ⇒ 最后一行（唱完之后高亮停在末句）。
     */
    fun indexAt(positionMs: Long, lines: List<LyricShareLine>): Int {
        if (lines.isEmpty()) return -1
        if (positionMs < lines[0].timeMs) return -1
        var lo = 0
        var hi = lines.size - 1
        if (positionMs >= lines[hi].timeMs) return hi
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lines[mid].timeMs <= positionMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * 以 [center] 为中心、半径 [radius] 的窗口，**贴着歌首 / 歌尾时整体平移而不是缩短**。
     *
     * 这是本文件里唯一需要解释的取舍：
     * - **缩短**（`max(0, center-radius) .. min(total-1, center+radius)`）在歌首只会给出 4 行，
     *   用户点「当前唱段」却拿到一张半空的图，看起来像 bug；
     * - **平移**（本实现）在歌首给出 `0..6`，行数恒定、信息量一致。
     *
     * 代价：当前行不再是窗口正中（歌首时它在第 1 行）。这是有意的 —— 海报上不该出现
     * 「上面没有内容、下面留一大片空」。
     *
     * @return 升序闭区间；`total == 0` 时返回空区间（`0..-1`）。
     */
    fun windowIndices(total: Int, center: Int, radius: Int): IntRange =
        windowOfSize(total, center, 2 * radius.coerceAtLeast(0) + 1)

    /**
     * 以 [center] 为中心、**恰好 [size] 行**（受表长限制）的窗口，规则与 [windowIndices] 同一套：
     * 贴边时整体平移、不缩短。
     *
     * 单独开这个函数是因为出图的「整首放不下」路径要的是**行数**而不是半径
     * （`半径 = (行数-1)/2` 在偶数行数上会少给一行）。
     */
    fun windowOfSize(total: Int, center: Int, size: Int): IntRange {
        if (total <= 0) return IntRange.EMPTY
        val s = size.coerceIn(1, total)
        val c = center.coerceIn(0, total - 1)
        var start = c - (s - 1) / 2
        if (start < 0) start = 0
        if (start + s > total) start = total - s
        return start until (start + s)
    }

    /**
     * 文本分享（复制 / `ACTION_SEND`）要写进去的那几行。
     *
     * 整首就是整首 —— **不截断**。文本没有排版高度约束，截断只会让用户拿到半首歌
     * 却不知道少了什么（这也是它和 [linesForPoster] 唯一的区别）。
     */
    fun linesForText(
        lines: List<LyricShareLine>,
        positionMs: Long,
        scope: LyricShareScope,
        radius: Int = DEFAULT_RADIUS,
    ): List<LyricShareLine> {
        if (lines.isEmpty()) return emptyList()
        if (scope == LyricShareScope.WHOLE_SONG) return lines
        val center = indexAt(positionMs, lines).coerceAtLeast(0)
        val range = windowIndices(lines.size, center, radius)
        if (range.isEmpty()) return emptyList()
        return lines.subList(range.first, range.last + 1).toList()
    }

    /**
     * 出图要画的那几行。与 [linesForText] 的两点区别：
     *
     * 1. 整首歌词超过 [maxLines] 时退化成「以当前行为中心取 [maxLines] 行」；
     * 2. 返回值**最多** [maxLines] 行。
     *
     * 另外把「当前行在返回列表里的下标」一起交出去（海报要把当前句画成主色），
     * 这样渲染侧不需要再算一次时间 → 行号（算两次就是第二个真相）。
     */
    fun selectionForPoster(
        lines: List<LyricShareLine>,
        positionMs: Long,
        scope: LyricShareScope,
        radius: Int = DEFAULT_RADIUS,
        maxLines: Int = MAX_POSTER_LINES,
    ): PosterSelection {
        if (lines.isEmpty()) return PosterSelection(emptyList(), -1, -1)
        val cap = maxLines.coerceAtLeast(1)
        val center = indexAt(positionMs, lines).coerceAtLeast(0)
        val range = if (scope == LyricShareScope.WHOLE_SONG && lines.size <= cap) {
            0 until lines.size
        } else if (scope == LyricShareScope.WHOLE_SONG) {
            // 整首放不下 ⇒ 尽量多给上下文：直接按**行数上限**开窗，而不是退回默认的 3 行半径。
            windowOfSize(lines.size, center, cap)
        } else {
            windowIndices(lines.size, center, radius)
        }
        if (range.isEmpty()) return PosterSelection(emptyList(), -1, -1)
        val slice = lines.subList(range.first, range.last + 1).toList()
        return PosterSelection(
            lines = slice,
            currentIndex = if (center in range) center - range.first else -1,
            centerIndex = center,
        )
    }

    /**
     * 出图选段的结果。
     *
     * @param lines 真正要画的那些行（升序，长度 ≤ `maxLines`）
     * @param currentIndex [lines] 里哪一行是「正在唱的那句」，`-1` = 没有（前奏 / 空歌词）
     * @param centerIndex [lines] 在**原表**里的中心下标（测试与日志用，渲染侧不读）
     */
    data class PosterSelection(
        val lines: List<LyricShareLine>,
        val currentIndex: Int,
        val centerIndex: Int,
    )
}
