/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.ui.player

import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.lyric.LyricClock

internal data class KaraokeCue(val current: Int, val next: Int, val count: Int? = null)

internal class KaraokeTimeline(
    lines: List<LrcLine>,
    translations: Map<Long, LrcLine>,
    private val times: LongArray = LyricClock.lineStarts(lines, useWordTiming = true),
) {
    private val breaks = LongArray(lines.size) { -1L }
    private val lineCues = Array(lines.size + 1) { index ->
        KaraokeCue(index - 1, if (index < lines.size) index else -1)
    }
    private val breakCues = arrayOfNulls<Array<KaraokeCue>>(lines.size)

    // A plain LRC start cannot tell us when a phrase ends. Only show an instrumental cue
    // when a timestamp establishes the end of the previous phrase (or before the first).
    val boundaries: List<Long> = buildList {
        if (times.isNotEmpty() && times[0] >= INTRO_MS) {
            breakCues[0] = countdownCues(next = 0)
            addCountdown(times[0])
        }
        for (i in 0 until lines.lastIndex) {
            val end = lines[i].knownEnd(times[i]) ?: continue
            val translatedEnd = translations[lines[i].timeMs]?.knownEnd(times[i]) ?: end
            val latest = maxOf(end, translatedEnd)
            if (times[i + 1] - latest < BREAK_MS) continue
            breaks[i] = latest
            breakCues[i + 1] = countdownCues(next = i + 1)
            add(latest)
            addCountdown(times[i + 1])
        }
    }

    fun cueAt(positionMs: Long): KaraokeCue {
        val current = currentLineIndex(positionMs, times)
        val next = current + 1
        val cues = breakCues.getOrNull(next) ?: return lineCues[next]
        if (current >= 0 && positionMs < breaks[current]) return lineCues[next]
        val remaining = times[next] - positionMs
        val count = if (remaining in 1L..3_000L) ((remaining + 999L) / 1_000L).toInt() else 0
        return cues[count]
    }

    private fun countdownCues(next: Int) = Array(4) { count ->
        KaraokeCue(-1, next, count.takeIf { it > 0 })
    }

    private fun MutableList<Long>.addCountdown(next: Long) {
        for (second in 3 downTo 1) add(next - second * 1000L)
    }

    private fun LrcLine.knownEnd(startMs: Long): Long? =
        maxOf(endMs ?: startMs, words.maxOfOrNull { it.startMs + it.durationMs } ?: startMs)
            .takeIf { it > startMs }

    private companion object {
        const val INTRO_MS = 3000L
        const val BREAK_MS = 5000L
    }
}
