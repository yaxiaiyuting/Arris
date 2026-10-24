package com.takahashirinta.ncrust.lyric

import org.junit.Assert.assertEquals
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
}
