/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.1 · P0：拆分后的**几何回归防线**（铁律 25）。
 */

package com.takahashirinta.ncrust.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [playerCardGeometry] 的逐档断言。
 *
 * ## 为什么这一组用例是拆分的关键证据
 *
 * 拆分把「封面落点 / 分栏边界 / 可视化条高度 / 命中区让位量」全部搬进了一个纯函数
 * （`PlayerCardState.kt`）。这些量同时被**封面 overlay**、**命中测试**、**控制栏把手**
 * 三处消费 —— 拆错了不会崩，只会「封面画在这里、点击判定在那里」。
 *
 * 真机 A/B 快照（`docs/verification/v3.2.1/EQUIVALENCE.md`）覆盖的是**取景内的**节点，
 * 而遮挡 / 分层 / 首帧这些地方它看不见；纯函数的数值断言补的正是这一层。
 *
 * 密度一律取 S6 的 4.0（density 640），屏幕取 1440×2560（360×640dp）——
 * 与崩溃报告、v3.2.0 探针里的真机数值同一把尺子。
 */
class PlayerCardGeometryTest {

    private val s6 = Density(density = 4f, fontScale = 1f)
    private val widthPx = 1440f
    private val heightPx = 2560f

    /** 手机竖屏（S6 原生）：360×640dp，`smallestScreenWidthDp = 360`。 */
    private fun phone(
        statusBarPx: Float = 96f,
        wideSplit: Float = 0f,
        bigScreen: Boolean = false,
        visualizerEnabled: Boolean = true,
    ) = playerCardGeometry(
        density = s6,
        screenWidthPx = widthPx,
        screenHeightPx = heightPx,
        screenWidthDp = 360,
        screenHeightDp = 640,
        smallestScreenWidthDp = 360,
        orientationLandscape = false,
        visualizerEnabled = visualizerEnabled,
        wideSplit = wideSplit,
        bigScreenRequested = bigScreen,
        wideCoverCenter = Offset.Zero,
        wideCoverSizePx = 0f,
        statusBarPx = statusBarPx,
    )

    /** 平板（`wm size 1440x2560` + `wm density 320` ⇒ 720×1280dp，横屏时 1280×720dp）。 */
    private fun tablet(
        landscape: Boolean,
        wideSplit: Float,
        wideCoverCenter: Offset = Offset(300f, 400f),
        wideCoverSizePx: Float = 600f,
    ): PlayerCardGeometry {
        val (w, h) = if (landscape) 2560f to 1440f else 1440f to 2560f
        return playerCardGeometry(
            density = Density(2f, 1f),
            screenWidthPx = w,
            screenHeightPx = h,
            screenWidthDp = if (landscape) 1280 else 720,
            screenHeightDp = if (landscape) 720 else 1280,
            smallestScreenWidthDp = 720,
            orientationLandscape = landscape,
            visualizerEnabled = true,
            wideSplit = wideSplit,
            bigScreenRequested = false,
            wideCoverCenter = wideCoverCenter,
            wideCoverSizePx = wideCoverSizePx,
            statusBarPx = 48f,
        )
    }

    // ---------------------------------------------------------------- 手机竖屏

    @Test
    fun `手机竖屏 —— 不进宽屏分支 封面取整屏宽`() {
        val g = phone()
        assertFalse(g.isWidePlayer)
        assertFalse(g.bigScreenActive)
        assertFalse(g.usesSideCover)
        assertEquals(1f, g.wideLeftFraction)
        assertEquals(widthPx, g.coverSizePx, 1e-3f)      // 窄屏封面 = 整屏宽
        assertEquals(360f, g.coverSizeDp.value, 1e-3f)
        assertEquals(widthPx / 2f, g.largeCoverCenterX, 1e-3f)
        assertFalse("手机竖屏不挂可视化条", g.visualizerSlot)
    }

    @Test
    fun `手机竖屏 —— 封面落点与命中区让位量与 v3_2_0 的真机数值一致`() {
        val g = phone(statusBarPx = 96f)   // S6 状态栏 24dp
        // TrayLayout: COVER_START_DP 16 + COVER_SIZE_DP 56 / 2 = 44dp ⇒ 176px
        assertEquals(176f, g.miniCoverCenterX, 1e-3f)
        // statusBar + HEIGHT_DP/2 = 96 + 160 = 256px
        assertEquals(256f, g.miniCoverCenterY, 1e-3f)
        // statusBar + TOP_BAR_HEIGHT_DP/2 = 96 + 112 = 208px
        assertEquals(208f, g.topBarCoverCenterY, 1e-3f)
        // 命中区让位 = statusBar 高（96px = 24dp）
        assertEquals(24f, g.hitGateInsetDp.value, 1e-3f)
        // 顶栏下沿 = statusBar + 56dp = 96 + 224 = 320px
        assertEquals(320f, g.topBarBottomPx, 1e-3f)
        // 封面缩放比 = 小封面边长 / 大封面边长
        // 小封面边长 56dp = 224px（coverHalfDp × 2）÷ 大封面边长
        assertEquals(224f / widthPx, g.miniScale, 1e-6f)
        // screenHeightPx*0.3 + 24dp = 768 + 96 = 864px（窄屏大封面的中心落点）
        assertEquals(864f, g.largeCoverCenterY, 1e-3f)
    }

    // ---------------------------------------------------------------- 平板宽屏

    @Test
    fun `平板横屏 —— 走宽屏两栏 wideSplit 从 0 到 1`() {
        val closed = tablet(landscape = true, wideSplit = 0f)
        assertTrue(closed.isWidePlayer)
        assertEquals(1f, closed.wideLeftFraction, 1e-6f)
        assertEquals(2560f, closed.panelBoundaryPx, 1e-3f)   // 单栏：边界 = 整宽

        val open = tablet(landscape = true, wideSplit = 1f)
        assertEquals(0.44f, open.wideLeftFraction, 1e-6f)
        assertEquals(1126.4f, open.panelBoundaryPx, 1e-3f)   // 0.44 × 2560
        assertTrue("平板 + 横屏 ⇒ 可视化条挂载（v2.5.4 · D 的那一格）", open.visualizerSlot)
    }

    @Test
    fun `平板竖屏 —— 仍是宽屏 但不挂可视化条`() {
        val g = tablet(landscape = false, wideSplit = 1f)
        assertTrue(g.isWidePlayer)
        assertFalse("平板竖屏与 v1.8.0 逐像素一致：不放可视化条", g.visualizerSlot)
    }

    @Test
    fun `宽屏大图落点来自实测值 窄屏来自屏宽`() {
        val t = tablet(landscape = true, wideSplit = 1f)
        assertTrue(t.usesSideCover)
        assertEquals("侧栏布局用实测封面区中心", 300f, t.largeCoverCenterX, 1e-3f)
        assertEquals(400f, t.largeCoverCenterY, 1e-3f)
        assertEquals(600f, t.coverSizePx, 1e-3f)
    }

    @Test
    fun `实测之前的兜底封面尺寸不会退化成 1px`() {
        val t = tablet(landscape = true, wideSplit = 1f, wideCoverSizePx = 0f)
        assertTrue("首帧兜底必须远大于 1px（否则 Coil 按 1px 解码成纯色）", t.coverSizePx > 100f)
        assertEquals(
            PlayerLayout.coverFallbackSizePx(2560f, 1440f), t.coverSizePx, 1e-3f,
        )
    }

    // ---------------------------------------------------------------- 大屏模式

    @Test
    fun `大屏模式生效时走左栏固定比例 且封面走侧栏`() {
        val g = phone(bigScreen = true, wideSplit = 1f).let {
            // 大屏需要「用户点了 + 窗口真的横过来」，手机竖屏下 requested 为真也不生效
            assertEquals(false, it.bigScreenActive)
            it
        }
        assertFalse("窗口没横过来 ⇒ 不生效（第三谓词）", g.bigScreenActive)

        val land = playerCardGeometry(
            density = s6,
            screenWidthPx = 2560f,
            screenHeightPx = 1440f,
            screenWidthDp = 640,
            screenHeightDp = 360,
            smallestScreenWidthDp = 360,
            orientationLandscape = true,
            visualizerEnabled = true,
            wideSplit = 0f,
            bigScreenRequested = true,
            wideCoverCenter = Offset.Zero,
            wideCoverSizePx = 0f,
            statusBarPx = 0f,
        )
        assertTrue(land.bigScreenActive)
        assertTrue(land.usesSideCover)
        assertEquals(
            "大屏分栏边界 = 固定 44%（不随 wideSplit 动画）",
            PlayerLayout.bigScreenLeftBoundaryPx(2560f), land.panelBoundaryPx, 1e-3f,
        )
        assertTrue(land.visualizerSlot)
    }

    // ---------------------------------------------------------------- A 项不变量

    @Test
    fun `任何输入下 两个 weight 都是合法值（铁律 26）`() {
        val splits = listOf(0f, 0.5f, 1f, -1f, 2f, Float.NaN, Float.POSITIVE_INFINITY)
        for (split in splits) {
            val g = tablet(landscape = true, wideSplit = split)
            val left = g.wideLeftFraction
            assertFalse("left 不能是 NaN（wideSplit=$split）", left.isNaN())
            assertTrue("left 必须 > 0（wideSplit=$split）", left > 0f)
            val right = PlayerLayout.wideRightFraction(left)
            assertFalse("right 不能是 NaN（wideSplit=$split）", right.isNaN())
            assertTrue("right 必须 > 0（wideSplit=$split）", right > 0f)
        }
    }

    @Test
    fun `可视化条高度按窗口高夹取（11% 夹在 32~56dp）`() {
        assertEquals(56f, phone().visualizerHeightDp.value, 1e-3f)          // 640dp × 11% ⇒ 夹到 56
        assertEquals(56f, tablet(landscape = true, wideSplit = 0f).visualizerHeightDp.value, 1e-3f)
    }

    @Test
    fun `背景级波形高度按窗口高夹取（20% 夹在 120dp 以内）`() {
        assertEquals(120f, phone().waveBackdropHeightDp.value, 1e-3f)        // 640 × 20% = 128 ⇒ 夹到 120
        assertEquals(120f, tablet(landscape = true, wideSplit = 0f).waveBackdropHeightDp.value, 1e-3f)
    }

    @Test
    fun `音质选择器高度上限按窗口高 70% 夹取`() {
        assertEquals(400f, phone().qualityPickerMaxHeightDp.value, 1e-3f)    // 640 × 0.7 = 448 ⇒ 夹到 400
        // PCL110 横屏（2800×1272px / density 560 ⇒ 3.5）：可用高 363dp
        val short = playerCardGeometry(
            density = Density(3.5f, 1f), screenWidthPx = 2800f, screenHeightPx = 1272f,
            screenWidthDp = 800, screenHeightDp = 363, smallestScreenWidthDp = 800,
            orientationLandscape = true, visualizerEnabled = true, wideSplit = 0f,
            bigScreenRequested = true, wideCoverCenter = Offset.Zero, wideCoverSizePx = 0f,
            statusBarPx = 0f,
        )
        assertEquals(254.4f, short.qualityPickerMaxHeightDp.value, 1e-1f)    // 1272×0.7 = 890px ÷ 3.5
    }
}
