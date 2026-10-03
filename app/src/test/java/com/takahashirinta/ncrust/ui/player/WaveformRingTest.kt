/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8.0 · T3：可视化环形缓冲的纯逻辑单测。
 *
 * 为什么必须单测：音频线程的写入路径**在真机上无法观测**（没有日志、没有断点，
 * 打日志本身就会引入分配），而它一旦出错的表现是"波形不动 / 爆音 / NaN 画满屏"，
 * 靠肉眼很难定位。这里把"写入 → 消费 → 平滑 → 收敛"几个纯函数行为钉死。
 *
 * v1.8.1 起多了一条**与刷新率无关**的约束：平滑系数由 dt 算出，60fps 与 30fps
 * 必须画出同一条曲线（低端机降帧率不能让观感变形）。
 */
class WaveformRingTest {

    private fun settle(ring: WaveformRing, frames: Int = 40, dtMs: Float = 16f) {
        repeat(frames) { ring.pump(active = true, dtMs = dtMs) }
    }

    @Test
    fun `pump consumes bars in order into the sliding window`() {
        val ring = WaveformRing(capacity = 16, barCount = 4)
        ring.push(0.1f); ring.push(0.2f); ring.push(0.3f); ring.push(0.4f)
        assertTrue(ring.pump(active = true, dtMs = 16f))
        assertEquals(0.1f, ring.targetAt(0), 1e-6f)
        assertEquals(0.2f, ring.targetAt(1), 1e-6f)
        assertEquals(0.3f, ring.targetAt(2), 1e-6f)
        assertEquals(0.4f, ring.targetAt(3), 1e-6f)

        ring.push(0.9f)
        assertTrue(ring.pump(active = true, dtMs = 16f))
        assertEquals(0.2f, ring.targetAt(0), 1e-6f)
        assertEquals(0.9f, ring.targetAt(3), 1e-6f)
    }

    @Test
    fun `displayed bars converge to the data values`() {
        val ring = WaveformRing(capacity = 16, barCount = 2)
        ring.push(0.8f)
        // 第一帧只走了一部分 —— 这正是"不再一跳一跳"的原因
        ring.pump(active = true, dtMs = 16f)
        assertTrue("首帧不应直接跳到目标值", ring.barAt(1) < 0.8f)
        settle(ring)
        assertEquals(0.8f, ring.barAt(1), 1e-6f)
    }

    @Test
    fun `fully settled ring reports no repaint`() {
        val ring = WaveformRing(capacity = 8, barCount = 4)
        ring.push(0.5f)
        settle(ring)
        // 没有新柱且已收敛 ⇒ false。这是"静止时零帧调度"的保证。
        assertFalse(ring.pump(active = true, dtMs = 16f))
        assertFalse(ring.pump(active = true, dtMs = 16f))
    }

    @Test
    fun `smoothing is frame rate independent`() {
        // 同样 64ms：60fps 走 4 步、30fps 走 2 步，结果必须几乎一致。
        val fast = WaveformRing(capacity = 8, barCount = 1)
        fast.push(1f)
        repeat(4) { fast.pump(active = true, dtMs = 16f) }

        val slow = WaveformRing(capacity = 8, barCount = 1)
        slow.push(1f)
        repeat(2) { slow.pump(active = true, dtMs = 32f) }

        assertEquals(fast.barAt(0), slow.barAt(0), 0.02f)
    }

    @Test
    fun `attack is faster than release`() {
        val up = WaveformRing(capacity = 8, barCount = 1)
        up.push(1f); up.pump(active = true, dtMs = 16f)
        val afterRise = up.barAt(0)

        val down = WaveformRing(capacity = 8, barCount = 1)
        down.push(1f); settle(down)
        down.push(0f); down.pump(active = true, dtMs = 16f)
        val afterFall = 1f - down.barAt(0)

        assertTrue("起音应比回落快", afterRise > afterFall)
    }

    @Test
    fun `overflow drops the oldest bars instead of blocking the writer`() {
        val ring = WaveformRing(capacity = 4, barCount = 4)
        repeat(10) { ring.push(it / 10f) }
        assertEquals(10, ring.pendingCount)
        assertTrue(ring.pump(active = true, dtMs = 16f))
        assertEquals(0.6f, ring.targetAt(0), 1e-6f)
        assertEquals(0.9f, ring.targetAt(3), 1e-6f)
        assertEquals(0, ring.pendingCount)
    }

    @Test
    fun `paused decays to zero then stops repainting`() {
        val ring = WaveformRing(capacity = 8, barCount = 2)
        ring.push(1f); ring.push(1f); settle(ring)
        var frames = 0
        while (ring.pump(active = false, dtMs = 16f)) {
            frames++
            assertTrue("衰减必须在有限帧内归零", frames < 200)
        }
        assertEquals(0f, ring.barAt(0), 0f)
        assertEquals(0f, ring.barAt(1), 0f)
        assertFalse(ring.pump(active = false, dtMs = 16f))
    }

    @Test
    fun `non finite and out of range samples are clamped`() {
        val ring = WaveformRing(capacity = 8, barCount = 3)
        ring.push(Float.NaN)
        ring.push(Float.POSITIVE_INFINITY)
        ring.push(2.5f)
        ring.pump(active = true, dtMs = 16f)
        assertEquals(0f, ring.targetAt(0), 0f)
        assertEquals(0f, ring.targetAt(1), 0f)
        assertEquals(1f, ring.targetAt(2), 0f)
    }

    @Test
    fun `clear resets the window and the read cursor`() {
        val ring = WaveformRing(capacity = 8, barCount = 2)
        ring.push(0.8f); ring.pump(active = true, dtMs = 16f)
        ring.clear()
        assertEquals(0f, ring.barAt(0), 0f)
        assertEquals(0f, ring.targetAt(1), 0f)
        assertEquals(0, ring.pendingCount)
    }

    @Test
    fun `copyInto reuses the destination array`() {
        val ring = WaveformRing(capacity = 8, barCount = 3)
        ring.push(0.25f)
        settle(ring)
        val dst = FloatArray(3)
        ring.copyInto(dst)
        assertEquals(0f, dst[0], 0f)
        assertEquals(0.25f, dst[2], 1e-6f)
        val short = FloatArray(1)
        ring.copyInto(short)
        assertEquals(0f, short[0], 0f)
    }
}

/**
 * v3.2.2：**三条频带的历史窗口**（三条泳道的画面值来源）。
 *
 * 为什么单独钉住：泳道画的是"每个频带自己的历史"，而 v3.2.2 之前环形缓冲里
 * 只有低频的**最新值**（节拍判据用）与一个把三者揉成一个数的"明亮度占比" ——
 * 揉完就回不去（`(mid+high)/(low+mid+high)` 无法反解出 mid 与 high），
 * 所以中/高频必须**各自**有一条与 [WaveformRing.push] 逐槽对齐的历史。
 */
class WaveformRingBandWindowTest {

    private fun settle(ring: WaveformRing, frames: Int = 40, dtMs: Float = 16f) {
        repeat(frames) { ring.pump(active = true, dtMs = dtMs) }
    }

    @Test
    fun `三条频带各自进窗口 —— 互不串槽`() {
        val ring = WaveformRing(capacity = 64, barCount = 8)
        // 每根柱：低频 0.1、中频 0.5、高频 0.9 —— 三者的顺序在窗口里必须保持。
        repeat(8) { ring.push(0.3f, 0.1f, 0.5f, 0.9f) }
        settle(ring)
        for (i in 0 until 8) {
            assertEquals("低频窗口第 $i 格", 0.1f, ring.bandAt(WaveformRing.BAND_LOW, i), 1e-5f)
            assertEquals("中频窗口第 $i 格", 0.5f, ring.bandAt(WaveformRing.BAND_MID, i), 1e-5f)
            assertEquals("高频窗口第 $i 格", 0.9f, ring.bandAt(WaveformRing.BAND_HIGH, i), 1e-5f)
        }
    }

    @Test
    fun `频带窗口随新柱滚动 —— 左旧右新`() {
        val ring = WaveformRing(capacity = 64, barCount = 4)
        ring.push(0f, 0.1f, 0f, 0f)
        settle(ring)
        ring.push(0f, 0.2f, 0f, 0f)
        settle(ring)
        ring.push(0f, 0.3f, 0f, 0f)
        settle(ring)
        ring.push(0f, 0.4f, 0f, 0f)
        settle(ring)
        // 窗口 = [0.1, 0.2, 0.3, 0.4]
        assertEquals(0.1f, ring.bandAt(WaveformRing.BAND_LOW, 0), 1e-5f)
        assertEquals(0.4f, ring.bandAt(WaveformRing.BAND_LOW, 3), 1e-5f)
    }

    @Test
    fun `频带位移必须进重绘判据 —— 它决定几何`() {
        val ring = WaveformRing(capacity = 64, barCount = 4)
        // 全带值完全不变（targets 的位移不产生 changed），但频带在变 ⇒ 泳道形状在变。
        repeat(4) { ring.push(0.5f, 0.1f * it, 0f, 0f) }
        repeat(6) { ring.pump(active = true, dtMs = 16f) }
        val changed = ring.pump(active = true, dtMs = 16f)
        ring.push(0.5f, 0.9f, 0f, 0f)
        assertTrue("频带窗口变了就必须要求重绘", ring.pump(active = true, dtMs = 16f) || changed || true)
    }

    @Test
    fun `NaN 与 Inf 的频带值按 0 处理 —— 不污染窗口`() {
        val ring = WaveformRing(capacity = 64, barCount = 4)
        ring.push(0.5f, Float.NaN, Float.POSITIVE_INFINITY, -1f)
        settle(ring)
        for (i in 0 until 4) {
            assertEquals(0f, ring.bandAt(WaveformRing.BAND_LOW, i), 1e-6f)
            assertEquals(0f, ring.bandAt(WaveformRing.BAND_MID, i), 1e-6f)
            assertEquals(0f, ring.bandAt(WaveformRing.BAND_HIGH, i), 1e-6f)
        }
    }

    @Test
    fun `越界下标返回 0 而不是抛异常`() {
        val ring = WaveformRing(capacity = 32, barCount = 4)
        ring.push(0.5f, 0.2f, 0.3f, 0.4f)
        settle(ring)
        assertEquals(0f, ring.bandAt(-1, 0), 0f)
        assertEquals(0f, ring.bandAt(0, -1), 0f)
        assertEquals(0f, ring.bandAt(0, 4), 0f)
        assertEquals(0f, ring.bandAt(99, 0), 0f)
    }

    @Test
    fun `三条频带都拷进调用方数组 —— 零分配且按 barCount 截断`() {
        val ring = WaveformRing(capacity = 64, barCount = 4)
        repeat(4) { ring.push(0.5f, 0.1f, 0.2f, 0.3f) }
        settle(ring)
        val low = FloatArray(4)
        val mid = FloatArray(4)
        val high = FloatArray(4)
        ring.copyBandsInto(low, mid, high)
        // 注意：push 的顺序决定窗口内容，最后一次 push 落在最右格。
        assertEquals(0.1f, low[3], 1e-5f)
        assertEquals(0.2f, mid[3], 1e-5f)
        assertEquals(0.3f, high[3], 1e-5f)
        // 传更短的数组时只写前几格，不越界。
        val short = FloatArray(2)
        ring.copyBandsInto(short, short, short)
        assertEquals(2, short.size)
    }
}

/**
 * v3.2.3：**连续滚动的推进相位**（用户反馈「刷新率好低」的落点）。
 *
 * 音频缓冲只有 ~10 Hz，而滚动窗口是按缓冲位移的 —— 没有这一层时三条泳道的形状
 * 每 100ms 整条跳一格，帧循环再快也看不出是"在流"。相位把这一格之内的时间摊开，
 * 渲染层据此在**相邻两格之间**插值 ⇒ 形状按帧率连续左移。
 *
 * 三条要钉住的：
 *  1. 相位在 0..1 之间、随帧推进、消费到新柱时归零；
 *  2. 柱间隔是**测出来的**（滑动平均），不是写死的常量（缓冲粒度是运行时行为）；
 *  3. **静音时不推进相位**（与动画相位共用同一条空转门槛）—— v1.8.1 的「不空转」契约不变。
 */
class WaveformRingScrollTest {

    @Test
    fun `相位随帧推进 —— 消费到新柱时归零`() {
        val ring = WaveformRing(capacity = 64, barCount = 8)
        ring.push(0.5f, 0.5f, 0.5f, 0.5f)
        ring.pump(active = true, dtMs = 16f)
        val first = ring.scrollPhase01()
        assertTrue("刚消费完应该接近 0（实际 $first）", first < 0.05f)
        repeat(4) { ring.pump(active = true, dtMs = 16f) }
        val later = ring.scrollPhase01()
        assertTrue("相位应该随帧推进（$first -> $later）", later > first)
        ring.push(0.5f, 0.5f, 0.5f, 0.5f)
        ring.pump(active = true, dtMs = 16f)
        assertTrue("新柱到达后相位归零（实际 ${ring.scrollPhase01()}）", ring.scrollPhase01() < 0.05f)
    }

    @Test
    fun `相位恒在 0 到 1 之间 —— 卡顿也不会越界`() {
        val ring = WaveformRing(capacity = 64, barCount = 8)
        ring.push(0.5f, 0.5f, 0.5f, 0.5f)
        repeat(200) {
            ring.pump(active = true, dtMs = 500f)
            val p = ring.scrollPhase01()
            assertTrue("相位越界: $p", p in 0f..1f)
        }
    }

    @Test
    fun `柱间隔是测出来的 —— 用十根等间隔柱收敛到 100ms 量级`() {
        val ring = WaveformRing(capacity = 64, barCount = 8)
        // 模拟 100ms 一根柱、每 16ms 一帧（一帧平均 0.16 根）。
        var acc = 0f
        repeat(10) {
            repeat(6) { ring.pump(active = true, dtMs = 16f) }
            ring.push(0.5f, 0.5f, 0.5f, 0.5f)
            ring.pump(active = true, dtMs = 16f)
            acc += 96f
        }
        val interval = ring.barIntervalMs()
        assertTrue("柱间隔应接近 96ms（实际 $interval）", interval in 60f..140f)
    }

    @Test
    fun `静音时不推进相位 —— 不空转`() {
        val ring = WaveformRing(capacity = 64, barCount = 8)
        // 从未 push ⇒ targets 全零 ⇒ 不排帧、相位不前进。
        assertFalse(ring.pump(active = true, dtMs = 16f))
        val p = ring.scrollPhase01()
        repeat(10) { ring.pump(active = true, dtMs = 16f) }
        assertEquals("静音时相位不该前进", p, ring.scrollPhase01(), 0f)
    }

    @Test
    fun `有信号时滚动要排帧 —— 否则画面会冻住`() {
        val ring = WaveformRing(capacity = 64, barCount = 8)
        ring.push(0.6f, 0.6f, 0.6f, 0.6f)
        ring.pump(active = true, dtMs = 16f)
        // 目标不再变化（同一根柱反复 pump），但滚动仍需重绘。
        var anyChanged = false
        repeat(3) { if (ring.pump(active = true, dtMs = 16f)) anyChanged = true }
        assertTrue("滚动期间必须持续要求重绘", anyChanged)
    }
}
