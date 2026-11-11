/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.ui.player.motion

import com.takahashirinta.ncrust.ui.player.WaveformStore
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerEffects
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPlaybackTest {
    @Test
    fun `paused clock waits after settling and buffering is interruptible`() = runBlocking {
        val playback = MutableStateFlow(MotionPlayback.Playing)
        val events = Channel<String>(Channel.UNLIMITED)
        val needsMore = Channel<Boolean>()
        val driver = launch {
            playback.runMotionFrames(
                play = { resuming ->
                    events.send("play $resuming")
                    awaitCancellation()
                },
                settle = { hold ->
                    events.send("settle $hold")
                    needsMore.receive()
                },
            )
        }
        try {
            withTimeout(2_000) {
                assertEquals("play false", events.receive())
                playback.value = MotionPlayback.Paused
                assertEquals("settle false", events.receive())
                needsMore.send(true)
                assertEquals("settle false", events.receive())
                needsMore.send(false)
                assertNull(withTimeoutOrNull(30) { events.receive() })

                playback.value = MotionPlayback.Buffering
                assertEquals("settle true", events.receive())
                playback.value = MotionPlayback.Playing
                assertEquals("play true", events.receive())
            }
        } finally {
            driver.cancelAndJoin()
        }
    }

    @Test
    fun `breathing fade needs more frames between its thirty hertz draws`() {
        WaveformStore.resetForTest()
        MotionClock.resetForTest()
        val effects = MotionEffects.of(MotionIntensity.SIMPLE, uiMotionEnabled = false)
        val waveform = VisualizerEffects.BASELINE
        try {
            WaveformStore.onFeatures(0.0, 0.8, 0.5, 0.3, 0.0, available = true)
            MotionClock.frame(true, 50f, waveform, effects, animateBreathing = true)
            val generation = MotionClock.breathGeneration
            assertTrue(MotionClock.frame(false, 16f, waveform, effects, animateBreathing = true))
            assertEquals(generation, MotionClock.breathGeneration)

            var limit = 300
            while (MotionClock.frame(false, 16f, waveform, effects, animateBreathing = true) && --limit > 0) Unit
            assertTrue(limit > 0)
            assertFalse(MotionClock.breathing.hasEnergy)
        } finally {
            WaveformStore.resetForTest()
            MotionClock.resetForTest()
        }
    }

    @Test
    fun `resume ignores transients accumulated while idle and keeps the next beat`() {
        WaveformStore.resetForTest()
        MotionClock.resetForTest()
        val effects = MotionEffects.of(MotionIntensity.SHOWCASE, uiMotionEnabled = true)
        try {
            WaveformStore.onFeatures(0.2, 0.3, 0.1, 0.1, 0.2, available = true)
            MotionClock.frame(false, 16f, effects.waveform, effects)
            WaveformStore.onTransient(4, 1f)
            MotionClock.alignTransientCounts()
            MotionClock.frame(true, 16f, effects.waveform, effects)
            assertEquals(0, MotionClock.bindings().transients)
            assertEquals(0, WaveformStore.showcase.aliveRipples())

            WaveformStore.onTransient(1, 1f)
            MotionClock.frame(true, 16f, effects.waveform, effects)
            assertEquals(1, MotionClock.bindings().transients)
            assertTrue(WaveformStore.showcase.aliveRipples() > 0)
        } finally {
            WaveformStore.resetForTest()
            MotionClock.resetForTest()
        }
    }
}
