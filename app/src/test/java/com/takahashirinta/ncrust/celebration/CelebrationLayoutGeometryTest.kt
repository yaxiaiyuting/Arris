package com.takahashirinta.ncrust.celebration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.6.3：Tux 彩蛋「按钮出屏 / 插画错位」的几何判据。
 *
 * 数字来自实机等价的 320×568dp 工况（系统栏 92dp ⇒ 视口 476dp；标题 153dp）与
 * 360×640dp（视口 548dp）。旧实现的 200dp 硬地板在这两个工况下分别溢出 53dp / 不溢出，
 * 而「footer 从 12dp 长到 176dp」会让插画高度在一帧里掉 43% —— 这就是错位。
 */
class CelebrationLayoutGeometryTest {

    private val artMin = 72

    @Test
    fun `320x568 上标题加按钮加插画必须放得下`() {
        // 476 = 568 − 92（系统栏）；footer 预留 200dp（WAITING）
        val art = celebrationArtHeightPx(viewportPx = 476, topPx = 153, bottomPx = 200, minArtPx = artMin)
        assertEquals("插画应拿到剩余空间", 123, art)
        assertTrue("总高不得超过视口：153 + $art + 200 = ${153 + art + 200}", 153 + art + 200 <= 476)
        assertTrue("插画不得低于最小可见高度", art >= artMin)
    }

    @Test
    fun `360x640 上插画仍要明显大于最小高度`() {
        val art = celebrationArtHeightPx(viewportPx = 548, topPx = 153, bottomPx = 200, minArtPx = artMin)
        assertEquals(195, art)
        assertTrue(art > artMin * 2)
    }

    @Test
    fun `极小视口只保证按钮，插画让位到最小高度`() {
        val art = celebrationArtHeightPx(viewportPx = 400, topPx = 180, bottomPx = 220, minArtPx = artMin)
        assertEquals("空间不足时插画固定在最小高度，页面可滚动", artMin, art)
        assertTrue("这时总高确实超过视口（允许滚动兜底）", 180 + art + 220 > 400)
    }

    @Test
    fun `视口未知时不产生负数`() {
        assertEquals(artMin, celebrationArtHeightPx(0, 153, 200, artMin))
    }
}
