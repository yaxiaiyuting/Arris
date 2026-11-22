/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.1.8 卡顿判据的单测。
 *
 * 四条必须覆盖的行为（对应真机现场的四种状态组合）：
 * 1. READY 但时钟不前进 ⇒ 判卡顿；
 * 2. 正常播放 ⇒ 永不误判；
 * 3. 用户暂停 ⇒ 不判卡顿；
 * 4. buffering 期间数据还在来 ⇒ 不判卡顿（合法的等待）。
 */
class PlaybackStallDetectorTest {

    private fun sample(
        nowMs: Long,
        positionMs: Long,
        bufferedMs: Long = positionMs,
        buffering: Boolean = false,
        intent: Boolean = true,
        generation: Long = 0L,
    ) = PlaybackStallDetector.Sample(
        nowMs = nowMs,
        positionMs = positionMs,
        bufferedMs = bufferedMs,
        buffering = buffering,
        playbackIntent = intent,
        actionGeneration = generation,
    )

    @Test
    fun `ready with a frozen clock is a stall`() {
        val detector = PlaybackStallDetector(stallThresholdMs = 12_000L)
        assertFalse(detector.observe(sample(0L, 18_396L, 23_978L)).stalled)
        // 现场形状：state=READY、isPlaying=true、position 与 buffered 都不动。
        for (second in 2L..10L step 2) {
            assertFalse(detector.observe(sample(second * 1_000L, 18_396L, 23_978L)).stalled)
        }
        val reading = detector.observe(sample(12_000L, 18_396L, 23_978L))
        assertTrue(reading.stalled)
        assertEquals(12_000L, reading.stalledForMs)
    }

    @Test
    fun `steady playback never reports a stall`() {
        val detector = PlaybackStallDetector(stallThresholdMs = 12_000L)
        for (second in 0L..600L) {
            val reading = detector.observe(sample(second * 1_000L, 5_000L + second * 1_000L, 60_000L))
            assertFalse(reading.stalled)
            assertEquals(0L, reading.stalledForMs)
        }
    }

    @Test
    fun `user pause is not a stall`() {
        val detector = PlaybackStallDetector(stallThresholdMs = 12_000L)
        assertFalse(detector.observe(sample(0L, 5_000L)).stalled)
        // 用户按下暂停：playWhenReady=false ⇒ 没有播放意图。
        for (second in 1L..300L) {
            val reading = detector.observe(sample(second * 1_000L, 5_000L, intent = false))
            assertFalse(reading.stalled)
            assertFalse(reading.armed)
            assertEquals(0L, reading.stalledForMs)
        }
        // 恢复播放后重新开始计时，而不是拿暂停前的时间戳算账。
        assertFalse(detector.observe(sample(301_000L, 5_000L)).stalled)
        assertFalse(detector.observe(sample(310_000L, 5_000L)).stalled)
        assertTrue(detector.observe(sample(313_000L, 5_000L)).stalled)
    }

    @Test
    fun `buffering that keeps receiving data is a legitimate wait`() {
        val detector = PlaybackStallDetector(stallThresholdMs = 12_000L)
        assertFalse(detector.observe(sample(0L, 5_000L, 5_000L, buffering = true)).stalled)
        // 位置不动，但 bufferedPosition 每秒涨一点 ⇒ 网络活着，不判卡顿。
        for (second in 1L..120L) {
            val reading = detector.observe(
                sample(second * 1_000L, 5_000L, 5_000L + second * 100L, buffering = true),
            )
            assertFalse("buffering with data at ${second}s must not stall", reading.stalled)
        }
    }

    @Test
    fun `buffering that stops receiving data is a stall`() {
        val detector = PlaybackStallDetector(stallThresholdMs = 12_000L)
        assertFalse(detector.observe(sample(0L, 5_000L, 5_000L, buffering = true)).stalled)
        assertFalse(detector.observe(sample(11_000L, 5_000L, 5_000L, buffering = true)).stalled)
        assertTrue(detector.observe(sample(12_000L, 5_000L, 5_000L, buffering = true)).stalled)
    }

    @Test
    fun `one report per gap and progress re-arms the detector`() {
        val detector = PlaybackStallDetector(stallThresholdMs = 12_000L)
        assertFalse(detector.observe(sample(0L, 5_000L)).stalled)
        assertTrue(detector.observe(sample(12_000L, 5_000L)).stalled)
        // 同一次卡顿不重复上报 —— 否则上层每 2 秒就会做一次 stop/prepare。
        for (second in 14L..60L step 2) {
            assertFalse(detector.observe(sample(second * 1_000L, 5_000L)).stalled)
        }
        // 恢复推进 ⇒ 新窗口。
        assertFalse(detector.observe(sample(62_000L, 5_500L)).stalled)
        assertFalse(detector.observe(sample(73_000L, 5_500L)).stalled)
        assertTrue(detector.observe(sample(74_000L, 5_500L)).stalled)
    }

    @Test
    fun `seek or track change discards the old window`() {
        val detector = PlaybackStallDetector(stallThresholdMs = 12_000L)
        assertFalse(detector.observe(sample(0L, 5_000L, generation = 7L)).stalled)
        assertFalse(detector.observe(sample(11_000L, 5_000L, generation = 7L)).stalled)
        // 用户 seek：代数递增 + 位置跳变。
        assertFalse(detector.observe(sample(11_100L, 90_000L, generation = 8L)).stalled)
        assertFalse(detector.observe(sample(22_000L, 90_000L, generation = 8L)).stalled)
        assertTrue(detector.observe(sample(23_100L, 90_000L, generation = 8L)).stalled)
    }

    @Test
    fun `audio focus suppression is not a stall`() {
        val detector = PlaybackStallDetector(stallThresholdMs = 12_000L)
        assertFalse(detector.observe(sample(0L, 5_000L)).stalled)
        val suppressed = PlaybackStallDetector.Sample(
            nowMs = 20_000L,
            positionMs = 5_000L,
            bufferedMs = 5_000L,
            buffering = false,
            playbackIntent = false,
            actionGeneration = 0L,
        )
        assertFalse(detector.observe(suppressed).stalled)
    }

    @Test
    fun `explicit reset starts a fresh window`() {
        val detector = PlaybackStallDetector(stallThresholdMs = 12_000L)
        assertFalse(detector.observe(sample(0L, 5_000L)).stalled)
        assertFalse(detector.observe(sample(11_000L, 5_000L)).stalled)
        detector.reset()
        assertFalse(detector.observe(sample(31_000L, 5_000L)).stalled)
        assertFalse(detector.observe(sample(42_000L, 5_000L)).stalled)
        assertTrue(detector.observe(sample(43_000L, 5_000L)).stalled)
    }
}
