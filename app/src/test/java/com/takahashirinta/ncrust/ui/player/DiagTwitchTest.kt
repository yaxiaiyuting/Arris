package com.takahashirinta.ncrust.ui.player

import org.junit.Test

class DiagTwitchTest {
    @Test
    fun 长跑步长序列() {
        // 复刻「60Hz 帧 + 100ms 柱」并检查可见位置是否单调、步长是否恒定
        val ring = WaveformRing(capacity = 512, barCount = 16)
        ring.setBarIntervalForTest(100f)
        ring.push(0.5f, 0.5f, 0.5f, 0.5f)
        var since = 0f; var prev = 0f
        var worstBack = 0f; var minStep = Float.MAX_VALUE; var maxStep = -1f
        val samples = ArrayList<Float>()
        repeat(300) { fr ->
            ring.pump(active = true, dtMs = 16.667f)
            since += 16.667f
            if (since >= 100f) { since -= 100f; ring.push(0.5f,0.5f,0.5f,0.5f); ring.pump(active = true, dtMs = 0f) }
            val pos = ring.shiftedCellsForTest() + ring.scrollPhase01()
            val raw = ring.rawScrollPhaseForTest()
            if (fr > 10) {
                val step = pos - prev
                if (step < worstBack) worstBack = step
                if (step < minStep) minStep = step
                if (step > maxStep) maxStep = step
                samples.add(step)
            }
            prev = pos
            if (fr in 10..26) println("DIAG fr=$fr raw=%.4f shifted=%.0f pos=%.4f step=%.4f".format(raw, ring.shiftedCellsForTest(), pos, if (fr>10) samples.last() else 0f))
        }
        println("DIAG 步长 min=%.4f max=%.4f 最大倒退=%.4f".format(minStep, maxStep, worstBack))
        println("DIAG 期望步长=0.1667")
    }
}
