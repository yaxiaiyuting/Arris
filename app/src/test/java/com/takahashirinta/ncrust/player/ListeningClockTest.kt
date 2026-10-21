package com.takahashirinta.ncrust.player

import org.junit.Assert.*
import org.junit.Test

class ListeningClockTest {
    @Test fun manualReplayIsConsumedOnceAcrossTransitionAndTrackStartedCallbacks() {
        var time = 0L
        val clock = ListeningClock { time }
        clock.begin(1)
        clock.setPlaying(true)
        time = 5_000
        clock.restartOnNextBegin(1)
        assertTrue(clock.begin(1))
        time += 100
        assertFalse(clock.begin(1))
        assertEquals(100L, clock.playedMs(1))

        // The first callback for a different track also consumes its pending restart.
        clock.restartOnNextBegin(2)
        assertTrue(clock.begin(2))
        time += 100
        assertFalse(clock.begin(2))
        assertEquals(100L, clock.playedMs(2))
    }

    @Test fun pausedSeekAndRestoredPositionCannotCountAsListening() {
        var elapsed=0L;val clock=ListeningClock {elapsed}
        clock.begin(1);elapsed+=210_000
        assertEquals(0,clock.playedMs(1))
        clock.setPlaying(true);elapsed+=1_000;clock.setPlaying(false)
        elapsed+=30_000
        assertEquals(1000,clock.playedMs(1))
        assertFalse(PlayReporter.reachedCompletion(clock.playedMs(1),240_000))
        assertTrue(PlayReporter.playLogs(1,clock.playedMs(1)).contains("\"time\":1,"))
    }
    @Test fun bufferingAndPauseAreExcludedWhileBackgroundPlaybackCounts() {
        var time=0L;val clock=ListeningClock {time}
        clock.begin(1);clock.setPlaying(true);time=5_000;clock.setPlaying(false)
        time+=30_000;clock.setPlaying(true);time+=3_000
        assertEquals(8000,clock.playedMs(1))
    }
    @Test fun gaplessTransitionStartsANewSessionAndQualityReloadKeepsTheSameOne() {
        var time=0L;val clock=ListeningClock {time}
        clock.begin(1);clock.setPlaying(true);time=5_000
        assertTrue(clock.begin(2));time+=1_000
        assertEquals(0,clock.playedMs(1));assertEquals(1000,clock.playedMs(2))
        assertFalse(clock.begin(2));time+=1_000;assertEquals(2000,clock.playedMs(2))
        assertTrue(clock.begin(2,restart=true));assertEquals(0,clock.playedMs(2))
    }
    @Test fun completionRequiresActualEightyPercentAndEndDoesNotInventTheFullDuration() {
        var time=0L;val clock=ListeningClock {time}
        clock.begin(1);clock.setPlaying(true)
        time=191_999;assertFalse(PlayReporter.reachedCompletion(clock.playedMs(1),240_000))
        time++;assertTrue(PlayReporter.reachedCompletion(clock.playedMs(1),240_000))
        clock.setPlaying(false);time+=500_000
        assertEquals(192_000,clock.playedMs(1))
    }
    @Test fun clockRollbackAndUnknownTrackCannotAddTime() {
        var time=100_000L;val clock=ListeningClock {time}
        clock.setPlaying(true);time+=10_000;clock.begin(1)
        assertEquals(0,clock.playedMs(1))
        time=0;assertEquals(0,clock.playedMs(1));time=500;assertEquals(500,clock.playedMs(1))
    }
}
