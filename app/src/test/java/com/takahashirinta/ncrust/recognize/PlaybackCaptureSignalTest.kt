package com.takahashirinta.ncrust.recognize

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCaptureSignalTest {
    private val sampleRate = 44_100

    @Test fun `digital silence from blocked playback is rejected`() {
        assertFalse(hasPlaybackSignal(ShortArray(sampleRate * 10), sampleRate))
    }

    @Test fun `a short startup click does not trigger an upload`() {
        val samples = ShortArray(sampleRate * 10)
        samples.fill(Short.MIN_VALUE, 0, 100)
        assertFalse(hasPlaybackSignal(samples, sampleRate))
    }

    @Test fun `quiet sustained playback can be recognized`() {
        val samples = ShortArray(sampleRate * 10)
        for (index in 0 until sampleRate / 2) {
            samples[sampleRate + index] = if (index % 20 < 10) 80 else -80
        }
        assertTrue(hasPlaybackSignal(samples, sampleRate))
    }
}
