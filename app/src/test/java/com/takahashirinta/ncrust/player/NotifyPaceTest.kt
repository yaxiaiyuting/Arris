/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyPaceTest {

    @Test
    fun `state changes always post immediately`() {
        val pace = NotifyPace(contentMinIntervalMs = 5_000L)
        for (i in 0..20) {
            assertEquals(
                NotifyPace.Decision.Post,
                pace.decide(nowMs = i * 300L, reason = NotifyReason.STATE),
            )
        }
    }

    @Test
    fun `content updates are paced to the interval and deferred not dropped`() {
        val pace = NotifyPace(contentMinIntervalMs = 5_000L)
        assertEquals(NotifyPace.Decision.Post, pace.decide(0L, NotifyReason.CONTENT))
        assertEquals(NotifyPace.Decision.Defer(2_500L), pace.decide(2_500L, NotifyReason.CONTENT))
        assertEquals(NotifyPace.Decision.Defer(100L), pace.decide(4_900L, NotifyReason.CONTENT))
        assertEquals(NotifyPace.Decision.Post, pace.decide(5_000L, NotifyReason.CONTENT))
        assertEquals(NotifyPace.Decision.Defer(4_000L), pace.decide(6_000L, NotifyReason.CONTENT))
    }

    @Test
    fun `a state post clears the content budget so the new track shows its first line`() {
        val pace = NotifyPace(contentMinIntervalMs = 5_000L)
        assertEquals(NotifyPace.Decision.Post, pace.decide(0L, NotifyReason.CONTENT))
        assertEquals(NotifyPace.Decision.Post, pace.decide(1_000L, NotifyReason.STATE))
        // 切歌后立刻跨行：不能再被上一首的预算拖 4 秒。
        assertEquals(NotifyPace.Decision.Post, pace.decide(1_100L, NotifyReason.CONTENT))
        assertEquals(NotifyPace.Decision.Defer(4_000L), pace.decide(2_100L, NotifyReason.CONTENT))
    }

    @Test
    fun `clock going backwards is treated as allowed rather than stuck`() {
        val pace = NotifyPace(contentMinIntervalMs = 5_000L)
        assertEquals(NotifyPace.Decision.Post, pace.decide(10_000L, NotifyReason.CONTENT))
        assertEquals(NotifyPace.Decision.Post, pace.decide(1_000L, NotifyReason.CONTENT))
    }

    @Test
    fun `reset starts a fresh budget`() {
        val pace = NotifyPace(contentMinIntervalMs = 5_000L)
        assertEquals(NotifyPace.Decision.Post, pace.decide(0L, NotifyReason.CONTENT))
        pace.reset()
        assertEquals(NotifyPace.Decision.Post, pace.decide(100L, NotifyReason.CONTENT))
    }

    @Test
    fun `default interval matches the documented five seconds`() {
        assertTrue(NotifyPace.DEFAULT_CONTENT_INTERVAL_MS >= 5_000L)
    }
}
