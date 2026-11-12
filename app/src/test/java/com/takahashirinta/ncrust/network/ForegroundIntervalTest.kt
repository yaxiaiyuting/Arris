/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundIntervalTest {
    @Test
    fun `foreground time does not count toward idle cleanup`() {
        val interval = ForegroundInterval()
        assertNull(interval.foreground(1_000L))
        interval.background(90_000L)
        assertEquals(500L, interval.foreground(90_500L))
        assertEquals(250L, interval.sinceForegroundMs(90_750L))
        assertNull(interval.foreground(90_800L))
    }

    @Test
    fun `repeated background callbacks preserve the start of the interval`() {
        val interval = ForegroundInterval()
        interval.background(0L)
        interval.background(8_000L)
        assertEquals(11_000L, interval.foreground(11_000L))
    }
}
