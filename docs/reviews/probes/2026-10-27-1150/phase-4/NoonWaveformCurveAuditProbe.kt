package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.ui.player.WaveformStore
import com.takahashirinta.ncrust.ui.player.waveform.BandLanes
import com.takahashirinta.ncrust.ui.player.waveform.WaveformCurve
import org.junit.Assert.*
import org.junit.Test

class NoonWaveformCurveAuditProbe {
    @Test fun productionThreeLanePointCountExceedsTheTangentLimitAndOvershootsTheSeam() {
        val count=WaveformStore.BAR_COUNT*BandLanes.LANE_COUNT+1
        assertEquals(85,count)
        val y=FloatArray(count) { 0.1f }
        y[61]=0.1f;y[62]=0.4f;y[63]=1f;y[64]=0.1f
        val tangents=FloatArray(count)
        WaveformCurve.computeTangents(y,count,1f,tangents)
        assertTrue(tangents[63]>0f)
        assertEquals(0f,tangents[64])
        assertFalse(WaveformCurve.staysWithinEndpoints(y[63],y[64],tangents[63],tangents[64],1f,steps=256))
        val peak=(0..256).maxOf { WaveformCurve.sampleAt(y[63],y[64],tangents[63],tangents[64],1f,it/256f) }
        assertTrue(peak>1f)
        println("AUDIT production waveform: renderer requests 85 points, tangent helper clamps to 64; segment 63→64 inherits the truncated prefix endpoint slope and overshoots normalized height to $peak. Pure production-count/curve math; no Canvas or visual playback")
    }
}
