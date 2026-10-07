/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.4 · E：竖屏播放托盘第一行（实时歌词）的**纯逻辑**。无 Android 依赖，JVM 可单测。
 */

package com.takahashirinta.ncrust.ui.player

import com.takahashirinta.ncrust.lyric.LrcLine

/**
 * 「播放位置 → 托盘第一行该显示哪一句」。
 *
 * ## 为什么单独抽一个文件
 *
 * 三个理由，按重要性：
 *
 * 1. **行级节流必须是可证明的**。托盘的更新频率是「跨行才更新一次」，而这件事
 *    发生在一条 flow 管道里（见 `PlayerCard.TrayLyricLine`）。管道本身不可单测，
 *    但它的**判据**（[lineAt]）可以 —— 于是「同 2Hz 采样下不换行就不产出新值」
 *    由 [TrayLyricTest] 钉住，而不是靠读代码相信。
 * 2. **二分查找必须与歌词面板同源**。它直接复用 [currentLineIndex]
 *    （`NcrustLyricsPanel.kt`，v1.5.2 起就是面板与逐字扫过窗口共用的那一份）。
 *    托盘若自己写一份线性扫描，「面板高亮的行」与「托盘显示的行」迟早错开一行。
 * 3. **降级规则要能被断言**：没有歌词 / 还没到第一行 / 当前行是空白 →
 *    一律返回 `null`，由调用方决定显示什么。`null` 与空串是两件事，
 *    不在这里混用（`AGENTS.md` 的「缺失 vs 空」规则）。
 *
 * ## 有意不做的事
 *
 * - **不逐字**。逐字高亮是 `SweepTrack` + `drawWithContent` 那一套，
 *   它只在**当前行**的一次绘制里跑。托盘是一个 56dp 的 `MetroText`，
 *   把它接进逐字路径等于让一整个外层组件跟着帧时钟重建 —— 与铁律 17
 *   （UI 动效不得影响播放性能）冲突，收益（一行小字逐字高亮）也看不见。
 * - **不拼译文 / 音译**。托盘一行只有 ~15dp 高，拼上去必然被省略号吃掉一半。
 *   译文与音译留给歌词面板（那里有独立的行）。
 */
object TrayLyric {

    /**
     * 当前应显示在托盘第一行的歌词文本。
     *
     * @param lines 主歌词轨（`PlayerViewModel.lyrics`）。空列表 ⇒ `null`。
     * @param timestamps [lines] 的时间戳数组，由 [timestampsOf] 构造；
     *   长度必须与 [lines] 相等，否则视为「不同源」返回 `null`
     *   （`combine` 的两个上游可能短暂错位，宁可这一帧不显示，也不要显示错行的歌词）。
     * @param positionMs 当前播放位置。
     * @return 非空且非纯空白的行文本（已 `trim`）；没有可显示的行时 `null`。
     *
     * 边界：
     * - 位置早于第一行 ⇒ `null`（前奏阶段不显示任何歌词，与面板一致）；
     * - 位置晚于最后一行 ⇒ 停在最后一行（与 [currentLineIndex] 一致）；
     * - 当前行是纯空白 ⇒ `null`（LRC 里确实存在这种占位行）。
     */
    fun lineAt(lines: List<LrcLine>, timestamps: LongArray, positionMs: Long): String? {
        if (lines.isEmpty() || timestamps.size != lines.size) return null
        if (positionMs < 0L) return null
        val index = currentLineIndex(positionMs, timestamps)
        if (index !in lines.indices) return null
        return lines[index].text.trim().takeIf { it.isNotEmpty() }
    }

    /**
     * [LrcLine] 列表 → 二分查找要的时间戳数组。
     *
     * 抽出来的唯一理由：它必须在**歌词列表变化时才算一次**（列表每首歌才换一次），
     * 不能放进 2Hz 的采样路径里（那样每秒重建两次 LongArray，纯浪费）。
     * 调用方用 `lyricsFlow.map { it to timestampsOf(it) }` 把它挪到上游。
     */
    fun timestampsOf(lines: List<LrcLine>): LongArray = LongArray(lines.size) { lines[it].timeMs }
}
