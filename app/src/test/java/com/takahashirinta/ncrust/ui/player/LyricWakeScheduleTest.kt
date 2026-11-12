/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.ui.player

import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.lyric.LrcWord
import com.takahashirinta.ncrust.lyric.LyricClock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricWakeScheduleTest {
    @Test
    fun `original and translated sweeps are independent within the active line`() {
        val lines = listOf(
            LrcLine(1000, "A", listOf(LrcWord(900, 200, "A", 0, 1)), endMs = 1200),
            LrcLine(2500, "B", listOf(LrcWord(2600, 100, "B", 0, 1))),
        )
        val translation = LrcLine(1000, "甲", listOf(LrcWord(1500, 800, "甲", 0, 1)), endMs = 4000)
        val schedule = LyricWakeSchedule(
            lines, LyricClock.lineStarts(lines, useWordTiming = true), mapOf(1000L to translation),
            useWordTiming = true, extraBoundaries = listOf(1800L, 900L),
        )

        assertArrayEquals(longArrayOf(900, 1200, 1500, 1800, 2500, 2600, 2700, 3000), schedule.boundaries)
        assertFalse(schedule.sweepingAt(899))
        assertTrue(schedule.sweepingAt(900))
        assertFalse(schedule.sweepingAt(1200))
        assertTrue(schedule.sweepingAt(1500))
        assertFalse(schedule.sweepingAt(2500))
        assertTrue(schedule.sweepingAt(2600))
        assertFalse(schedule.sweepingAt(2700))
    }

    @Test
    fun `animation off keeps line and countdown boundaries only`() {
        val line = LrcLine(1000, "A", listOf(LrcWord(900, 200, "A", 0, 1)))
        val schedule = LyricWakeSchedule(
            listOf(line), longArrayOf(1000), mapOf(1000L to line),
            useWordTiming = false, extraBoundaries = listOf(0L, 1000L),
        )

        assertArrayEquals(longArrayOf(0, 1000), schedule.boundaries)
        assertFalse(schedule.sweepingAt(1000))
    }
}
