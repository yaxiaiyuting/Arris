package com.takahashirinta.ncrust.ui.player

import org.junit.Test

class ZScratchEmaTest {
    @Test
    fun trace() {
        val h = WaveformScrollJitterHarness()
        val frames = ArrayList<Double>()
        var t = 0.0
        while (t < 2000.0) { frames.add(t); t += 1000.0 / 60 }
        val bars = ArrayList<Pair<Double, Float>>()
        var b = 0.0
        while (b < 2000.0) { bars.add(b to 0.6f); b += 100.0 }
        var bi = 0; var fi = 0
        var prevShifted = 0L
        while (fi < frames.size) {
            val ft = frames[fi]
            if (bi < bars.size && bars[bi].first <= ft) {
                h.pushBar(bars[bi].first, bars[bi].second); bi++
            } else {
                val f = h.advanceTo(ft)
                if (f.shiftedCells != prevShifted) {
                    println("SCRATCH 消费 t=%8.3f 平移=%d 间隔估计=%.2f phase=%.4f 位移=%.4f".format(f.tMs, f.shiftedCells, f.intervalMs, f.phase, f.displacement))
                    prevShifted = f.shiftedCells
                }
                fi++
            }
        }
    }
}
