/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.8.0 · J13：曲线点数预算必须覆盖**生产绘制点数**（28 柱 × 3 泳道 + 1 延伸点）。
 */

package com.takahashirinta.ncrust.ui.player.waveform

import com.takahashirinta.ncrust.ui.player.WaveformStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J13 的回归契约：`WaveformCurve` 的点数预算 **≥ 生产实际绘制点数**。
 *
 * ## 缺陷本体
 *
 * `AudioVisualizerBars` 的生产点数不是 28，而是
 *
 * ```
 * 28 柱 × 3 泳道 + 1（右边缘延伸点）= 85
 * ```
 *
 * 而 `WaveformCurve.MAX_POINTS` 当时是 **64**，`computeTangents` 里的
 * `minOf(count, y.size, out.size, MAX_POINTS)` 于是把 85 **静默夹到 64**：
 *
 * - 第 64..84 点的切线根本没算（清零循环也只跑到 63 ⇒ 数组里是**上一帧**的旧值）；
 * - 第 63 点被当成整条曲线的终点，用端点公式 ⇒ 缺了对第 64 点的保号/单调保护；
 * - 绘制层却按**完整点数**画（它只按 `count` 与数组长度夹）。
 *
 * 探针在 61–64 点给合法归一化高度 `0.1 / 0.4 / 1.0 / 0.1`（边界切线为正、下一段下降），
 * 采样峰值 **1.0243**（越过数据上包络 1.0），`staysWithinEndpoints` 返回 false。
 */
class WaveformCurvePointBudgetTest {

    /** 生产点数（与 `AudioVisualizerBars` 的 `geomCount` 同源，见它的注释）。 */
    private val productionPoints =
        WaveformStore.BAR_COUNT * BandLanes.LANE_COUNT + BandScroll.EXTRA_POINTS

    @Test
    fun `曲线点数预算必须覆盖三泳道的生产点数`() {
        assertEquals("生产点数 = 28 × 3 + 1", 85, productionPoints)
        assertTrue(
            "MAX_POINTS=${WaveformCurve.MAX_POINTS} 小于生产点数 $productionPoints ⇒ " +
                "切线会被静默夹断、最后几段用上一帧的旧值画（J13）",
            WaveformCurve.MAX_POINTS >= productionPoints,
        )
    }

    @Test
    fun `第 61 至 64 点的转折不得越界 —— 旧上限恰好把第 63 点当终点`() {
        val dx = 8f
        // 前段平缓、第 63 点是一个尖峰、第 64 点立刻掉下来：这正是让「端点公式」出事的形状。
        val y = FloatArray(productionPoints) { 0.2f }
        y[60] = 0.1f
        y[61] = 0.4f
        y[62] = 0.7f
        y[63] = 1.0f
        y[64] = 0.1f
        for (i in 65 until productionPoints) y[i] = 0.3f

        val tangents = FloatArray(productionPoints)
        WaveformCurve.computeTangents(y, productionPoints, dx, tangents)

        for (i in 0 until productionPoints - 1) {
            assertTrue(
                "第 ${i}→${i + 1} 段越界：y=${y[i]}/${y[i + 1]} 切线=${tangents[i]}/${tangents[i + 1]}" +
                    "（旧上限 64 时第 63→64 段正是这条）",
                WaveformCurve.staysWithinEndpoints(y[i], y[i + 1], tangents[i], tangents[i + 1], dx),
            )
        }
    }

    @Test
    fun `最后一个点的切线必须是端点值而不是零 —— 说明它真的被算过`() {
        val dx = 4f
        // 全程单调上升：内部点的切线为正，端点（单侧差分）同样为正。
        val y = FloatArray(productionPoints) { 0.01f * it.toFloat() }
        val tangents = FloatArray(productionPoints)
        WaveformCurve.computeTangents(y, productionPoints, dx, tangents)

        val lastSlope = (y[productionPoints - 1] - y[productionPoints - 2]) / dx
        assertTrue("末点切线必须与末段斜率同号（被夹断的槽位只会是 0）", tangents[productionPoints - 1] > 0f)
        assertTrue(
            "末点切线不得超过末段斜率的 3 倍（端点公式的硬上界）",
            tangents[productionPoints - 1] <= 3f * lastSlope + 1e-5f,
        )
    }

    @Test
    fun `容量不足时退化成折线而不是留上一帧的脏切线`() {
        val count = 8
        val y = FloatArray(count) { it.toFloat() }
        val tangents = FloatArray(count) { 123f }   // 模拟上一帧的残留
        WaveformCurve.computeTangents(y, count, 1f, tangents)
        // 全部被本帧重算过 ⇒ 不可能还是 123f
        assertTrue("切线数组必须被本帧整体覆盖", tangents.none { it == 123f })
    }
}
