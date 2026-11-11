/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.ui.player

import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.lyric.SweepTrack
import java.util.Arrays

/** 唱词窗口内跟随帧时钟，其余时间只在歌词或提示的边界唤醒。 */
internal class LyricWakeSchedule(
    lines: List<LrcLine>,
    private val lineStarts: LongArray,
    translations: Map<Long, LrcLine>,
    useWordTiming: Boolean,
    extraBoundaries: List<Long>,
) {
    private val originalWindows = if (useWordTiming) SweepWindows(lines.size) else null
    private val translationWindows =
        if (useWordTiming && translations.isNotEmpty()) SweepWindows(lines.size) else null

    val boundaries: LongArray = buildBoundaries(lines, translations, extraBoundaries)

    fun sweepingAt(positionMs: Long): Boolean {
        val originals = originalWindows ?: return false
        val index = currentLineIndex(positionMs, lineStarts)
        if (index < 0) return false
        return originals.contains(index, positionMs) || translationWindows?.contains(index, positionMs) == true
    }

    private fun buildBoundaries(
        lines: List<LrcLine>,
        translations: Map<Long, LrcLine>,
        extraBoundaries: List<Long>,
    ): LongArray {
        val originals = originalWindows
        val translated = translationWindows
        var capacity = lineStarts.size + extraBoundaries.size
        if (originals != null) {
            capacity += lines.sumOf { it.words.size + 1 }
            capacity += translations.values.sumOf { it.words.size }
            if (translated != null) capacity += lines.size
        }

        val times = LongArray(capacity)
        lineStarts.copyInto(times)
        var count = lineStarts.size
        for (time in extraBoundaries) times[count++] = time

        if (originals != null) {
            lines.forEachIndexed { index, line ->
                for (word in line.words) times[count++] = word.startMs
                val end = originals.record(index, line)
                if (end != NO_WINDOW) times[count++] = end
                if (translated != null) {
                    val translation = translations[line.timeMs] ?: return@forEachIndexed
                    val translatedEnd = translated.record(index, translation)
                    if (translatedEnd != NO_WINDOW) times[count++] = translatedEnd
                }
            }
            for (line in translations.values) {
                for (word in line.words) times[count++] = word.startMs
            }
        }

        Arrays.sort(times, 0, count)
        var unique = 0
        for (index in 0 until count) {
            val time = times[index]
            if (unique == 0 || times[unique - 1] != time) times[unique++] = time
        }
        return if (unique == times.size) times else times.copyOf(unique)
    }

    private class SweepWindows(size: Int) {
        private val starts = LongArray(size) { NO_WINDOW }
        private val ends = LongArray(size)

        fun record(index: Int, line: LrcLine): Long {
            var first = Long.MAX_VALUE
            var last = Long.MIN_VALUE
            for (word in line.words) {
                first = minOf(first, word.startMs)
                last = maxOf(last, word.startMs + word.durationMs)
            }
            if (first == Long.MAX_VALUE || last < first) return NO_WINDOW

            // 行尾最多保留扫过轨道用于收束的时长。
            val end = minOf(maxOf(line.endMs ?: last, last), last + SweepTrack.TERMINAL_MAX_MS)
            starts[index] = first
            ends[index] = end
            return end
        }

        fun contains(index: Int, positionMs: Long): Boolean {
            val start = starts[index]
            return start != NO_WINDOW && positionMs >= start && positionMs < ends[index]
        }
    }

    private companion object {
        const val NO_WINDOW = Long.MIN_VALUE
    }
}
