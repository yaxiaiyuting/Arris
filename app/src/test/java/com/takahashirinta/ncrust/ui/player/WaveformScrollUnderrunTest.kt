/* Copyright (c) 2026 yaxiaiyuting. This Arris fork modification is licensed under GPLv3. */
package com.takahashirinta.ncrust.ui.player

import com.takahashirinta.ncrust.ui.player.waveform.VisualizerEffects
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
}
