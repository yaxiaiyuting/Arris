package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.qq.QqApi
import org.junit.Assert.*
import org.junit.Test

/** Calls production alignment only. A passing assertion confirms the current defect. */
class EveningLyricAuditProbe {
    @Test fun exactTimeTranslationsAreDuplicatedAndShiftedWhenLinesAreOneSecondApart() {
        val main = (0..3).map { LrcLine(it * 1000L, "main-$it") }
        val sub = (0..3).map { LrcLine(it * 1000L, "translation-$it") }
        val actual = QqApi.alignToMainLines(main, sub)
        assertEquals(listOf(0L, 1000L, 2000L, 3000L), actual.map { it.timeMs })
        assertEquals(listOf("translation-0", "translation-0", "translation-1", "translation-2"), actual.map { it.text })
        assertNotEquals(sub.map { it.text }, actual.map { it.text })
        println("AUDIT real QQ aligner: exact timestamp translations at 0/1000/2000/3000ms become 0/0/1/2; the last translation is lost. No parser, network, rendering or device used")
    }

    @Test fun earlierCandidateBeatsExactTimestampEvenWhenTranslationsAreNotReused() {
        val main = listOf(LrcLine(2000, "main"))
        val sub = listOf(LrcLine(1000, "earlier-line"), LrcLine(2000, "exact-line"))
        val actual = QqApi.alignToMainLines(main, sub)
        assertEquals("earlier-line", actual.single().text)
        assertEquals(2000L, actual.single().timeMs)
        println("AUDIT real QQ aligner chooses a 1000ms-earlier candidate and rewrites its timestamp to 2000ms instead of choosing the available exact match. Same production function serves translations and romanization")
    }
}
