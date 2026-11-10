package com.takahashirinta.ncrust.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackVisualVolumeTest {
    @Test
    fun `route attenuation maps on a perceptual curve instead of linear amplitude`() {
        // 满音量、满播放增益 → 1。
        assertEquals(1f, PlaybackVisualVolume.visualGain(1f, 15, 15, 0f), 0f)
        // 播放增益 0.25（= −12 dBFS）仍压到 0.5 —— 安静段落要有可见细节。
        assertEquals(0.5f, PlaybackVisualVolume.visualGain(0.25f, 15, 15, 0f), 0.001f)
        // 4.1.0 的关键修正：−15 dB（约 50% 音量）应当显示 0.75，而不是旧公式的 0.42。
        assertEquals(0.75f, PlaybackVisualVolume.visualGain(1f, 5, 15, -15f), 0.001f)
        // −30 dB → 0.5，−60 dB → 0。
        assertEquals(0.5f, PlaybackVisualVolume.visualGain(1f, 2, 15, -30f), 0.001f)
        assertEquals(0f, PlaybackVisualVolume.visualGain(1f, 1, 15, -60f), 0.001f)
        // 拿不到路由 dB 时退回 (index/max)^0.35：3/12 → 0.61。
        assertEquals(0.6156f, PlaybackVisualVolume.visualGain(1f, 3, 12, null), 0.002f)
    }

    @Test
    fun `zero volume or zero player gain leaves no waveform amplitude`() {
        assertEquals(0f, PlaybackVisualVolume.visualGain(1f, 0, 15, null), 0f)
        assertEquals(0f, PlaybackVisualVolume.visualGain(0f, 15, 15, 0f), 0f)
        assertEquals(0f, PlaybackVisualVolume.visualGain(1f, 1, 15, Float.NEGATIVE_INFINITY), 0f)
    }
}
