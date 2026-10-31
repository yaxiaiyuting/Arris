package com.takahashirinta.ncrust.recognize

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 识曲 · 指纹（v3.8.0）：确定性、静音、裁切重合率。 */
class FingerprintTest {

    private fun tone(freqs: List<Double>, seconds: Double = 3.0, amp: Double = 0.3): ShortArray {
        val n = (ShazamFingerprint.SAMPLE_RATE * seconds).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / ShazamFingerprint.SAMPLE_RATE
            val v = freqs.sumOf { f -> sin(2 * PI * f * t) } / freqs.size * amp
            (v * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    @Test
    fun `同一段音频指纹完全一致`() {
        val pcm = tone(listOf(440.0, 880.0, 1320.0))
        val a = ShazamFingerprint.compute(pcm)
        val b = ShazamFingerprint.compute(pcm.copyOf())
        assertTrue("应有峰", a.peaks.isNotEmpty())
        assertEquals(a.hashes.toList(), b.hashes.toList())
    }

    @Test
    fun `静音不产生指纹`() {
        val silence = ShortArray(ShazamFingerprint.SAMPLE_RATE * 2)
        val fp = ShazamFingerprint.compute(silence)
        assertEquals(0, fp.hashes.size)
        assertEquals(0.0, ShazamFingerprint.rms(silence), 1e-9)
    }

    @Test
    fun `同一音频裁掉开头后仍高度重合`() {
        val pcm = tone(listOf(440.0, 1760.0, 3000.0), seconds = 4.0)
        val full = ShazamFingerprint.compute(pcm)
        val shifted = ShazamFingerprint.compute(
            pcm.copyOfRange(ShazamFingerprint.SAMPLE_RATE, pcm.size)
        )
        val overlap = ShazamFingerprint.overlapRatio(full.hashes, shifted.hashes)
        assertTrue("重合率应 > 0.6，实际 $overlap", overlap > 0.6f)
    }

    @Test
    fun `不同音频重合率低`() {
        val a = ShazamFingerprint.compute(tone(listOf(300.0, 900.0)))
        val b = ShazamFingerprint.compute(tone(listOf(2500.0, 3300.0)))
        assertTrue(ShazamFingerprint.overlapRatio(a.hashes, b.hashes) < 0.3f)
    }

    @Test
    fun `输入太短返回空指纹而不是崩溃`() {
        assertTrue(ShazamFingerprint.compute(ShortArray(100)).isEmpty)
    }
}
