/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LyricPosterLayout] 的单测（v3.3.0 · 需求第 9 条）。
 *
 * 出图的排版在真机上「看起来不对」时极难定位：是字号选错了、还是矩形算错了？
 * 这一组用例把两件事分开钉：
 *
 * - **几何**（[LyricPosterLayout.plan]）：封面 / 标题 / 歌词区 / 页脚的矩形必须都在画布内、
 *   互不重叠、且「无封面」版式确实比「有封面」多出高度；
 * - **档位**（[LyricPosterLayout.chooseTextSize]）：用一个**假的** measure 覆盖
 *   「最大档放得下 / 只有小档放得下 / 全放不下」三种情形 —— 真 measure 要 `StaticLayout`，
 *   那是渲染层的事（见 `LyricPosterRenderer`）。
 */
class LyricPosterLayoutTest {

    // ------------------------------------------------------------ 几何

    @Test
    fun `画布是 1080x1920_占位内存约 8_3MB_在 minSdk 24 上可接受`() {
        val plan = LyricPosterLayout.plan(hasCover = true)
        assertEquals(1080, plan.widthPx)
        assertEquals(1920, plan.heightPx)
        assertEquals(2_073_600, plan.widthPx * plan.heightPx)
        // ARGB_8888：单张 ≈ 8.3MB。这是「必须有 720×1280 降级档」的量化理由。
        assertEquals(8_294_400, plan.widthPx * plan.heightPx * 4)
    }

    @Test
    fun `带封面版式_封面 360 直角_标题在右侧_歌词区高度为正`() {
        val plan = LyricPosterLayout.plan(hasCover = true)
        assertEquals(80, plan.coverLeftPx)
        assertEquals(96, plan.coverTopPx)
        assertEquals(360, plan.coverSizePx)
        // 标题左 = 80 + 360 + 48
        assertEquals(488, plan.titleLeftPx)
        assertEquals(1080 - 80 - 488, plan.titleWidthPx)
        assertTrue(plan.lyricsHeightPx > 0)
        assertTrue(plan.hasCover)
    }

    @Test
    fun `无封面版式_标题回到左边缘_歌词区比带封面时更高`() {
        val withCover = LyricPosterLayout.plan(hasCover = true)
        val noCover = LyricPosterLayout.plan(hasCover = false)
        assertEquals(80, noCover.titleLeftPx)
        assertEquals(1080 - 160, noCover.titleWidthPx)
        assertEquals(0, noCover.coverSizePx)
        assertFalse(noCover.hasCover)
        assertTrue(
            "拿不到封面时正文应当拿到更多空间，而不是在封面位置留一块空洞",
            noCover.lyricsHeightPx > withCover.lyricsHeightPx,
        )
        assertTrue(noCover.lyricsTopPx < withCover.lyricsTopPx)
    }

    @Test
    fun `所有矩形都落在画布内_且歌词区不压到页脚`() {
        listOf(true, false).forEach { hasCover ->
            val plan = LyricPosterLayout.plan(hasCover)
            assertTrue(plan.coverLeftPx >= 0 && plan.coverTopPx >= 0)
            assertTrue(plan.coverLeftPx + plan.coverSizePx <= plan.widthPx)
            assertTrue(plan.coverTopPx + plan.coverSizePx <= plan.heightPx)
            assertTrue(plan.titleLeftPx >= 0)
            assertTrue(plan.titleLeftPx + plan.titleWidthPx <= plan.widthPx)
            assertTrue(plan.lyricsLeftPx >= 0)
            assertTrue(plan.lyricsLeftPx + plan.lyricsWidthPx <= plan.widthPx)
            assertTrue("歌词区不能压到页脚", plan.lyricsTopPx + plan.lyricsHeightPx <= plan.footerTopPx)
            assertTrue("页脚要在底部色条之上", plan.footerTopPx + plan.accentBarPx <= plan.heightPx)
            assertTrue(plan.lyricsHeightPx > 600)
        }
    }

    @Test
    fun `页脚与上下色条的位置对称_都是直角矩形`() {
        val plan = LyricPosterLayout.plan(hasCover = true)
        assertEquals(16, plan.accentBarPx)
        assertEquals(1920 - 16 - 168, plan.footerTopPx)
    }

    @Test
    fun `OOM 降级档_整体缩放到 720x1280_每个字段都跟着缩`() {
        val plan = LyricPosterLayout.plan(hasCover = true)
        val small = LyricPosterLayout.scalePlan(plan, LyricPosterLayout.FALLBACK_SCALE)
        assertEquals(720, small.widthPx)
        assertEquals(1280, small.heightPx)
        assertEquals(Math.round(plan.coverSizePx * 2f / 3f), small.coverSizePx)
        assertEquals(Math.round(plan.lyricsTopPx * 2f / 3f), small.lyricsTopPx)
        assertEquals(Math.round(plan.footerTopPx * 2f / 3f), small.footerTopPx)
        assertEquals(Math.round(plan.accentBarPx * 2f / 3f), small.accentBarPx)
        // 缩放后仍然满足「歌词区不压到页脚」
        assertTrue(small.lyricsTopPx + small.lyricsHeightPx <= small.footerTopPx)
    }

    // ------------------------------------------------------------ 字号自适应

    @Test
    fun `字号自适应_行数少时选最大档`() {
        // 3 行、每行高 = 字号 ⇒ 80*3 = 240 <= 1184
        val size = LyricPosterLayout.chooseTextSize(availableHeightPx = 1184) { it * 3 }
        assertEquals(80, size)
    }

    @Test
    fun `字号自适应_行数多时逐级降档_直到放得下`() {
        // 11 行、每行高 = 字号 * 1.6（模拟含行距与译文的真实排版高度）
        val size = LyricPosterLayout.chooseTextSize(availableHeightPx = 1184) { (it * 1.6f * 11).toInt() }
        // 80→1408、72→1267 都放不下；64→1126 放得下 ⇒ 选 64
        assertEquals(64, size)
    }

    @Test
    fun `字号自适应_全部放不下时返回最小档_由调用方截尾`() {
        val size = LyricPosterLayout.chooseTextSize(availableHeightPx = 100) { 999_999 }
        assertEquals(LyricPosterLayout.TEXT_SIZES.last(), size)
    }

    @Test
    fun `字号自适应_候选乱序也能选对_内部会降序排`() {
        val shuffled = intArrayOf(36, 80, 48, 72, 56)
        val size = LyricPosterLayout.chooseTextSize(shuffled, availableHeightPx = 1000) { it * 14 }
        // 80*14=1120 > 1000；72*14=1008 > 1000；64 不在候选里；56*14=784 <= 1000 ⇒ 56
        assertEquals(56, size)
    }

    @Test
    fun `字号自适应_档位表本身是降序且覆盖 1 到 20 行`() {
        val sizes = LyricPosterLayout.TEXT_SIZES
        assertTrue(sizes.size >= 8)
        assertEquals(sizes.toList().sortedDescending(), sizes.toList())
        // 20 行时仍能拿到 >= 28px 的字号（1080 宽上仍然清晰）
        assertTrue(sizes.last() >= 28)
        val twentyLines = LyricPosterLayout.chooseTextSize(availableHeightPx = 1184) { it * 20 }
        assertTrue("20 行也得能放下（否则会触发截尾）", twentyLines * 20 <= 1184)
    }

    // ------------------------------------------------------------ 长行截断

    @Test
    fun `长行截断_不超上限时原样返回`() {
        assertEquals("短句", LyricPosterLayout.truncateLine("短句"))
        val exact = "字".repeat(LyricPosterLayout.MAX_LINE_CHARS)
        assertEquals(exact, LyricPosterLayout.truncateLine(exact))
    }

    @Test
    fun `长行截断_超限时截到上限减一再加省略号`() {
        val long = "字".repeat(200)
        val cut = LyricPosterLayout.truncateLine(long)
        assertEquals(LyricPosterLayout.MAX_LINE_CHARS, cut.length)
        assertTrue(cut.endsWith("…"))
        assertEquals("字".repeat(LyricPosterLayout.MAX_LINE_CHARS - 1), cut.dropLast(1))
    }

    @Test
    fun `长行截断_不把 emoji 的代理对切开`() {
        // 每个 emoji 占 2 个 char。截断点落在低位代理上时正好是完整字符，落在高位代理上必须再退一个。
        val emoji = "😀".repeat(60)
        val cut = LyricPosterLayout.truncateLine(emoji, maxChars = 11)
        assertEquals("😀😀😀😀😀…", cut)
        val head = cut.dropLast(1)
        assertEquals("10 个 char 正好 5 个码点，没有半个代理", 5, head.codePointCount(0, head.length))

        // 让截断点**正好**落在高位代理上：maxChars = 12 ⇒ cut = 11 ⇒ text[10] 是第 6 个 emoji 的高位代理
        val cut2 = LyricPosterLayout.truncateLine(emoji, maxChars = 12)
        assertEquals("😀😀😀😀😀…", cut2)
        val head2 = cut2.dropLast(1)
        assertEquals(5, head2.codePointCount(0, head2.length))
    }

    @Test
    fun `长行截断_边界入参不抛异常`() {
        assertEquals("…", LyricPosterLayout.truncateLine("abc", maxChars = 1))
        assertEquals("", LyricPosterLayout.truncateLine("", maxChars = 0))
        // 截断点前的空白要裁掉，避免出现「字 …」
        assertEquals("abc…", LyricPosterLayout.truncateLine("abc   def", maxChars = 5))
    }
}
