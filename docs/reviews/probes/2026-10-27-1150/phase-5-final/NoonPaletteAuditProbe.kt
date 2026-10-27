package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.ui.theme.CoverThemeExtractor
import com.takahashirinta.ncrust.ui.theme.color.QuantizerCelebi
import com.takahashirinta.ncrust.ui.theme.color.QuantizerWu
import com.takahashirinta.ncrust.ui.theme.color.QuantizerWsmeans
import org.junit.Assert.*
import org.junit.Test

class NoonPaletteAuditProbe {
    @Test fun sortedDistancePruningMergesExactGreenAndYellowStartingCenters() {
        val values=intArrayOf(0xffff0000.toInt(),0xff00ff00.toInt(),0xff0000ff.toInt(),0xffffffff.toInt(),0xff000000.toInt(),0xffffff00.toInt(),0xffff00ff.toInt())
        val out=QuantizerWsmeans.quantize(values,values,values.size)
        assertEquals(6,out.size)
        assertFalse(out.containsKey(0xff00ff00.toInt()));assertFalse(out.containsKey(0xffffff00.toInt()))
        assertEquals(2,out[0xffaeff00.toInt()])
        println("AUDIT Wsmeans: seven distinct input colors are also supplied as seven exact starting centers, but sorted distance pruning still skips the matching center; green/yellow merge into ffaeff00. This bypasses Wu, so it is independent of the Int overflow defect")
    }
    @Test fun theProductionFiftyByFiftyTwoColorImageCollapsesIntoOneInventedColor() {
        val red=0xffff0000.toInt();val green=0xff00ff00.toInt()
        val side=CoverThemeExtractor.SAMPLE_MAX_PX
        assertEquals(50,side)
        val pixels=IntArray(side*side) { if(it%2==0) red else green }
        val result=QuantizerCelebi.quantize(pixels,128)
        assertEquals(1,result.size)
        assertFalse(result.containsKey(red));assertFalse(result.containsKey(green))
        assertEquals(pixels.size,result.values.sum())
        println("AUDIT palette production size: a 50x50 image with exactly red/green and budget128 quantizes to ${result.mapKeys { it.key.toUInt().toString(16) }}; Wu's Int square overflow makes valid split gains negative, leaving Wsmeans one starting cluster")
    }
    @Test fun aConstantColorHasLargeNonzeroVarianceBecauseChannelSumsAreSquaredAsInts() {
        val w=QuantizerWu()
        w.constructHistogram(mapOf(0xffffffff.toInt() to 2500));w.createMoments()
        val box=QuantizerWu.Box().apply { r1=32;g1=32;b1=32 }
        val variance=w.variance(box)
        assertTrue(variance>1_000_000.0)
        println("AUDIT palette variance: constant white population2500 has mathematical variance0, actual=$variance; Int multiplications overflow before conversion to Double")
    }
}
