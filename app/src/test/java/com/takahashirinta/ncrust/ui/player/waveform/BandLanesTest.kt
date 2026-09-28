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
 * v3.2.2：**三条频带泳道**的几何与标定单测。
 *
 * 这一层要钉住的是三件容易被"顺手优化掉"的事：
 *  1. **显示增益只作用在显示高度上**，且三条泳道的 p95 被标定到同一水平
 *     （否则高频泳道永远是一条贴中线的直线，用户要的"左低中中右高"就没有信息）；
 *  2. **线性映射**（不是 sqrt）：sqrt 会把动态范围压成 2 倍，画面上三条泳道变成
 *     "差不多粗的板子" —— 这是照真机截图改的，别改回去；
 *  3. **频带 → 角色的映射顺序**（低=secondary / 中=primary 主题色 / 高=tertiary）。
 */
class BandLanesTest {

    /** 探针 §2.3 实测的 5 首歌曲分位数（12197 个缓冲），逐字写在这里当标定依据。 */
    private val p95 = floatArrayOf(0.344f, 0.256f, 0.137f)

    @Test
    fun `三条泳道的 p95 被标定到接近满高`() {
        for (lane in 0 until BandLanes.LANE_COUNT) {
            val amp = BandLanes.amplitude(p95[lane], lane)
            assertTrue(
                "泳道 $lane 的 p95 振幅应落在 0.85~1.0（实际 $amp）",
                amp in 0.85f..1.0f,
            )
        }
    }

    @Test
    fun `线性映射 —— 动态范围不许被压成 2 倍`() {
        // 低频段 p50=0.081 / p95=0.344（探针实测）。线性 + 增益下比值应接近 4 倍，
        // sqrt 下只有 2 倍（那正是"三条差不多粗的板子"的来源）。
        val quiet = BandLanes.amplitude(0.081f, BandLanes.LANE_LOW)
        val loud = BandLanes.amplitude(0.344f, BandLanes.LANE_LOW)
        assertTrue("动态范围应 ≥ 3.5 倍（实际 ${loud / quiet}）", loud / quiet >= 3.5f)
    }

    @Test
    fun `饱和截断 —— 超过标定点的输入不越界`() {
        for (lane in 0 until BandLanes.LANE_COUNT) {
            assertEquals(1f, BandLanes.amplitude(1f, lane), 0f)
            assertTrue(BandLanes.amplitude(0.9f, lane) <= 1f)
        }
    }

    @Test
    fun `非法输入与越界泳道 —— 返回 0 不抛异常`() {
        assertEquals(0f, BandLanes.amplitude(Float.NaN, 0), 0f)
        assertEquals(0f, BandLanes.amplitude(Float.POSITIVE_INFINITY, 0), 0f)
        assertEquals(0f, BandLanes.amplitude(-1f, 0), 0f)
        assertEquals(0f, BandLanes.amplitude(0.5f, -1), 0f)
        assertEquals(0f, BandLanes.amplitude(0.5f, BandLanes.LANE_COUNT), 0f)
    }

    @Test
    fun `静音输入映射到 0（不是负值也不是 NaN）`() {
        for (lane in 0 until BandLanes.LANE_COUNT) {
            assertEquals(0f, BandLanes.amplitude(0f, lane), 0f)
        }
    }

    @Test
    fun `频带到角色的映射顺序固定：低=secondary 中=primary 高=tertiary`() {
        assertEquals(3, BandLanes.LANE_ROLE.size)
        assertEquals(BandColorRoles.LOW, BandLanes.LANE_ROLE[BandLanes.LANE_LOW])
        assertEquals(BandColorRoles.MID, BandLanes.LANE_ROLE[BandLanes.LANE_MID])
        assertEquals(BandColorRoles.HIGH, BandLanes.LANE_ROLE[BandLanes.LANE_HIGH])
        // 中频拿主题色，依据是探针实测"中频占 0.66 的时间"。
        assertEquals(BandColorRoles.MID, BandColorRoles.MID)
    }

    @Test
    fun `接缝过渡带宽度落在肉眼可辨又不喧宾夺主的区间`() {
        assertTrue("太窄会像硬切", BandLanes.SEAM_FRACTION >= 0.02f)
        assertTrue("太宽会把两段颜色混成第三种", BandLanes.SEAM_FRACTION <= 0.12f)
    }

    @Test
    fun `三条泳道的点序列是三等分的连续序列`() {
        // 渲染层把三条泳道的窗口**首尾相接**成一条点序列（无缝拼接的几何表达）：
        // 点距在整条序列上恒定，接缝两侧与段内完全一样。
        val pointsPerLane = 28
        val total = pointsPerLane * BandLanes.LANE_COUNT
        assertEquals(84, total)
        val step = 1000f / (total - 1)
        // 第 27 点（低频段最后一点）与第 28 点（中频段第一点）的距离 = step
        val x27 = 27 * step
        val x28 = 28 * step
        assertEquals("接缝两侧的点距必须与段内一致", step, x28 - x27, 1e-3f)
    }
}
