/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.4 · P1：帧率适配（60 / 90 / 120 / 144Hz）与帧步长（铁律 33）。
 */

package com.takahashirinta.ncrust.ui.player.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 铁律 33：**帧率适配必须显式处理设备刷新率（60 / 90 / 120Hz），不得假设固定帧率。**
 *
 * 这个文件把四档刷新率上的判据全部钉死。判据是**帧步长**（每几帧推进一次）而不是时间阈值：
 *
 * | 面板 | 帧间隔 | 非低内存：步长 / 实际帧率 | 低内存：步长 / 实际帧率 |
 * |---|---|---|---|
 * | 60Hz | 16.67ms | 1 / **60fps** | 2 / 30fps |
 * | 90Hz | 11.11ms | 1 / **90fps** | 3 / 30fps |
 * | 120Hz | 8.33ms | 1 / **120fps** | 4 / 30fps |
 * | 144Hz | 6.94ms | 1 / **144fps** | 5 / 28.8fps |
 *
 * ⚠️ v3.2.3 的旧判据（时间阈值 16ms）在 120Hz 上是「2 帧一次、余量 0.667ms」，
 * 一抖就变 3 帧（25ms）⇒ 位移 ±49% ⇒ 用户报的「抖」。
 * 根因与仿真见 `docs/verification/v3.2.4/probe-waveform-jitter-hfr.md`。
 */
class DisplayRefreshTest {

    private val rates = listOf(60f, 90f, 120f, 144f)

    @Test
    fun `四档刷新率的帧间隔就是 1000 除以刷新率`() {
        assertEquals(16.667f, DisplayRefresh.frameIntervalMs(60f), 0.01f)
        assertEquals(11.111f, DisplayRefresh.frameIntervalMs(90f), 0.01f)
        assertEquals(8.333f, DisplayRefresh.frameIntervalMs(120f), 0.01f)
        assertEquals(6.944f, DisplayRefresh.frameIntervalMs(144f), 0.01f)
    }

    @Test
    fun `非低内存设备在四档刷新率上都是每帧推进 —— 节拍器就是 vsync`() {
        rates.forEach { hz ->
            assertEquals("${hz}Hz 必须每帧推进（否则就是 v3.2.3 的量化缺陷）", 1, DisplayRefresh.strideFor(hz, lowTier = false))
        }
    }

    @Test
    fun `低内存设备在四档刷新率上都被节流到约 30fps`() {
        assertEquals(2, DisplayRefresh.strideFor(60f, lowTier = true))
        assertEquals(3, DisplayRefresh.strideFor(90f, lowTier = true))
        assertEquals(4, DisplayRefresh.strideFor(120f, lowTier = true))
        assertEquals(5, DisplayRefresh.strideFor(144f, lowTier = true))
        rates.forEach { hz ->
            val fps = 1000f / DisplayRefresh.pacedIntervalMs(hz, lowTier = true)
            assertTrue(
                "低内存设备在 ${hz}Hz 上应落回 27~34fps，实测 ${fps}fps",
                fps in 27f..34f,
            )
        }
    }

    @Test
    fun `低内存设备的节流间隔绝不短于 33ms 这条历史契约`() {
        rates.forEach { hz ->
            val paced = DisplayRefresh.pacedIntervalMs(hz, lowTier = true)
            assertTrue("${hz}Hz 的节流间隔 $paced 短于 33ms", paced >= DisplayRefresh.LOW_TIER_BUDGET_MS)
        }
    }

    @Test
    fun `非法刷新率一律回落到 60Hz 而不是猜一个`() {
        val bad = listOf(0f, -1f, 10f, 1000f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        bad.forEach { hz ->
            assertEquals("$hz 应回落", DisplayRefresh.FALLBACK_HZ, DisplayRefresh.sanitizeHz(hz), 0.001f)
            assertEquals(DisplayRefresh.FALLBACK_HZ, 1000f / DisplayRefresh.frameIntervalMs(hz), 0.001f)
        }
        assertEquals(20f, DisplayRefresh.sanitizeHz(20f), 0.001f)
        assertEquals(240f, DisplayRefresh.sanitizeHz(240f), 0.001f)
    }

    @Test
    fun `帧间隔恒为正 —— 0 会让 delay 与除法都出事`() {
        listOf(0f, -0f, Float.NaN, 0.0001f).forEach { hz ->
            assertTrue(DisplayRefresh.frameIntervalMs(hz) > 0f)
        }
    }
}

/**
 * [FrameStride] 是帧循环里那个「数帧」的计数器。它是**唯一**决定「这一帧推不推进」的东西，
 * 所以必须能被 JVM 直接测 —— 而不是靠读一段落在 `LaunchedEffect` 里的内联代码。
 */
class FrameStrideTest {

    @Test
    fun `步长为 1 时每一帧都推进`() {
        val stride = FrameStride(1)
        repeat(100) { assertTrue(stride.shouldAdvance()) }
    }

    @Test
    fun `步长为 4 时每 4 帧推进一次且长期稳定`() {
        val stride = FrameStride(4)
        val advanced = (1..120).count { stride.shouldAdvance() }
        assertEquals(30, advanced)
    }

    @Test
    fun `步长非法（0 或负数）按 1 处理 —— 绝不出现永远不推进`() {
        listOf(0, -3).forEach { bad ->
            val stride = FrameStride(bad)
            assertEquals(1, stride.effectiveStride)
            repeat(10) { assertTrue(stride.shouldAdvance()) }
        }
    }

    @Test
    fun `reset 之后重新计数 —— 暂停恢复不会提前或延后一帧`() {
        val stride = FrameStride(3)
        assertFalse(stride.shouldAdvance())
        assertFalse(stride.shouldAdvance())
        stride.reset()
        assertFalse(stride.shouldAdvance())
        assertFalse(stride.shouldAdvance())
        assertTrue(stride.shouldAdvance())
    }
}
