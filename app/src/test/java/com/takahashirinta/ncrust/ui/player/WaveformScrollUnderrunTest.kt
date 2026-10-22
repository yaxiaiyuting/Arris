/* Copyright (c) 2026 yaxiaiyuting. This Arris fork modification is licensed under GPLv3. */
package com.takahashirinta.ncrust.ui.player

import com.takahashirinta.ncrust.ui.player.waveform.VisualizerEffects
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerTier
import org.junit.Assert.*
import org.junit.Test

class WaveformScrollUnderrunTest {
    @Test
    fun `playing history keeps moving through a short data gap`() {
        val h = WaveformScrollJitterHarness(capacity = 128, barCount = 16)
        val base = 1_000_000.0
        for (t in 0..4_000 step 10) {
            if (t % 100 == 0 && t !in 2_000..2_250) h.pushBar(base + t, .55f)
            val frame = h.advanceTo(base + t)
            if (t in 2_000..2_250) {
                assertTrue("data gap froze visible history at $t ms: ${frame.displacement}", frame.displacement > .07f)
                assertTrue("moving history must redraw at $t ms", frame.wantsRedraw)
            }
        }
    }

    @Test
    fun `quiet visible detail redraws between samples too`() {
        val h = WaveformScrollJitterHarness(capacity = 128, barCount = 16)
        val base = 1_000_000.0
        for (t in 0..4_000 step 10) {
            if (t % 100 == 0) h.pushBar(base + t, if (t % 200 == 0) .005f else .015f)
            val frame = h.advanceTo(base + t)
            if (t > 2_000) assertTrue("quiet history stopped invalidating at $t ms", frame.wantsRedraw)
        }
    }

    @Test
    fun `empty input scrolls measured history out then stops invalidating`() {
        val ring = WaveformRing(capacity = 64, barCount = 16)
        repeat(16) { ring.push(.6f, .4f, .3f, .2f, 1_000L + it * 100L) }
        ring.pump(true, 10f, VisualizerEffects.BASELINE, 3_000)
        var changed = true
        for (t in 3_010L..9_000L step 10L) changed = ring.pump(true, 10f, VisualizerEffects.BASELINE, t)
        assertEquals("missing data is a blank, never repeated invented peaks", 0f, ring.newestTarget(), 0f)
        assertEquals(0f, ring.bandAt(0, 0), 0f)
        assertEquals(0f, ring.bandAt(1, 0), 0f)
        assertEquals(0f, ring.bandAt(2, 0), 0f)
        assertFalse("fully empty history must stop requesting redraw", changed)
    }

    @Test
    fun `paused playback never advances the scrolling window`() {
        val ring = WaveformRing(capacity = 64, barCount = 16)
        ring.push(.6f, arrivalAtMs = 1_000)
        ring.pump(true, 10f, VisualizerEffects.BASELINE, 1_000)
        val columns = ring.shiftedCellsForTest()
        val phase = ring.scrollPhase01()
        for (t in 1_010L..2_000L step 10L) ring.pump(false, 10f, VisualizerEffects.BASELINE, t)
        assertEquals(columns, ring.shiftedCellsForTest())
        assertEquals(phase, ring.scrollPhase01(), 0f)
    }
    @Test
    fun `blank underrun column carries no stale ball or dash`() {
        val ring = WaveformRing(capacity = 64, barCount = 16)
        val refined = VisualizerEffects.of(
            tier = VisualizerTier.REFINED,
            showcase = false,
            shockwave = false,
            particles = false,
            perspective = false,
            tapInteraction = false,
        )
        var now = 1_000L
        ring.push(.9f, .9f, .8f, .7f, now)
        // 先让小球/短横在最后一格稳定到柱顶附近（首帧会把这根柱填进窗口）。
        repeat(5) {
            now += 16
            ring.pump(true, 16f, refined, now)
        }
        assertTrue("前置条件：末格小球应在柱顶附近", ring.bandPeakAt(0, 15) > .2f)
        // 再跨过一个格周期但**没有新数据**：应追加一列空白，而不是继承旧峰值。
        now += 200
        ring.pump(true, 16f, refined, now)
        assertEquals("缺样列的目标值必须是 0", 0f, ring.bandAt(0, 15), 0f)
        assertEquals("缺样列不能继承上一格的小球", 0f, ring.bandPeakAt(0, 15), 0f)
        assertEquals("缺样列不能继承上一格的短横", 0f, ring.bandDashAt(0, 15), 0f)
    }

}
