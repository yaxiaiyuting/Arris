package com.takahashirinta.ncrust.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窄屏大封面落点：Regular 空间足够时保持「满宽 + 原锚点」，矮屏才收缩并夹回可用带。
 * 纯几何，不需要 Compose 环境。
 */
class CoverGeometryTest {

    @Test
    fun `regular phone keeps safety margin and original anchor`() {
        // 384x896dp @ 3x：宽 1152px、高 2688px；顶栏 56dp、控件 260dp、标题 90dp。
        val g = narrowCoverGeometry(
            availableWidthPx = 1152f,
            contentHeightPx = 2688f,
            topBarBottomPx = 168f,
            controlsHeightPx = 780f,
            titleReservePx = 270f,
            minGapPx = 36f,
            preferredCenterYPx = 2688f * 0.3f + 72f,
        )
        assertEquals(1152f * 0.88f, g.sizePx, 0.01f)
        assertEquals(576f, g.centerXPx, 0.01f)
        assertEquals(2688f * 0.3f + 72f, g.centerYPx, 0.01f)
    }

    @Test
    fun `tiny screen shrinks cover to the available band and centers it`() {
        // 320x427dp @ 2x：高 854px，控件 + 标题几乎吃掉下半屏。
        val g = narrowCoverGeometry(
            availableWidthPx = 640f,
            contentHeightPx = 854f,
            topBarBottomPx = 112f,
            controlsHeightPx = 400f,
            titleReservePx = 90f,
            minGapPx = 24f,
            preferredCenterYPx = 854f * 0.3f + 48f,
        )
        assertTrue("封面必须收缩", g.sizePx < 640f)
        assertTrue("封面必须留在顶栏下方", g.centerYPx - g.sizePx / 2f >= 112f - 0.01f)
        assertTrue("封面必须留在控件上方", g.centerYPx + g.sizePx / 2f <= 854f - 400f - 90f - 24f + 0.01f)
        assertEquals(320f, g.centerXPx, 0.01f)
    }

    @Test
    fun `coerced band never produces an inverted center range`() {
        // 控件/标题比整屏还高：上界会小于下界，这里必须退回带内下界而不是崩。
        val g = narrowCoverGeometry(
            availableWidthPx = 1080f,
            contentHeightPx = 1000f,
            topBarBottomPx = 900f,
            controlsHeightPx = 500f,
            titleReservePx = 200f,
            minGapPx = 24f,
            preferredCenterYPx = 500f,
        )
        assertTrue(g.sizePx >= 1f)
        assertTrue(g.centerYPx >= 900f)
    }
}
