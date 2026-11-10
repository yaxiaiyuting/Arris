package com.takahashirinta.ncrust.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackVisualVolumeTest {
    @Test
    fun `route attenuation combines with player gain and remains readable at low volume`() {
        assertEquals(1f, PlaybackVisualVolume.visualGain(1f, 15, 15, 0f), 0f)
        assertEquals(0.5f, PlaybackVisualVolume.visualGain(0.25f, 15, 15, 0f), 0.001f)
        assertEquals(0.316f, PlaybackVisualVolume.visualGain(1f, 5, 15, -20f), 0.001f)
        assertEquals(0.5f, PlaybackVisualVolume.visualGain(1f, 3, 12, null), 0.001f)
    }

    @Test
    fun `zero volume or zero player gain leaves no waveform amplitude`() {
        assertEquals(0f, PlaybackVisualVolume.visualGain(1f, 0, 15, null), 0f)
        assertEquals(0f, PlaybackVisualVolume.visualGain(0f, 15, 15, 0f), 0f)
        assertEquals(0f, PlaybackVisualVolume.visualGain(1f, 1, 15, Float.NEGATIVE_INFINITY), 0f)
    }
}
