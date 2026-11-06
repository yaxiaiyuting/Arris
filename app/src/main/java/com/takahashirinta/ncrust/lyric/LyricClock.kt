/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

/** 2Hz 位置采样间短暂外推；采样停止后最多走 750ms，不能把断网等待算成播放。 */
object LyricClock {
    private const val MAX_WORD_LEAD_MS = 2_000L
    private const val SNAP_BACK_MS = 1_000L

    /**
     * @param speed 播放倍速（默认 1f = 不缩放）。**3.7.31 补回**：v3.7.30 回移竖屏跟唱时
     *   把 `KaraokeTimelineTest` 一起搬了过来，但这个参数没跟着落地 —— 测试源码引用了
     *   `speed`，主源里没有它 ⇒ `:app:testDebugUnitTest` 在 HEAD 上就是红的（编译失败）。
     *
     *   这里按主线同名函数的**逐字等价实现**补回（750ms 是**墙钟**上限，倍速只缩放媒体时间）：
     *   `elapsedMs.coerceIn(0, 750) * speed`。默认值 1f 保证现有三处调用点行为**零变化**，
     *   也就没有引入任何新行为 —— 纯粹是把缺的那个可选参数补齐。
     */
    fun positionAt(positionMs: Long, sampledNanos: Long, nowNanos: Long, speed: Float = 1f): Long {
        val elapsedMs = ((nowNanos - sampledNanos) / 1_000_000L).coerceIn(0L, 750L)
        return positionMs.coerceAtLeast(0L) + (elapsedMs * speed).toLong()
    }

    /** Matched word timing may start before the source line stamp. Keep stamps for track joins. */
    fun lineStarts(lines: List<LrcLine>, useWordTiming: Boolean): LongArray {
        val starts = LongArray(lines.size)
        lines.forEachIndexed { index, line ->
            val wordStart = if (useWordTiming) {
                line.words.minOfOrNull { word ->
                    if (word.durationMs > 0 && word.startMs >= 0) word.startMs else Long.MAX_VALUE
                } ?: line.timeMs
            } else line.timeMs
            starts[index] = if (wordStart < line.timeMs && line.timeMs - wordStart <= MAX_WORD_LEAD_MS &&
                (index == 0 || wordStart > starts[index - 1])
            ) wordStart else line.timeMs
        }
        return starts
    }

    /** The first time strictly after [positionMs], for a sorted table. */
    fun nextBoundaryAfter(boundaries: LongArray, positionMs: Long): Long? {
        if (boundaries.isEmpty() || positionMs >= boundaries.last()) return null
        var low = 0
        var high = boundaries.lastIndex
        while (low < high) {
            val mid = (low + high) ushr 1
            if (boundaries[mid] <= positionMs) low = mid + 1 else high = mid
        }
        return boundaries[low]
    }

    fun displayPosition(previousMs: Long, clockMs: Long, boundaries: LongArray, sweeping: Boolean): Long {
        if (previousMs - clockMs > SNAP_BACK_MS) return clockMs
        if (clockMs <= previousMs) return previousMs
        if (sweeping) return clockMs
        // Check the first unshown boundary. A fresh sample or a late wake may
        // already be past it when we decide where to sleep next.
        val next = nextBoundaryAfter(boundaries, previousMs)
        return if (next != null && next <= clockMs) clockMs else previousMs
    }
}
