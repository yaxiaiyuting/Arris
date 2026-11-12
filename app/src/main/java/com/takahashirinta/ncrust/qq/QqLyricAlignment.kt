/*
 * Copyright (c) 2026 yaxiaiyuting.
 * Arris fork contribution, licensed under GPLv3. See LICENSE.
 */

package com.takahashirinta.ncrust.qq

import com.takahashirinta.ncrust.lyric.LrcLine
import kotlin.math.abs

/** 译文和音译共用主轨时间轴。每个主轨位置保留时间差最小的一行。 */
internal class QqLyricAlignment(main: List<LrcLine>) {
    private val times = LongArray(main.size).also { times ->
        main.forEachIndexed { index, line -> times[index] = line.timeMs }
        times.sort()
    }

    fun align(sub: List<LrcLine>, toleranceMs: Long = 1500L): List<LrcLine> {
        if (times.isEmpty() || sub.isEmpty()) return emptyList()
        val matched = arrayOfNulls<LrcLine>(times.size)
        val deltas = LongArray(times.size) { Long.MAX_VALUE }
        for (line in sub) {
            val next = lowerBound(line.timeMs)
            var best = next - 1
            var delta = if (best >= 0) abs(times[best] - line.timeMs) else Long.MAX_VALUE
            if (next < times.size) {
                val nextDelta = abs(times[next] - line.timeMs)
                if (nextDelta < delta) {
                    best = next
                    delta = nextDelta
                }
            }
            // 等距时选较早的主轨位置，同一位置上的并列副轨保留先到的一行。
            if (best >= 0 && delta <= toleranceMs && delta < deltas[best]) {
                matched[best] = line
                deltas[best] = delta
            }
        }

        val out = ArrayList<LrcLine>(minOf(times.size, sub.size))
        var first = 0
        while (first < times.size) {
            val time = times[first]
            var last = first
            while (last < times.lastIndex && times[last + 1] == time) last++
            val firstMatch = matched[first]
            // 二分插入点只会选中同刻组的首尾。组内按时间差排序，并列时保持主轨顺序。
            val lastMatch = if (last > first) matched[last] else null
            if (lastMatch != null && deltas[last] < deltas[first]) {
                out.add(lastMatch.atTime(time))
                firstMatch?.let { out.add(it.atTime(time)) }
            } else {
                firstMatch?.let { out.add(it.atTime(time)) }
                lastMatch?.let { out.add(it.atTime(time)) }
            }
            first = last + 1
        }
        return out
    }

    private fun lowerBound(timeMs: Long): Int {
        var low = 0
        var high = times.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (times[mid] < timeMs) low = mid + 1 else high = mid
        }
        return low
    }

    private fun LrcLine.atTime(time: Long): LrcLine = if (timeMs == time) this else copy(timeMs = time)
}
