/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.ui.player

import com.takahashirinta.ncrust.lyric.LrcLine

internal data class KaraokeCue(val current: Int, val next: Int, val count: Int? = null)

internal class KaraokeTimeline(lines: List<LrcLine>, translations: Map<Long, LrcLine>) {
    private val times = LongArray(lines.size) { lines[it].timeMs }
    private val breaks = LongArray(lines.size) { -1L }

    // A plain LRC start cannot tell us when a phrase ends. Only show an instrumental cue
    // when a timestamp establishes the end of the previous phrase (or before the first).
    val boundaries: List<Long> = buildList {
        if (times.isNotEmpty() && times[0] >= INTRO_MS) addCountdown(times[0])
        for (i in 0 until lines.lastIndex) {
            val end = lines[i].knownEnd() ?: continue
            val translatedEnd = translations[times[i]]?.knownEnd() ?: end
            val latest = maxOf(end, translatedEnd)
            if (times[i + 1] - latest < BREAK_MS) continue
            breaks[i] = latest
            add(latest)
            addCountdown(times[i + 1])
        }
    }

    fun cueAt(positionMs: Long): KaraokeCue {
        val current = currentLineIndex(positionMs, times)
        val next = (current + 1).takeIf { it < times.size } ?: -1
        if (next == -1) return KaraokeCue(current, next)
        val inBreak = if (current < 0) times[next] >= INTRO_MS
            else breaks[current] in 0..positionMs
        if (!inBreak) return KaraokeCue(current, next)
        val remaining = times[next] - positionMs
        val count = ((remaining + 999) / 1000).toInt().takeIf { it in 1..3 }
        return KaraokeCue(-1, next, count)
    }

    private fun MutableList<Long>.addCountdown(next: Long) {
        for (second in 3 downTo 1) add(next - second * 1000L)
    }

    private fun LrcLine.knownEnd(): Long? =
        maxOf(endMs ?: timeMs, words.maxOfOrNull { it.startMs + it.durationMs } ?: timeMs)
            .takeIf { it > timeMs }

    private companion object {
        const val INTRO_MS = 3000L
        const val BREAK_MS = 5000L
    }
}
