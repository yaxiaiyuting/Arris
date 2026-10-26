package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.lyric.*
import org.junit.Assert.*
import org.junit.Test

class ScheduledLyricGeometryAuditProbe {
    @Test fun oneLyricWordWrappingAcrossThreeVisualLinesSkipsTheMiddleLine() {
        val geometry=object:SweepGeometry {
            override val rtl=false
            override fun lineForChar(charIndex:Int)=charIndex/4
            override fun lineStart(lineIndex:Int)=0f
            override fun lineEnd(lineIndex:Int)=40f
            override fun charStart(charIndex:Int)=(charIndex%4)*10f
            override fun charEnd(charIndex:Int)=(charIndex%4+1)*10f
        }
        val track=SweepTrack.build(listOf(LrcWord(0,1200,"abcdefghijkl",0,12)),12,1200,geometry,0f)!!
        val visited=(0L..1200L step 10).mapNotNull { track.sample(it)?.lineIndex }.toSet()
        assertEquals(setOf(0,2),visited)
        assertFalse(1 in visited)
        println("AUDIT lyric geometry: a valid timed span covering three wrapped lines produces sweep samples on lines 0 and 2 only; line 1 can only become lit at the jump")
    }
}
