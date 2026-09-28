/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.2：主导频段判定与防闪烁的**纯逻辑单测**。
 *
 * 为什么这些用例必须存在：三件在真机上"看起来一样"的事在这里被钉死 ——
 *  1. **滞回到底有没有生效**（观感上"颜色还是抖"和"阈值没生效"肉眼分不出）；
 *  2. **过渡会不会被下一次切换打断**（打断会制造一次性 pop，探针 §2.4 实测 ΔE 8.13）；
 *  3. **特征不可用时有没有真的退回单色**（降级与"真的是低频"在画面上同形）。
 */
class BandDominanceTest {

    private val weights = FloatArray(3)

    /** 推进 [ms] 毫秒，每帧 16ms（60fps），返回切换次数。 */
    private fun run(
        band: BandDominance,
        low: Float,
        mid: Float,
        high: Float,
        ms: Int,
        dt: Float = 16f,
    ): Int {
        var switches = 0
        var previous = band.dominant
        var elapsed = 0f
        while (elapsed < ms) {
            band.update(low, mid, high, available = true, dtMs = dt)
            if (band.dominant != previous) {
                if (previous != BandDominance.NONE) switches++
                previous = band.dominant
            }
            elapsed += dt
        }
        return switches
    }

    @Test
    fun `起播时没有结论 权重要求调用方退回单色`() {
        val band = BandDominance()
        assertFalse(band.isReliable())
        assertFalse(band.weights(weights))
        assertEquals(0f, weights[0], 0f)
        assertEquals(0f, weights[1], 0f)
        assertEquals(0f, weights[2], 0f)
    }

    @Test
    fun `特征不可用时不产生任何结论 且会把已有结论清掉`() {
        val band = BandDominance()
        run(band, low = 0.5f, mid = 0.1f, high = 0.1f, ms = 800)
        assertTrue(band.isReliable())
        assertEquals(BandDominance.LOW, band.dominant)
        band.update(0f, 0f, 0f, available = false, dtMs = 16f)
        assertFalse(band.isReliable())
        assertFalse(band.weights(weights))
        assertEquals(BandDominance.NONE, band.dominant)
    }

    @Test
    fun `滞回 20% —— 挑战者只高 10% 时不得切换`() {
        val band = BandDominance()
        run(band, low = 0.50f, mid = 0.10f, high = 0.10f, ms = 1500)
        assertEquals(BandDominance.LOW, band.dominant)
        // 中频只比低频高 10%，低于 20% 的滞回线 ⇒ 保持低频。
        val switches = run(band, low = 0.50f, mid = 0.55f, high = 0.10f, ms = 2000)
        assertEquals(0, switches)
        assertEquals(BandDominance.LOW, band.dominant)
    }

    @Test
    fun `滞回 20% —— 挑战者高 40% 时必须切换`() {
        val band = BandDominance()
        run(band, low = 0.50f, mid = 0.10f, high = 0.10f, ms = 1500)
        val switches = run(band, low = 0.50f, mid = 0.70f, high = 0.10f, ms = 1500)
        assertEquals(1, switches)
        assertEquals(BandDominance.MID, band.dominant)
    }

    /**
     * 阈值边界。
     *
     * **一个实测到的口径问题（写在这里，避免下一个人以为阈值没生效）**：
     * 平滑是指数逼近 `target − (target−start)·e^(−t/τ)`，在 float 里它会停在
     * **目标下方约 1 ULP 的定点**上（实测跑 2500 帧后 `0.8f` 稳定在 `0.7999995f`），
     * 所以"挑战者恰好等于 1.20 倍"这个数学边界在实现里**不可观测** ——
     * 它会被那 1 ULP 的差推过界线。这不是缺陷，是浮点定点 + 严格大于号的必然结果。
     *
     * 因此边界用**两侧各留 2.5 个百分点**的值断言：
     *  +17.5%（0.94 / 0.80）不切换、+22.5%（0.98 / 0.80）切换。
     * 两侧都远离浮点定点，而区间宽度（5 个百分点）远小于滞回阈值本身（20 个百分点）。
     */
    @Test
    fun `滞回阈值边界 —— 低于 20% 不切换 高于 20% 切换`() {
        val below = BandDominance()
        run(below, low = 0.80f, mid = 0.80f, high = 0f, ms = 40_000)
        assertEquals(BandDominance.LOW, below.dominant)
        // 平滑在目标下方约 1 ULP 处停下 —— 这条断言把这个口径钉住（见上面的 KDoc）。
        assertEquals(0.8f, below.smoothAt(BandDominance.LOW), 1e-5f)
        run(below, low = 0.80f, mid = 0.94f, high = 0f, ms = 40_000)
        assertEquals("挑战者只高 17.5%（< 20%）不得切换", BandDominance.LOW, below.dominant)

        val above = BandDominance()
        run(above, low = 0.80f, mid = 0.80f, high = 0f, ms = 40_000)
        run(above, low = 0.80f, mid = 0.98f, high = 0f, ms = 40_000)
        assertEquals("挑战者高 22.5%（> 20%）必须切换", BandDominance.MID, above.dominant)
    }

    @Test
    fun `静音闸门 —— 三频带和低于阈值时保持当前频段`() {
        val band = BandDominance()
        run(band, low = 0.20f, mid = 0.05f, high = 0.02f, ms = 1200)
        assertEquals(BandDominance.LOW, band.dominant)
        // 三个值都极小（和 = 0.03 < 0.06）、且中频"最大" —— 闸门必须挡住这次切换。
        run(band, low = 0.01f, mid = 0.02f, high = 0.00f, ms = 1500)
        assertEquals(BandDominance.LOW, band.dominant)
    }

    @Test
    fun `平滑时间常数 300ms —— 阶跃后 300ms 走到约 63%`() {
        val band = BandDominance()
        band.update(1f, 0f, 0f, available = true, dtMs = 1f)
        var elapsed = 1f
        while (elapsed < 300f) {
            band.update(1f, 0f, 0f, available = true, dtMs = 1f)
            elapsed += 1f
        }
        // 1 − e^(−1) ≈ 0.632
        assertEquals(0.632f, band.smoothAt(BandDominance.LOW), 0.02f)
    }

    @Test
    fun `过渡 —— 100ms 走完且期间权重和恒为 1`() {
        val band = BandDominance()
        run(band, low = 0.50f, mid = 0.10f, high = 0.10f, ms = 1200)
        // 切到中频：平滑是渐近的，给它足够时间越过滞回线（一次 16ms 帧只走 5%）。
        var guard = 0
        while (band.dominant == BandDominance.LOW && guard < 200) {
            band.update(0.10f, 0.90f, 0.10f, available = true, dtMs = 16f)
            guard++
        }
        assertEquals(BandDominance.MID, band.dominant)
        assertTrue("切换后的那一帧必须刚开始过渡", band.transitionProgress() < 1f)
        var frames = 0
        while (band.transitionProgress() < 1f && frames < 100) {
            band.update(0.10f, 0.90f, 0.10f, available = true, dtMs = 16f)
            assertTrue("权重必须可读", band.weights(weights))
            assertEquals("权重和必须恒为 1", 1f, weights[0] + weights[1] + weights[2], 1e-4f)
            frames++
        }
        // 100ms / 16ms ≈ 7 帧
        assertTrue("过渡应在 100ms 左右走完，实际 $frames 帧", frames in 5..9)
    }

    @Test
    fun `过渡期间不接受新切换 —— 否则权重会跳变`() {
        val band = BandDominance()
        run(band, low = 0.90f, mid = 0.10f, high = 0.10f, ms = 1200)
        assertEquals(BandDominance.LOW, band.dominant)
        // 触发一次切换（低频 → 中频）：推进到切换发生，**再多推 2 帧**让过渡走在半路。
        var guard = 0
        while (band.dominant == BandDominance.LOW && guard < 200) {
            band.update(0.10f, 0.90f, 0.10f, available = true, dtMs = 16f)
            guard++
        }
        assertEquals(BandDominance.MID, band.dominant)
        band.update(0.10f, 0.90f, 0.10f, available = true, dtMs = 16f)
        band.update(0.10f, 0.90f, 0.10f, available = true, dtMs = 16f)
        assertTrue("此刻过渡应走在半路", band.transitionProgress() < 1f)
        // 过渡没走完时，第三个频带即使远超当前频段也不得夺走
        band.update(0.10f, 0.10f, 1.00f, available = true, dtMs = 16f)
        assertEquals("过渡期间不接受新切换", BandDominance.MID, band.dominant)
        // 过渡走完之后才允许
        run(band, low = 0.10f, mid = 0.10f, high = 1.00f, ms = 1500)
        assertEquals(BandDominance.HIGH, band.dominant)
    }

    @Test
    fun `权重可写进调用方数组 —— 长度不足时返回 false 而不是抛异常`() {
        val band = BandDominance()
        run(band, low = 0.5f, mid = 0.1f, high = 0.1f, ms = 800)
        assertFalse(band.weights(FloatArray(2)))
        val out = FloatArray(3)
        assertTrue(band.weights(out))
        assertEquals(1f, out.sum(), 1e-4f)
    }

    @Test
    fun `reset 之后回到不可信 —— 换歌不该沿用上一首的颜色`() {
        val band = BandDominance()
        run(band, low = 0.5f, mid = 0.1f, high = 0.1f, ms = 800)
        assertTrue(band.isReliable())
        assertTrue(band.reset())
        assertFalse(band.isReliable())
        assertEquals(BandDominance.NONE, band.dominant)
        assertFalse("已经清空了就不该再报 changed", band.reset())
    }

    @Test
    fun `NaN 与 Inf 不得污染状态`() {
        val band = BandDominance()
        run(band, low = 0.5f, mid = 0.1f, high = 0.1f, ms = 800)
        val beforeLow = band.smoothAt(BandDominance.LOW)
        val beforeMid = band.smoothAt(BandDominance.MID)
        band.update(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, available = true, dtMs = 16f)
        // 非法输入保持不动：归零会把颜色打回"没有低频"，clamp 到 1.0 会凭空触发切换。
        assertEquals(beforeLow, band.smoothAt(BandDominance.LOW), 0f)
        assertEquals(beforeMid, band.smoothAt(BandDominance.MID), 0f)
        assertTrue(band.weights(weights))
        for (w in weights) assertTrue("权重必须是有限值", w.isFinite())
    }

    @Test
    fun `长卡顿 —— 平滑不会一步到位 而过渡会走完（不留下半截颜色）`() {
        val band = BandDominance()
        run(band, low = 0.50f, mid = 0.10f, high = 0.10f, ms = 1200)
        val before = band.smoothAt(BandDominance.MID)
        band.update(0.10f, 0.90f, 0.10f, available = true, dtMs = 60_000f)
        // 平滑：dt 被 MAX_DT_MS(200ms) 夹住 ⇒ k = 1−e^(−200/300) ≈ 0.487，绝不会一步到 0.9。
        val after = band.smoothAt(BandDominance.MID)
        assertTrue("平滑必须被 dt 上界保护（$before -> $after）", after < 0.9f && after > before)
        // 过渡：卡顿 200ms 已经把 100ms 的过渡走完 ⇒ 不留半截颜色（那会被用户看成"卡住了"）。
        assertEquals(1f, band.transitionProgress(), 0f)
    }
}
