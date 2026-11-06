package com.takahashirinta.ncrust.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class LyricClockTest {
    @Test fun `network stall cannot advance lyrics by the offline interval`() {
        assertEquals(30750L, LyricClock.positionAt(30000, 1_000_000_000, 61_000_000_000))
    }
    @Test fun `fresh resumed anchor starts from the real audio position`() {
        assertEquals(30250L, LyricClock.positionAt(30000, 61_000_000_000, 61_250_000_000))
    }
    @Test fun `normal sample interval and backwards monotonic clock are bounded`() {
        assertEquals(30500L, LyricClock.positionAt(30000, 1_000_000_000, 1_500_000_000))
        assertEquals(30000L, LyricClock.positionAt(30000, 2_000_000_000, 1_000_000_000))
    }

    @Test fun `an audio sample that jumps past a pending boundary still advances the lyric`() {
        val boundaries = longArrayOf(1_000, 3_000, 7_000)
        assertEquals(1_000L, LyricClock.displayPosition(1_000, 2_999, boundaries, sweeping = false))
        assertEquals(3_180L, LyricClock.displayPosition(1_000, 3_180, boundaries, sweeping = false))
        assertEquals(8_000L, LyricClock.displayPosition(1_000, 8_000, boundaries, sweeping = false))
        assertEquals(7_000L, LyricClock.displayPosition(6_980, 7_000, boundaries, sweeping = false))
    }

    @Test fun `word timing advances late line stamps without reordering or shifting plain lyrics`() {
        fun line(time: Long, wordStart: Long) = LrcLine(time, "A", listOf(LrcWord(wordStart, 150, "A", 0, 1)))
        val lines = listOf(line(1_000, 1_060), line(2_000, 900), line(4_000, 2_900), line(7_000, 3_500))
        assertArrayEquals(longArrayOf(1_000, 2_000, 2_900, 7_000), LyricClock.lineStarts(lines, true))
        assertArrayEquals(longArrayOf(1_000, 2_000, 4_000, 7_000), LyricClock.lineStarts(lines, false))
    }

    @Test fun `a sweep advances without a boundary and a large backward correction is applied`() {
        val boundaries = longArrayOf(1_000, 5_000)
        assertEquals(1_700L, LyricClock.displayPosition(1_680, 1_700, boundaries, sweeping = true))
        assertEquals(1_700L, LyricClock.displayPosition(3_200, 1_700, boundaries, sweeping = false))
    }
}
