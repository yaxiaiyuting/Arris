package com.takahashirinta.ncrust.audit

import androidx.media3.common.C
import com.takahashirinta.ncrust.player.AudioFeatureExtractor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import org.junit.Assert.*
import org.junit.Test

class NoonAudioFeatureAuditProbe {
    private fun pcm(channels:Int,ms:Int,amplitude:Short)=ByteBuffer.allocate(48*ms*channels*2).order(ByteOrder.nativeOrder()).apply {
        repeat(48*ms*channels) { putShort(amplitude) };flip()
    }
    @Test fun duplicatingMonoIntoStereoAdvancesTheEnvelopeClockFourTimesAsFast() {
        val mono=AudioFeatureExtractor().also { it.configure(48000,1) }
        val stereo=AudioFeatureExtractor().also { it.configure(48000,2) }
        assertTrue(mono.process(pcm(1,100,16384),C.ENCODING_PCM_16BIT))
        assertTrue(stereo.process(pcm(2,100,16384),C.ENCODING_PCM_16BIT))
        assertEquals(mono.rms,stereo.rms,0.00001f)
        assertEquals(mono.frameMs,stereo.frameMs,0.00001f)
        assertEquals((0.5*(1-exp(-100.0/800))).toFloat(),mono.fullBaseline(),0.00001f)
        assertEquals((0.5*(1-exp(-400.0/800))).toFloat(),stereo.fullBaseline(),0.00001f)
        println("AUDIT audio envelope: identical 100ms mono/stereo signal has equal RMS and frameMs, but baseline mono=${mono.fullBaseline()} stereo=${stereo.fullBaseline()}; stereo envelope behaves as if 400ms passed")
    }
    @Test fun stereoCanRetriggerWithinTheNinetyMillisecondCooldown() {
        fun run(channels:Int):Int {
            val e=AudioFeatureExtractor().also { it.configure(48000,channels) }
            e.process(pcm(channels,10,26214),C.ENCODING_PCM_16BIT)
            assertTrue(e.transientCount>0)
            e.process(pcm(channels,30,0),C.ENCODING_PCM_16BIT)
            e.process(pcm(channels,10,26214),C.ENCODING_PCM_16BIT)
            return e.transientCount
        }
        assertEquals(0,run(1))
        assertTrue(run(2)>0)
        println("AUDIT cooldown: duplicated stereo pulse retriggers only 40ms after the initial onset despite the configured 90ms cooldown; mono control does not")
    }
}
