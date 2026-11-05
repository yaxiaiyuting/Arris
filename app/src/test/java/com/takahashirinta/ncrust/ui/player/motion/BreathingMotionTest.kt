/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.ui.player.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreathingMotionTest {
    @Test fun sharedClockCapsDrawsAndBandsDriveIndependentParameters() {
        val motion = BreathingMotion()
        val redraws = (1..144).count { motion.advance(1000f / 144f, true, true, 1f, 0f, 0f) }
        assertTrue("redraws=$redraws", redraws in 24..30)
        assertTrue(motion.spread > 0.9f)
        assertEquals(0f, motion.shimmer)
        val oldSpread = motion.spread
        repeat(50) { motion.advance(20f, true, true, 0f, 1f, 1f) }
        assertTrue(motion.spread < oldSpread)
        assertTrue(motion.shimmer > 0.9f)
    }

    @Test fun lossOfPcmOrPausedPlaybackSettlesWithoutUnboundedRedraws() {
        for ((active, available) in listOf(false to true, true to false)) {
            val motion = BreathingMotion()
            repeat(40) { motion.advance(20f, true, true, 1f, 1f, 1f) }
            val phase = motion.phase
            repeat(200) { motion.advance(20f, active, available, 1f, 1f, 1f) }
            assertEquals(0f, motion.spread)
            assertEquals(0f, motion.shimmer)
            assertEquals(phase, motion.phase)
            assertFalse(motion.advance(100f, active, available, 1f, 1f, 1f))
        }
    }

    @Test fun repeatedSlowFramesFallBackWithoutOneOffHitchOrPauseTriggeringIt() {
        val motion = BreathingMotion()
        motion.advance(70f, true, true, 1f, 1f, 1f)
        repeat(10) { motion.advance(100f, false, true, 1f, 1f, 1f) }
        assertFalse(motion.fluidTooSlow)
        repeat(8) { motion.advance(56f, true, true, 1f, 1f, 1f) }
        assertTrue(motion.fluidTooSlow)
        motion.reset()
        assertFalse(motion.fluidTooSlow)
    }
}
