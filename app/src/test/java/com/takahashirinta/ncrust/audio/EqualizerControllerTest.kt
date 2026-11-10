package com.takahashirinta.ncrust.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerControllerTest {
    @Test
    fun `the last slider reaches a device with fewer and differently spaced bands`() {
        val gains = IntArray(EqualizerPrefs.frequenciesHz.size)
        gains[gains.lastIndex] = 1_000

        assertEquals(0, EqualizerController.gainAtFrequency(gains, 8_000.0))
        assertTrue(EqualizerController.gainAtFrequency(gains, 14_000.0) in 700..999)
        assertEquals(1_000, EqualizerController.gainAtFrequency(gains, 16_000.0))
    }
}
