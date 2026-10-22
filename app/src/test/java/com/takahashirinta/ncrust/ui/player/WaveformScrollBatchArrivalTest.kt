/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.6.3：**成批到达**的柱必须被摊平成匀速滚动 —— 用户报的「一阵子动一下一阵子停止」。
 *
 * ## 症状与量到的形状
 *
 * 真实音频链路会把多根柱放进**同一次回调**（同一个 `uptimeMillis()`）。旧模型有两个前提：
 * 窗口每帧最多平移一格、相位按「距最后一根柱的到达时刻」算。成批到达把两个前提都破了：
 *
 * ```
 * 3 根/300ms（60Hz）：8 帧位移 0.0000 → 一帧 2.00 格 → 0.05 … → 再 8 帧 0.0000
 * 2 根/200ms（60Hz）：3 帧位移 0.0000 → 一帧 1.08 格 → 0.08 …
 * ```
 *
 * 前一段就是「停止」，那一帧就是「动一下」。同一份仿真在 v3.6.3 上：
 * **0.16~0.17 格/帧、0 个顿帧、最大步 1.0 倍名义**（60Hz，柱间隔 100ms）。
 *
 * ## 修法的两个可判定点
 *
 * 1. **柱间隔按批折算**：`(最新批起点 − 最早批起点) ÷ 中间各批柱数之和`。
 *    3 根/300ms 必须读出 **100ms/根**，而不是 300ms（按相邻两根）或 50ms（只除上一批柱数）。
 * 2. **画面按时间铺开**：每跨过一个格周期才消费**一根**。一批 3 根会在 3 个周期里被取走，
 *    所以窗口每帧最多平移一格、相位只回绕一格。
 */
class WaveformScrollBatchArrivalTest {

    private fun runBurst(
        seconds: Double,
        burstEveryMs: Double,
        barsPerBurst: Int,
        frameHz: Double = 60.0,
    ): WaveformScrollJitterHarness {
        val h = WaveformScrollJitterHarness(capacity = 512, barCount = 16)
        val base = 1_000_000.0
        var nextBurst = 0.0
        var t = 0.0
        while (t < seconds * 1000.0) {
            while (t >= nextBurst) {
                repeat(barsPerBurst) { h.pushBar(base + nextBurst, 0.5f) }
                nextBurst += burstEveryMs
            }
            h.advanceTo(base + t)
            t += 1000.0 / frameHz
        }
        return h
    }

    @Test
    fun `成批到达时每根柱的间隔按批折算到 100ms`() {
        listOf(300.0 to 3, 200.0 to 2, 400.0 to 4).forEach { (every, n) ->
            val h = runBurst(seconds = 6.0, burstEveryMs = every, barsPerBurst = n)
            val reported = h.frames.drop(180).map { it.intervalMs }
            val mean = reported.average()
            assertEquals("$n 根/${every.toInt()}ms 的每根柱间隔必须是 100ms（实际 $mean）", 100.0, mean, 2.0)
        }
    }

    @Test
    fun `成批到达时位移必须匀速 —— 不许停一批再冲一批`() {
        listOf(300.0 to 3, 200.0 to 2, 400.0 to 4).forEach { (every, n) ->
            val h = runBurst(seconds = 6.0, burstEveryMs = every, barsPerBurst = n)
            val s = h.summary()
            val tag = "$n 根/${every.toInt()}ms"
            assertTrue("$tag：样本太少（${s.frames} 帧）", s.frames > 200)
            assertEquals("$tag：不许有停顿帧（位移 < 25% 名义）", 0, s.stallCount)
            assertEquals("$tag：位移不许倒退", 0, s.backsteps)
            assertTrue("$tag：最大步是名义的 %.2f 倍，超过 1.6 就是「冲」".format(s.maxStepRatio), s.maxStepRatio <= 1.6f)
            assertTrue("$tag：抖动率 %.2f%% 应低于 10%%".format(s.jitterRatio * 100), s.jitterRatio < 0.10)
        }
    }

    @Test
    fun `一批只消费一根 —— 一帧最多平移一格`() {
        val h = runBurst(seconds = 6.0, burstEveryMs = 300.0, barsPerBurst = 3)
        val frames = h.frames.drop(WaveformScrollJitterHarness.SKIP_FRAMES)
        for (i in 1 until frames.size) {
            val cells = frames[i].shiftedCells - frames[i - 1].shiftedCells
            assertTrue("t=%.1f 一帧平移了 %d 格".format(frames[i].tMs, cells), cells <= 1L)
        }
    }

    @Test
    fun `首帧把已有历史一次填满，之后走时间节拍`() {
        val h = WaveformScrollJitterHarness(capacity = 64, barCount = 16)
        val base = 1_000_000.0
        // 起播前已经积了 10 根柱（用户先播放、后展开播放器）
        repeat(10) { h.pushBar(base + it * 100.0, 0.4f) }
        h.advanceTo(base + 20.0)
        assertEquals("首帧应把 10 根柱全部填进窗口（窗口 16 格）", 10L, h.ring.shiftedCellsForTest())
        val afterFirst = h.ring.shiftedCellsForTest()
        // 下一帧只推进 20ms，不到一格 ⇒ 不消费
        h.advanceTo(base + 40.0)
        assertEquals(afterFirst, h.ring.shiftedCellsForTest())
    }
    @Test
    fun `积压超过一屏时重建到最新，不把旧柱一格一格慢慢播`() {
        val h = WaveformScrollJitterHarness(capacity = 256, barCount = 16)
        val base = 1_000_000.0
        // 挂载前已经积了 100 根柱（用户先播放、后展开播放器）
        repeat(100) { h.pushBar(base + it * 100.0, it / 100f) }
        h.advanceTo(base + 20.0)
        assertEquals("首帧只消费最新一屏", 16L, h.ring.shiftedCellsForTest())
        assertEquals("最右格必须是最新那根柱", 0.99f, h.ring.targetAt(15), 1e-6f)
        assertEquals("旧柱应被丢掉，而不是留在队列里", 0, h.ring.pendingCount)
    }

    @Test
    fun `长卡顿后重建窗口，不留下几十格的播放积压`() {
        val h = WaveformScrollJitterHarness(capacity = 256, barCount = 16)
        val base = 1_000_000.0
        repeat(20) { h.pushBar(base + it * 100.0, 0.5f) }
        h.advanceTo(base + 20.0)
        val afterPrime = h.ring.shiftedCellsForTest()
        // 卡顿 1 秒，期间音频线程又推了 10 根柱
        repeat(10) { h.pushBar(base + 2_000.0 + it * 100.0, 0.8f) }
        h.advanceTo(base + 1_020.0)
        // 卡顿期间只新到了 10 根柱：重建会把**所有**新柱一次搬进窗口（+10），
        // 而不是只前进一格、把剩下 9 根留在队列里慢慢播（那会永久落后 0.9 秒）。
        assertEquals(
            "长卡顿后应吃掉全部积压，而不是只前进一格",
            afterPrime + 10,
            h.ring.shiftedCellsForTest(),
        )
        assertEquals(0, h.ring.pendingCount)
        assertEquals(0.8f, h.ring.targetAt(15), 1e-6f)
    }

    /** 诊断：把三种成批工况的位移分布打出来（给发布验证留一份可复算的数字）。 */
    @Test
    fun `成批到达的量化摘要`() {
        listOf(300.0 to 3, 200.0 to 2, 400.0 to 4, 100.0 to 1).forEach { (every, n) ->
            val h = runBurst(seconds = 6.0, burstEveryMs = every, barsPerBurst = n)
            val s = h.summary()
            // 相位分母：判据实际消费的那个量（不是 harness 名义间隔）
            val interval = h.frames.drop(180).map { it.intervalMs }.average()
            println(
                "%d 根/%dms：相位分母=%.2fms 均步=%.4f 格 最小=%.2f× 最大=%.2f× 抖动=%.2f%% 停顿=%d 倒退=%d"
                    .format(n, every.toInt(), interval, s.meanStep, s.minStepRatio, s.maxStepRatio,
                        s.jitterRatio * 100, s.stallCount, s.backsteps),
            )
        }
    }

}
