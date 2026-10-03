/*
 * Ncrust —— ncm 第三方客户端
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
 * v2.8.0 · P1-A：帧时间自动降级的判据单测。
 *
 * 这一层的全部风险是**误判**与**无界**：
 *  - 单帧超标就降级 ⇒ 用户只是滑了一下列表就被降档；
 *  - 不设"只降一次" ⇒ 反复降级、观感在两次启动之间来回跳；
 *  - 判定后不注销 ⇒ 每帧都白跑一遍判定。
 * 三条都在这里逐条钉住（配合 `VisualizerTierTest` 的降级有界性用例）。
 */
class FrameBudgetPolicyTest {

    private val budget = FrameBudgetPolicy.FRAME_BUDGET_NS
    private val window = FrameBudgetPolicy.WINDOW_FRAMES
    private val threshold = FrameBudgetPolicy.OVER_BUDGET_FRAMES

    /** 常量本身也是契约：它们决定了"多少算持续超标"，改它们必须连着改文档与这里的断言。 */
    @Test
    fun `判据常量有明确依据且被钉住`() {
        assertEquals(16_666_667L, FrameBudgetPolicy.FRAME_BUDGET_NS) // 60Hz 一个 vsync
        assertEquals(60, FrameBudgetPolicy.WINDOW_FRAMES)            // ≈1 秒
        assertEquals(24, FrameBudgetPolicy.OVER_BUDGET_FRAMES)       // 40%
    }

    @Test
    fun `单帧超标不降级`() {
        val policy = FrameBudgetPolicy()
        assertFalse(policy.onFrame(budget * 5))
        // 其余全部正常
        repeat(window - 1) { assertFalse(policy.onFrame(budget / 2)) }
        assertFalse("窗口内 1/60 超标远达不到 40%", policy.hasDecided())
    }

    @Test
    fun `窗口没填满之前不结算`() {
        val policy = FrameBudgetPolicy()
        repeat(window - 1) { assertFalse(policy.onFrame(budget * 3)) }
        assertEquals(window - 1, policy.overBudgetCount())
        assertFalse("还差一帧就不该判定", policy.hasDecided())
        assertTrue("第 60 帧到齐且全部超标 ⇒ 判定", policy.onFrame(budget * 3))
        assertTrue(policy.hasDecided())
    }

    @Test
    fun `持续超标判定一次之后永远不再判定`() {
        val policy = FrameBudgetPolicy()
        val first = (1..window).map { policy.onFrame(budget * 2) }
        assertTrue("第一次窗口就该判定", first.last())
        assertEquals("只允许判定一次", 1, first.count { it })
        // 之后继续送更糟的帧：一律不再返回 true
        repeat(window * 3) { assertFalse(policy.onFrame(budget * 10)) }
        assertTrue(policy.hasDecided())
    }

    @Test
    fun `刚好达到门槛即判定，差一帧则不判定`() {
        val atThreshold = FrameBudgetPolicy()
        repeat(threshold) { atThreshold.onFrame(budget * 2) }
        repeat(window - threshold) { atThreshold.onFrame(budget / 2) }
        assertTrue("超标帧数 == 门槛就该判定", atThreshold.hasDecided())

        val belowThreshold = FrameBudgetPolicy()
        repeat(threshold - 1) { belowThreshold.onFrame(budget * 2) }
        repeat(window - threshold + 1) { belowThreshold.onFrame(budget / 2) }
        assertFalse("差一帧就不该判定", belowThreshold.hasDecided())
    }

    /** 滑动窗口：第一窗口不达标不判定，第二窗口（持续超标）仍然能判定 —— 不是"一次性机会"。 */
    @Test
    fun `偶发超标不判定，但后续窗口仍会重新评估`() {
        val policy = FrameBudgetPolicy()
        // 窗口内 19/60 超标（< 24 门槛）⇒ 不判定
        val occasional = threshold - 5
        repeat(occasional) { policy.onFrame(budget * 2) }
        repeat(window - occasional) { policy.onFrame(budget / 2) }
        assertFalse(policy.hasDecided())
        assertEquals(occasional, policy.overBudgetCount())
        // 之后持续超标：滑窗内超标帧数爬到门槛就该判定（不需要再等满一整个窗口）
        var triggered = false
        repeat(window) { if (policy.onFrame(budget * 2)) triggered = true }
        assertTrue(triggered)
    }

    /** 边界：恰好等于预算算**超标**（"用满一个 vsync"已经没有余量给下一帧了）。 */
    @Test
    fun `恰好等于预算算超标`() {
        val policy = FrameBudgetPolicy()
        repeat(window) { policy.onFrame(budget) }
        assertTrue(policy.hasDecided())
    }

    /** 平台偶尔给出 0 / -1（读不到）：不计入窗口、不推进游标，也不判定。 */
    @Test
    fun `非正时长视为没有数据并被忽略`() {
        val policy = FrameBudgetPolicy()
        repeat(window * 2) { assertFalse(policy.onFrame(0L)) }
        repeat(window * 2) { assertFalse(policy.onFrame(-1L)) }
        assertFalse(policy.hasDecided())
        assertEquals(0, policy.overBudgetCount())
        // 真实数据照常参与判定
        repeat(window) { policy.onFrame(budget * 2) }
        assertTrue(policy.hasDecided())
    }

    /** 自定义参数（测试/将来调参）也必须遵守同一条有界契约。 */
    @Test
    fun `自定义窗口与门槛同样有界`() {
        val policy = FrameBudgetPolicy(windowSize = 10, budgetNs = 1_000L, overBudgetThreshold = 3)
        val results = (1..10).map { policy.onFrame(5_000L) }
        assertEquals(1, results.count { it })
        assertFalse(policy.onFrame(5_000L))
        // 窗口内正常帧占多数 ⇒ 不判定
        val healthy = FrameBudgetPolicy(windowSize = 10, budgetNs = 1_000L, overBudgetThreshold = 3)
        repeat(10) { healthy.onFrame(500L) }
        assertFalse(healthy.hasDecided())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非法窗口长度直接拒绝`() {
        FrameBudgetPolicy(windowSize = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `门槛大于窗口直接拒绝`() {
        FrameBudgetPolicy(windowSize = 10, overBudgetThreshold = 11)
    }
}
