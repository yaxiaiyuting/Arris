/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCheckpointTest {
    @Test
    fun `a resumed item waits five seconds before writing another checkpoint`() {
        val checkpoint = PlaybackCheckpoint()
        checkpoint.reset(60_000L)
        assertFalse(checkpoint.record(64_999L))
        assertTrue(checkpoint.record(65_000L))
        assertFalse(checkpoint.record(65_500L))
    }

    @Test
    fun `pause or seek can record immediately and a new item resets the interval`() {
        val checkpoint = PlaybackCheckpoint()
        checkpoint.reset(30_000L)
        assertTrue(checkpoint.record(30_500L, explicit = true))
        assertTrue(checkpoint.record(0L, explicit = true))
        checkpoint.reset(1_500L)
        assertFalse(checkpoint.record(6_499L))
        assertTrue(checkpoint.record(6_500L))
    }
}
