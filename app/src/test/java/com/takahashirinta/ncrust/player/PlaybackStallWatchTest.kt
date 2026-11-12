/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStallWatchTest {
    @Test
    fun `a slow growing buffer does not count as a stall`() {
        val watch = PlaybackStallWatch(12_000L)
        for (second in 0L..30L) {
            assertFalse(watch.observe(second * 1_000L, 5_000L, 5_000L + second / 4 * 100))
        }
    }

    @Test
    fun `stopped buffering reports once and fresh progress starts a new window`() {
        val watch = PlaybackStallWatch(12_000L)
        assertFalse(watch.observe(0L, 5_000L, 5_000L))
        assertFalse(watch.observe(11_999L, 5_000L, 5_000L))
        assertTrue(watch.observe(12_000L, 5_000L, 5_000L))
        assertFalse(watch.observe(24_000L, 5_000L, 5_000L))
        assertFalse(watch.observe(25_000L, 5_000L, 5_200L))
        assertTrue(watch.observe(37_000L, 5_000L, 5_200L))
    }

    @Test
    fun `a frozen playing position reports even without buffer samples`() {
        val watch = PlaybackStallWatch(12_000L)
        assertFalse(watch.observe(0L, 5_000L, null))
        assertFalse(watch.observe(10_000L, 15_000L, null))
        assertFalse(watch.observe(21_999L, 15_000L, null))
        assertTrue(watch.observe(22_000L, 15_000L, null))
    }

    @Test
    fun `pause or replacement discards the old timer`() {
        val watch = PlaybackStallWatch(12_000L)
        assertFalse(watch.observe(0L, 5_000L, 5_000L))
        watch.reset()
        assertFalse(watch.observe(120_000L, 0L, 0L))
        assertTrue(watch.observe(132_000L, 0L, 0L))
    }
}
