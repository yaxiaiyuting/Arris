/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.ui.player

import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.lyric.LrcParser
import com.takahashirinta.ncrust.lyric.LrcWord
import com.takahashirinta.ncrust.lyric.LyricClock
import com.takahashirinta.ncrust.lyric.YrcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KaraokeTimelineTest {
    @Test fun oneSecondOfFramesSharesTheSameCueAcrossSamplesAndSeeks() {
        val timeline = KaraokeTimeline(listOf(LrcLine(7000, "First"), LrcLine(9000, "Second")), emptyMap())
        val singing = timeline.cueAt(7000)
        val counting = timeline.cueAt(4000)
        repeat(120) { frame ->
            assertSame(singing, timeline.cueAt(7000L + frame * 8L))
            assertSame(counting, timeline.cueAt(4000L + frame * 8L))
        }
    }

    @Test fun introCountdownEndsOnFirstPhraseAndBackwardSeekRecomputesIt() {
        val timeline = KaraokeTimeline(listOf(LrcLine(7000, "First"), LrcLine(9000, "Second")), emptyMap())
        assertEquals(KaraokeCue(-1, 0), timeline.cueAt(3999))
        assertEquals(KaraokeCue(-1, 0, 3), timeline.cueAt(4000))
        assertEquals(KaraokeCue(-1, 0, 1), timeline.cueAt(6999))
        assertEquals(KaraokeCue(0, 1), timeline.cueAt(7000))
        assertEquals(KaraokeCue(1, -1), timeline.cueAt(10000))
        assertEquals(KaraokeCue(-1, 0, 2), timeline.cueAt(5001))
        assertTrue(timeline.boundaries.containsAll(listOf(4000L, 5000L, 6000L)))
    }

    @Test fun longIntervalOnlyStartsAfterBothTimedTracksFinish() {
        val first = LrcLine(1000, "A", endMs = 3000)
        val translated = LrcLine(1000, "B", words = listOf(LrcWord(4000, 1000, "B", 0, 1)))
        val timeline = KaraokeTimeline(listOf(first, LrcLine(11500, "C")), mapOf(1000L to translated))
        assertEquals(KaraokeCue(0, 1), timeline.cueAt(4999))
        assertEquals(KaraokeCue(-1, 1), timeline.cueAt(5000))
        assertEquals(KaraokeCue(-1, 1, 3), timeline.cueAt(8500))
        assertEquals(KaraokeCue(1, -1), timeline.cueAt(11500))
    }

    @Test fun plainLrcNeverClaimsAnInstrumentalBreakFromAStartTimestampAlone() {
        val timeline = KaraokeTimeline(listOf(LrcLine(1000, "A"), LrcLine(16000, "B")), emptyMap())
        assertEquals(KaraokeCue(0, 1), timeline.cueAt(15000))
        assertEquals(KaraokeCue(-1, -1), KaraokeTimeline(emptyList(), emptyMap()).cueAt(5000))
    }

    @Test fun matchedYrcStartsTheCueAtTheFirstWordWhileTranslationKeepsItsSourceStamp() {
        val lines = YrcParser.attachWords(
            LrcParser.parse("[00:07.00]A\n[00:16.00]B"),
            "[5800,900](5800,900,0)A",
        )
        assertEquals(7_000L, lines.first().timeMs)
        val translation = LrcLine(7_000, "译", words = listOf(LrcWord(8_000, 1_000, "译", 0, 1)))
        val timeline = KaraokeTimeline(lines, mapOf(translation.timeMs to translation))
        assertEquals(KaraokeCue(-1, 0, 1), timeline.cueAt(5_799))
        assertEquals(KaraokeCue(0, 1), timeline.cueAt(5_800))
        assertEquals(KaraokeCue(0, 1), timeline.cueAt(8_999))
        assertEquals(KaraokeCue(-1, 1), timeline.cueAt(9_000))
    }

    @Test fun interpolationScalesMediaTimeButStillStopsAfter750WallMilliseconds() {
        assertEquals(1750L, LyricClock.positionAt(1000, 0, 500_000_000, speed = 1.5f))
        assertEquals(2125L, LyricClock.positionAt(1000, 0, 2_000_000_000, speed = 1.5f))
    }
}
