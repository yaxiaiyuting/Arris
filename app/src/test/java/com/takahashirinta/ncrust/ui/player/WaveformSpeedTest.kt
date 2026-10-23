/* Copyright (c) 2026 yaxiaiyuting. This Arris fork modification is licensed under GPLv3. */
package com.takahashirinta.ncrust.ui.player

import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerEffects
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerPrefs
import org.junit.Assert.*
import org.junit.Test

/**
 * v3.6.9：波形横向滚动倍速。
 *
 * 用户反馈「波形条太慢」，要求可调倍速。倍速只改 UI 消费节拍；>1 时数据会先被
 * 取完，缺样分支改为**重复最后一列**，避免把加速做成空白断层；1× 及以下仍保持
 * 「缺失位置留空」。
 */
class WaveformSpeedTest {

    @Test
    fun `倍速被夹到合法区间且忽略非有限值`() {
        val ring = WaveformRing(capacity = 64, barCount = 16)
        assertEquals(1f, ring.scrollSpeedFactor(), 0f)
        ring.setScrollSpeedFactor(2f)
        assertEquals(2f, ring.scrollSpeedFactor(), 0f)
        ring.setScrollSpeedFactor(Float.NaN)
        assertEquals("NaN 不能破坏当前倍速", 2f, ring.scrollSpeedFactor(), 0f)
        ring.setScrollSpeedFactor(99f)
        assertEquals(4f, ring.scrollSpeedFactor(), 0f)
        ring.setScrollSpeedFactor(0f)
        assertEquals(0.25f, ring.scrollSpeedFactor(), 0f)
    }

    @Test
    fun `二倍速在相同时间里消费约两倍历史`() {
        fun columnsAt(speed: Float): Long {
            val ring = WaveformRing(capacity = 256, barCount = 16)
            repeat(200) { ring.push(.5f, .4f, .3f, .2f, 1_000L + it * 10L) }
            ring.setScrollSpeedFactor(speed)
            var now = 3_000L
            // 首帧填窗口（不计平移），之后 1s 的消费才用于比较。
            ring.pump(true, 16f, VisualizerEffects.BASELINE, now)
            val base = ring.shiftedCellsForTest()
            while (now < 4_000L) {
                now += 16
                ring.pump(true, 16f, VisualizerEffects.BASELINE, now)
            }
            return ring.shiftedCellsForTest() - base
        }
        val one = columnsAt(1f)
        val two = columnsAt(2f)
        assertTrue("1× 应消费约 10 列，实测 $one", one in 9L..12L)
        assertTrue("2× 应消费约 20 列，实测 $two", two in 18L..24L)
        assertTrue("2× 必须明显快于 1×（$two vs $one）", two >= one * 3 / 2)
    }

    @Test
    fun `倍速缺样重复最后一列而不是留空`() {
        fun lastBandAfterGap(speed: Float): Float {
            val ring = WaveformRing(capacity = 64, barCount = 16)
            ring.push(.9f, .8f, .7f, .6f, 1_000L)
            ring.setScrollSpeedFactor(speed)
            var now = 1_000L
            // 首帧填窗 + 让最后一格稳定。
            repeat(5) {
                now += 16
                ring.pump(true, 16f, VisualizerEffects.BASELINE, now)
            }
            // 200ms 没有新数据：1× 应留空；2× 应重复上一列。
            now += 200
            ring.pump(true, 16f, VisualizerEffects.BASELINE, now)
            return ring.bandAt(0, 15)
        }
        assertEquals("1× 缺样必须留空", 0f, lastBandAfterGap(1f), 0f)
        assertTrue("2× 缺样应重复最后一列，实测 ${lastBandAfterGap(2f)}", lastBandAfterGap(2f) > .5f)
    }
    @Test
    fun `倍速设置默认 1_5 倍并可往返读写`() {
        val prefs = MemoryPrefs()
        assertEquals("缺 key 默认 1.5×", 3, VisualizerPrefs.readSpeedStep(prefs))
        assertEquals(1.5f, VisualizerPrefs.readSpeedFactor(prefs), 0f)
        VisualizerPrefs.writeSpeedStep(prefs, 0)
        assertEquals(0, VisualizerPrefs.readSpeedStep(prefs))
        assertEquals(0.5f, VisualizerPrefs.readSpeedFactor(prefs), 0f)
        prefs.putRaw(VisualizerPrefs.KEY_SPEED_STEP, 99)
        assertEquals("越界值夹到最后一档", 4, VisualizerPrefs.readSpeedStep(prefs))
        assertEquals(2f, VisualizerPrefs.readSpeedFactor(prefs), 0f)
    }

}
